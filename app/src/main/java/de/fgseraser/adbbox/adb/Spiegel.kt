// ADB Box – Bildschirm spiegeln und steuern (wie scrcpy, ohne PC).
// Der offizielle scrcpy-Server (Apache-2.0, Genymobile) wird beim ersten Mal von GitHub geladen,
// per ADB aufs Gerät geschoben und gestartet. Das Video (H.264) wird hier mit MediaCodec angezeigt.
// von FGSeRaser – https://github.com/FGSeRaser
package de.fgseraser.adbbox.adb

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.security.MessageDigest

class Spiegel(
    private val v: AdbVerbindung,
    private val maxGroesse: Int,
    private val bitrate: Int,
    private val beiGroesse: (Int, Int) -> Unit,
    private val beiFehler: (String) -> Unit,
) {
    companion object {
        const val VERSION = "2.7"
        private const val URL_SERVER = "https://github.com/Genymobile/scrcpy/releases/download/v2.7/scrcpy-server-v2.7"
        private const val SHA256 = "a23c5659f36c260f105c022d27bcb3eafffa26070e7baa9eda66d01377a1adba"
        private const val GERAET_PFAD = "/data/local/tmp/adbbox-scrcpy-server.jar"

        /** Lädt den scrcpy-Server (einmalig) und prüft die Prüfsumme. */
        fun serverDatei(ctx: Context): File {
            val f = File(ctx.filesDir, "scrcpy-server-v$VERSION")
            if (f.exists() && sha256(f.readBytes()) == SHA256) return f
            val c = URL(URL_SERVER).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = true
            c.connectTimeout = 15000
            c.readTimeout = 30000
            val daten = c.inputStream.use { it.readBytes() }
            if (sha256(daten) != SHA256) throw IOException("scrcpy-Server: Prüfsumme stimmt nicht")
            f.writeBytes(daten)
            return f
        }

        private fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

        // Tastencodes (Android KeyEvent)
        const val TASTE_HOME = 3
        const val TASTE_ZURUECK = 4
        const val TASTE_APPS = 187
        const val TASTE_POWER = 26
        const val TASTE_LAUTER = 24
        const val TASTE_LEISER = 25
    }

    @Volatile var laeuft = false
        private set
    @Volatile var breite = 0
        private set
    @Volatile var hoehe = 0
        private set
    private var serverStrom: AdbStrom? = null
    private var video: AdbStrom? = null
    private var steuerung: AdbStrom? = null
    private var codec: MediaCodec? = null
    private val schreibSperre = Any()

    /** Startet alles; blockiert, bis das Video endet. Auf einem Hintergrund-Thread aufrufen. */
    fun starten(ctx: Context, surface: Surface) {
        laeuft = true
        try {
            val datei = serverDatei(ctx)
            datei.inputStream().use { AdbSync.hochladen(v, it, GERAET_PFAD) }
            val befehl = "CLASSPATH=$GERAET_PFAD app_process / com.genymobile.scrcpy.Server $VERSION " +
                "log_level=info audio=false video_codec=h264 max_size=$maxGroesse video_bit_rate=$bitrate " +
                "tunnel_forward=true control=true cleanup=true stay_awake=false"
            serverStrom = v.oeffnen("shell:$befehl")
            // Server-Ausgabe mitlesen (für Fehlermeldungen)
            val log = StringBuilder()
            Thread({
                try {
                    val s = serverStrom ?: return@Thread
                    val b = ByteArray(1024)
                    while (true) {
                        val n = s.lesen(b, 0, b.size)
                        if (n < 0) break
                        synchronized(log) { log.append(String(b, 0, n)); if (log.length > 4000) log.delete(0, log.length - 4000) }
                    }
                } catch (_: Exception) {}
            }, "scrcpy-log").apply { isDaemon = true; start() }

            var vs: AdbStrom? = null
            for (i in 0 until 60) {
                try {
                    vs = v.oeffnen("localabstract:scrcpy", 3000)
                    break
                } catch (_: Exception) {
                    if (serverStrom?.istZu == true) break
                    Thread.sleep(100)
                }
            }
            if (vs == null) throw IOException("scrcpy-Server startet nicht.\n" + synchronized(log) { log.toString().takeLast(800) })
            video = vs
            vs.lesenGenau(1)                              // Dummy-Byte
            steuerung = v.oeffnen("localabstract:scrcpy", 5000)
            vs.lesenGenau(64)                             // Gerätename
            val meta = ByteBuffer.wrap(vs.lesenGenau(12)) // Codec, Breite, Höhe (Big Endian)
            meta.int
            breite = meta.int
            hoehe = meta.int
            beiGroesse(breite, hoehe)

            val mc = MediaCodec.createDecoderByType("video/avc")
            val fmt = MediaFormat.createVideoFormat("video/avc", breite, hoehe)
            mc.configure(fmt, surface, null, 0)
            mc.start()
            codec = mc
            Thread({ ausgabeSchleife(mc) }, "scrcpy-ausgabe").apply { isDaemon = true; start() }

            while (laeuft) {
                val kopf = ByteBuffer.wrap(vs.lesenGenau(12))
                val ptsFlags = kopf.long
                val groesse = kopf.int
                val daten = vs.lesenGenau(groesse)
                val konfig = (ptsFlags ushr 63) == 1L
                val pts = ptsFlags and 0x3FFFFFFFFFFFFFFFL
                var idx = -1
                while (laeuft && idx < 0) idx = mc.dequeueInputBuffer(10_000)
                if (idx < 0) break
                val buf = mc.getInputBuffer(idx) ?: continue
                buf.clear()
                buf.put(daten)
                mc.queueInputBuffer(idx, 0, daten.size, if (konfig) 0 else pts, if (konfig) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0)
            }
        } catch (e: Exception) {
            if (laeuft) beiFehler(e.message ?: e.toString())
        } finally {
            stoppen()
        }
    }

    private fun ausgabeSchleife(mc: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (laeuft) {
                val idx = mc.dequeueOutputBuffer(info, 10_000)
                if (idx >= 0) {
                    mc.releaseOutputBuffer(idx, true)
                } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = mc.outputFormat
                    var w = f.getInteger(MediaFormat.KEY_WIDTH)
                    var h = f.getInteger(MediaFormat.KEY_HEIGHT)
                    if (f.containsKey("crop-right") && f.containsKey("crop-left")) w = f.getInteger("crop-right") - f.getInteger("crop-left") + 1
                    if (f.containsKey("crop-bottom") && f.containsKey("crop-top")) h = f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1
                    if (w > 0 && h > 0 && (w != breite || h != hoehe)) {
                        breite = w
                        hoehe = h
                        beiGroesse(w, h)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    fun stoppen() {
        laeuft = false
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
        for (s in listOf(video, steuerung, serverStrom)) try { s?.close() } catch (_: Exception) {}
        video = null
        steuerung = null
        serverStrom = null
    }

    // ein einziger Sende-Thread, damit Runter/Bewegen/Hoch in der richtigen Reihenfolge ankommen
    private val sender = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun senden(b: ByteArray) {
        val s = steuerung ?: return
        sender.execute {
            try { synchronized(schreibSperre) { s.schreiben(b) } } catch (_: Exception) {}
        }
    }

    /** Berührung: aktion 0 = runter, 1 = hoch, 2 = bewegen. x/y in Videopunkten. */
    fun beruehrung(aktion: Int, zeiger: Long, x: Int, y: Int) {
        if (breite == 0) return
        val b = ByteBuffer.allocate(32)
        b.put(2.toByte())
        b.put(aktion.toByte())
        b.putLong(zeiger)
        b.putInt(x)
        b.putInt(y)
        b.putShort(breite.toShort())
        b.putShort(hoehe.toShort())
        b.putShort(if (aktion == 1) 0 else 0xffff.toShort())
        b.putInt(1)   // Aktionstaste: primär
        b.putInt(if (aktion == 1) 0 else 1)
        senden(b.array())
    }

    /** Taste drücken und loslassen. */
    fun taste(code: Int) {
        for (aktion in 0..1) {
            val b = ByteBuffer.allocate(14)
            b.put(0.toByte())
            b.put(aktion.toByte())
            b.putInt(code)
            b.putInt(0)
            b.putInt(0)
            senden(b.array())
        }
    }

    /** Text eintippen (nur einfache Zeichen). */
    fun text(t: String) {
        val d = t.toByteArray()
        val b = ByteBuffer.allocate(5 + d.size)
        b.put(1.toByte())
        b.putInt(d.size)
        b.put(d)
        senden(b.array())
    }
}
