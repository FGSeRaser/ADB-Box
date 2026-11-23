// ADB Box – eigene ADB-Verbindung über das Netzwerk (wie "adb connect host:port").
// Spricht das ADB-Protokoll direkt: Anmeldung mit RSA-Schlüssel oder – bei „Kabelloses Debugging“
// (Android 11+) – per TLS 1.3 nach dem Koppeln. Ströme für shell:, sync:, localabstract: …
// von FGSeRaser – https://github.com/FGSeRaser
package de.fgseraser.adbbox.adb

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPair
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AdbVerbindung private constructor(private var sock: Socket, private val host: String, private val port: Int) : Closeable {

    companion object {
        const val CNXN = 0x4e584e43
        const val AUTH = 0x48545541
        const val OPEN = 0x4e45504f
        const val OKAY = 0x59414b4f
        const val CLSE = 0x45534c43
        const val WRTE = 0x45545257
        const val STLS = 0x534c5453
        private const val VERSION = 0x01000000
        private const val MAX_DATA = 256 * 1024

        /** Baut die Verbindung auf. [beiNachfrage] wird aufgerufen, wenn das Gerät "USB-Debugging zulassen?" anzeigt. */
        fun verbinden(host: String, port: Int, schluessel: KeyPair, beiNachfrage: () -> Unit = {}): AdbVerbindung {
            val s = Socket()
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), 10_000)
            val v = AdbVerbindung(s, host, port)
            try {
                v.anmelden(schluessel, beiNachfrage)
            } catch (e: Exception) {
                v.close()
                throw e
            }
            return v
        }
    }

    private var ein = DataInputStream(BufferedInputStream(sock.getInputStream(), 64 * 1024))
    private var aus: OutputStream = sock.getOutputStream()
    /** true = Verbindung läuft verschlüsselt (Kabelloses Debugging) */
    var tls = false
        private set
    private val stroeme = ConcurrentHashMap<Int, AdbStrom>()
    private val naechsteId = AtomicInteger(1)
    var maxDaten = 4096
        private set
    var geraeteInfo = ""
        private set
    @Volatile var offen = true
        private set

    private class Nachricht(val befehl: Int, val arg0: Int, val arg1: Int, val daten: ByteArray)

    private fun lesen(): Nachricht {
        val kopf = ByteArray(24)
        ein.readFully(kopf)
        val b = ByteBuffer.wrap(kopf).order(ByteOrder.LITTLE_ENDIAN)
        val befehl = b.int
        val a0 = b.int
        val a1 = b.int
        val laenge = b.int
        b.int // Prüfsumme
        b.int // magic
        val daten = ByteArray(laenge)
        if (laenge > 0) ein.readFully(daten)
        return Nachricht(befehl, a0, a1, daten)
    }

    @Synchronized
    internal fun senden(befehl: Int, arg0: Int, arg1: Int, daten: ByteArray = ByteArray(0)) {
        var summe = 0
        for (x in daten) summe += (x.toInt() and 0xff)
        val b = ByteBuffer.allocate(24 + daten.size).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(befehl).putInt(arg0).putInt(arg1).putInt(daten.size).putInt(summe).putInt(befehl.inv())
        b.put(daten)
        aus.write(b.array())
        aus.flush()
    }

    private fun anmelden(schluessel: KeyPair, beiNachfrage: () -> Unit) {
        sock.soTimeout = 90_000   // Zeit, um auf dem Gerät "Zulassen" zu tippen
        senden(CNXN, VERSION, MAX_DATA, "host::\u0000".toByteArray())
        var signiert = false
        while (true) {
            val n = lesen()
            when (n.befehl) {
                CNXN -> {
                    maxDaten = n.arg1.coerceIn(1024, MAX_DATA)
                    geraeteInfo = String(n.daten).trim('\u0000')
                    break
                }
                STLS -> {
                    // Kabelloses Debugging: ab hier TLS 1.3 mit unserem Zertifikat
                    senden(STLS, VERSION, 0)
                    val s = try {
                        AdbTls.umschalten(sock, host, port, schluessel)
                    } catch (e: Exception) {
                        throw IOException("TLS abgelehnt – das Gerät ist noch nicht gekoppelt. Erst „Mit Kopplungscode koppeln“ oder QR-Code nutzen. (${e.message})")
                    }
                    sock = s
                    ein = DataInputStream(BufferedInputStream(s.inputStream, 64 * 1024))
                    aus = s.outputStream
                    tls = true
                }
                AUTH -> {
                    if (tls || n.arg0 != 1) continue
                    if (!signiert) {
                        signiert = true
                        senden(AUTH, 2, 0, AdbSchluessel.signieren(schluessel, n.daten))
                    } else {
                        senden(AUTH, 3, 0, AdbSchluessel.oeffentlich(schluessel))
                        beiNachfrage()
                    }
                }
                else -> {}
            }
        }
        sock.soTimeout = 0
        Thread({ leseSchleife() }, "adb-lesen").apply { isDaemon = true; start() }
    }

    private fun leseSchleife() {
        try {
            while (offen) {
                val n = lesen()
                when (n.befehl) {
                    OKAY -> stroeme[n.arg1]?.okay(n.arg0)
                    WRTE -> {
                        val s = stroeme[n.arg1]
                        if (s != null) {
                            s.empfangen(n.daten)
                            senden(OKAY, n.arg1, n.arg0)
                        }
                    }
                    CLSE -> stroeme.remove(n.arg1)?.geschlossen()
                    else -> {}
                }
            }
        } catch (_: Exception) {
        } finally {
            offen = false
            for (s in stroeme.values) s.geschlossen()
            stroeme.clear()
        }
    }

    /** Öffnet einen Dienst, z. B. "shell:ls", "sync:" oder "localabstract:scrcpy". */
    fun oeffnen(dienst: String, wartezeitMs: Long = 10_000): AdbStrom {
        if (!offen) throw IOException("Verbindung getrennt")
        val id = naechsteId.getAndIncrement()
        val s = AdbStrom(this, id)
        stroeme[id] = s
        senden(OPEN, id, 0, (dienst + "\u0000").toByteArray())
        if (!s.warteOffen(wartezeitMs)) {
            stroeme.remove(id)
            throw IOException("Dienst nicht verfügbar: ${dienst.substringBefore(':')}")
        }
        return s
    }

    /** Führt einen Shell-Befehl aus und gibt die Ausgabe zurück. */
    fun shell(befehl: String): String {
        val s = oeffnen("shell:$befehl")
        return s.use { String(it.allesLesen()) }
    }

    internal fun entfernen(id: Int) {
        stroeme.remove(id)
    }

    override fun close() {
        offen = false
        try { sock.close() } catch (_: Exception) {}
    }
}

