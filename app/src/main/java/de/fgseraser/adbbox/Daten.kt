// ADB Box – gespeicherte Geräte, Schnellbefehle und Hilfsfunktionen (Speichern, Installieren, Updates).
// von FGSeRaser – https://github.com/FGSeRaser
package de.fgseraser.adbbox

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class Befehl(val name: String, val text: String, val perAdb: Boolean = false)

data class Geraet(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val host: String = "",
    val sshPort: Int = 8022,
    val sshBenutzer: String = "user",
    val sshPasswort: String = "",
    val adbPort: Int = 5555,
    val befehle: List<Befehl> = STANDARD_BEFEHLE,
) {
    fun json(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("host", host); put("sshPort", sshPort)
        put("sshBenutzer", sshBenutzer); put("sshPasswort", sshPasswort); put("adbPort", adbPort)
        put("befehle", JSONArray().apply {
            for (b in befehle) put(JSONObject().put("name", b.name).put("text", b.text).put("adb", b.perAdb))
        })
    }

    companion object {
        val STANDARD_BEFEHLE = listOf(
            Befehl("Speicherplatz", "df -h"),
            Befehl("Laufende Programme", "ps -A 2>/dev/null | tail -n 25 || ps"),
            Befehl("Laufzeit", "uptime"),
            Befehl("Netzwerk", "ip -br addr 2>/dev/null || ifconfig"),
            Befehl("Akku", "dumpsys battery", perAdb = true),
            Befehl("Android-Version", "getprop ro.build.version.release; getprop ro.product.model", perAdb = true),
        )

        fun aus(o: JSONObject): Geraet {
            val bl = mutableListOf<Befehl>()
            val a = o.optJSONArray("befehle")
            if (a != null) for (i in 0 until a.length()) {
                val b = a.getJSONObject(i)
                bl.add(Befehl(b.optString("name"), b.optString("text"), b.optBoolean("adb")))
            }
            return Geraet(
                id = o.optString("id", UUID.randomUUID().toString()),
                name = o.optString("name"), host = o.optString("host"),
                sshPort = o.optInt("sshPort", 8022), sshBenutzer = o.optString("sshBenutzer", "user"),
                sshPasswort = o.optString("sshPasswort"), adbPort = o.optInt("adbPort", 5555),
                befehle = if (a == null) STANDARD_BEFEHLE else bl,
            )
        }
    }
}

object Speicher {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("adbbox", Context.MODE_PRIVATE)

    fun geraete(ctx: Context): List<Geraet> {
        val t = prefs(ctx).getString("geraete", "[]") ?: "[]"
        val a = JSONArray(t)
        return (0 until a.length()).map { Geraet.aus(a.getJSONObject(it)) }
    }

    fun speichern(ctx: Context, liste: List<Geraet>) {
        val a = JSONArray()
        for (g in liste) a.put(g.json())
        prefs(ctx).edit().putString("geraete", a.toString()).apply()
    }

    fun setzen(ctx: Context, g: Geraet) {
        val l = geraete(ctx).toMutableList()
        val i = l.indexOfFirst { it.id == g.id }
        if (i >= 0) l[i] = g else l.add(g)
        speichern(ctx, l)
    }

    fun loeschen(ctx: Context, id: String) = speichern(ctx, geraete(ctx).filter { it.id != id })
}

object Hilfe {
    /** Datei in "Downloads/ADB-Box" auf diesem Handy speichern. Gibt den Anzeigepfad zurück. */
    fun inDownloads(ctx: Context, name: String, schreiben: (OutputStream) -> Unit): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val werte = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ADB-Box")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, werte) ?: throw IllegalStateException("Speichern nicht möglich")
            ctx.contentResolver.openOutputStream(uri)!!.use(schreiben)
        } else {
            @Suppress("DEPRECATION")
            val ordner = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ADB-Box")
            ordner.mkdirs()
            File(ordner, name).outputStream().use(schreiben)
        }
        return "Downloads/ADB-Box/$name"
    }

    /** APK auf diesem Handy installieren (Android fragt nach). */
    fun apkInstallieren(ctx: Context, datei: File) {
        val uri = FileProvider.getUriForFile(ctx, "de.fgseraser.adbbox.dateien", datei)
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(i)
    }

    fun inCache(ctx: Context, name: String, quelle: (OutputStream) -> Unit): File {
        val f = File(ctx.cacheDir, name)
        f.outputStream().use(quelle)
        return f
    }

    fun groesse(b: Long): String = when {
        b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
        b >= 1L shl 10 -> "%.0f KB".format(b / 1024.0)
        else -> "$b B"
    }

    fun kopieren(ein: InputStream, aus: OutputStream, fortschritt: (Long) -> Unit = {}) {
        val b = ByteArray(64 * 1024)
        var g = 0L
        while (true) {
            val n = ein.read(b)
            if (n < 0) break
            aus.write(b, 0, n)
            g += n
            fortschritt(g)
        }
    }
}

object Aktualisierung {
    const val REPO = "FGSeRaser/ADB-Box"

    data class Neu(val version: String, val url: String)

    /** Prüft auf GitHub, ob es eine neuere Version gibt. */
    fun pruefen(): Neu? {
        val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 10000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        if (c.responseCode != 200) return null
        val o = JSONObject(c.inputStream.bufferedReader().readText())
        val tag = o.optString("tag_name").removePrefix("v")
        val assets = o.optJSONArray("assets") ?: return null
        var url = ""
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk")) url = a.optString("browser_download_url")
        }
        if (url.isEmpty() || !neuer(tag, BuildConfig.VERSION_NAME)) return null
        return Neu(tag, url)
    }

    fun neuer(a: String, b: String): Boolean {
        val x = a.split(".").map { it.toIntOrNull() ?: 0 }
        val y = b.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrElse(i) { 0 }
            val q = y.getOrElse(i) { 0 }
            if (p != q) return p > q
        }
        return false
    }

    fun laden(ctx: Context, n: Neu): File {
        val c = URL(n.url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true
        val f = File(ctx.cacheDir, "ADB-Box-${n.version}.apk")
        c.inputStream.use { ein -> f.outputStream().use { ein.copyTo(it) } }
        return f
    }
}