/** Ein ADB-Strom (ein geöffneter Dienst) – liest und schreibt Bytes. */
class AdbStrom internal constructor(private val v: AdbVerbindung, val lokaleId: Int) : Closeable {
    @Volatile private var entfernteId = 0
    private val offenSignal = CountDownLatch(1)
    @Volatile private var geschlossen = false
    private val eingang = LinkedBlockingQueue<ByteArray>()
    private val schreibFrei = Semaphore(0)
    private var aktuell: ByteArray? = null
    private var pos = 0

    internal fun okay(remote: Int) {
        if (entfernteId == 0) {
            entfernteId = remote
            offenSignal.countDown()
        } else {
            schreibFrei.release()
        }
    }

    internal fun empfangen(d: ByteArray) {
        eingang.put(d)
    }

    internal fun geschlossen() {
        geschlossen = true
        offenSignal.countDown()
        eingang.put(ByteArray(0))   // Ende-Markierung
        schreibFrei.release(1000)
    }

    internal fun warteOffen(ms: Long): Boolean {
        offenSignal.await(ms, TimeUnit.MILLISECONDS)
        return entfernteId != 0 && !geschlossen
    }

    val istZu: Boolean get() = geschlossen

    fun schreiben(daten: ByteArray) {
        var off = 0
        while (off < daten.size) {
            if (geschlossen) throw IOException("Strom geschlossen")
            val n = minOf(v.maxDaten, daten.size - off)
            v.senden(AdbVerbindung.WRTE, lokaleId, entfernteId, daten.copyOfRange(off, off + n))
            if (!schreibFrei.tryAcquire(30, TimeUnit.SECONDS)) throw IOException("Zeitüberschreitung beim Senden")
            off += n
        }
    }

    /** Liest bis zu [len] Bytes; -1 am Ende. */
    fun lesen(ziel: ByteArray, off: Int, len: Int): Int {
        while (true) {
            val a = aktuell
            if (a != null && pos < a.size) {
                val n = minOf(len, a.size - pos)
                System.arraycopy(a, pos, ziel, off, n)
                pos += n
                return n
            }
            if (geschlossen && eingang.isEmpty()) return -1
            val neu = eingang.take()
            if (neu.isEmpty()) {
                if (geschlossen) { eingang.put(neu); return -1 }
                continue
            }
            aktuell = neu
            pos = 0
        }
    }

    fun lesenGenau(n: Int): ByteArray {
        val b = ByteArray(n)
        var g = 0
        while (g < n) {
            val r = lesen(b, g, n - g)
            if (r < 0) throw IOException("Verbindung beendet")
            g += r
        }
        return b
    }

    fun allesLesen(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val b = ByteArray(16 * 1024)
        while (true) {
            val r = lesen(b, 0, b.size)
            if (r < 0) break
            out.write(b, 0, r)
        }
        return out.toByteArray()
    }

    fun alsInputStream(): InputStream = object : InputStream() {
        override fun read(): Int {
            val b = ByteArray(1)
            return if (lesen(b, 0, 1) < 0) -1 else (b[0].toInt() and 0xff)
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int = if (len == 0) 0 else lesen(b, off, len)
    }

    override fun close() {
        if (!geschlossen && entfernteId != 0) {
            try { v.senden(AdbVerbindung.CLSE, lokaleId, entfernteId) } catch (_: Exception) {}
        }
        geschlossen = true
        v.entfernen(lokaleId)
    }
}
