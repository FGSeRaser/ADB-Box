// ADB Box – RSA-Schlüssel für ADB (wird einmal erzeugt und in der App gespeichert)
// und Dateiübertragung über den sync:-Dienst (wie "adb push/pull").
// von FGSeRaser – https://github.com/FGSeRaser
package de.fgseraser.adbbox.adb

import android.content.Context
import android.util.Base64
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

object AdbSchluessel {
    private val SHA1_KOPF = byteArrayOf(
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    )

    @Volatile private var zwischen: KeyPair? = null

    fun laden(ctx: Context): KeyPair {
        zwischen?.let { return it }
        val priv = File(ctx.filesDir, "adbkey")
        val pub = File(ctx.filesDir, "adbkey.pub.der")
        val kf = KeyFactory.getInstance("RSA")
        val paar = if (priv.exists() && pub.exists()) {
            KeyPair(kf.generatePublic(X509EncodedKeySpec(pub.readBytes())), kf.generatePrivate(PKCS8EncodedKeySpec(priv.readBytes())))
        } else {
            val g = KeyPairGenerator.getInstance("RSA")
            g.initialize(2048)
            val p = g.generateKeyPair()
            priv.writeBytes(p.private.encoded)
            pub.writeBytes(p.public.encoded)
            p
        }
        zwischen = paar
        return paar
    }

    /** Signatur für das Anmelde-Token (Token gilt als fertiger SHA-1-Wert). */
    fun signieren(paar: KeyPair, token: ByteArray): ByteArray {
        val c = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        c.init(Cipher.ENCRYPT_MODE, paar.private)
        return c.doFinal(SHA1_KOPF + token)
    }

    /** Öffentlicher Schlüssel im Android-Format (wie ~/.android/adbkey.pub). */
    fun oeffentlich(paar: KeyPair): ByteArray {
        val pub = paar.public as RSAPublicKey
        val n = pub.modulus
        val worte = 64
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = n.mod(r32).modInverse(r32).negate().mod(r32)
        val r = BigInteger.ONE.shiftLeft(worte * 32)
        val rr = r.multiply(r).mod(n)
        val b = ByteBuffer.allocate(4 + 4 + worte * 4 + worte * 4 + 4).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(worte)
        b.putInt(n0inv.toInt())
        val maske = BigInteger.valueOf(0xffffffffL)
        for (i in 0 until worte) b.putInt(n.shiftRight(32 * i).and(maske).toLong().toInt())
        for (i in 0 until worte) b.putInt(rr.shiftRight(32 * i).and(maske).toLong().toInt())
        b.putInt(pub.publicExponent.toInt())
        val text = Base64.encodeToString(b.array(), Base64.NO_WRAP) + " ADB-Box@FGSeRaser\u0000"
        return text.toByteArray()
    }

    /** Fingerabdruck (wie ihn das Gerät beim Zulassen anzeigt). */
    fun fingerabdruck(paar: KeyPair): String {
        val text = String(oeffentlich(paar)).substringBefore(' ')
        val md = java.security.MessageDigest.getInstance("MD5").digest(Base64.decode(text, Base64.NO_WRAP))
        return md.joinToString(":") { "%02X".format(it) }
    }
}

object AdbSync {
    private fun anfrage(id: String, wert: Int, daten: ByteArray = ByteArray(0)): ByteArray {
        val b = ByteBuffer.allocate(8 + daten.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put(id.toByteArray()).putInt(wert).put(daten)
        return b.array()
    }

    private fun int(b: ByteArray, off: Int = 4): Int = ByteBuffer.wrap(b, off, 4).order(ByteOrder.LITTLE_ENDIAN).int

    /** Datei aufs Gerät schieben (adb push). */
    fun hochladen(v: AdbVerbindung, quelle: InputStream, ziel: String, modus: Int = "644".toInt(8), fortschritt: (Long) -> Unit = {}) {
        v.oeffnen("sync:").use { s ->
            val spec = "$ziel,${0x8000 or modus}".toByteArray()
            s.schreiben(anfrage("SEND", spec.size, spec))
            val puffer = ByteArray(60 * 1024)
            var gesamt = 0L
            while (true) {
                val n = quelle.read(puffer)
                if (n < 0) break
                if (n == 0) continue
                s.schreiben(anfrage("DATA", n, puffer.copyOf(n)))
                gesamt += n
                fortschritt(gesamt)
            }
            s.schreiben(anfrage("DONE", (System.currentTimeMillis() / 1000).toInt()))
            val antwort = s.lesenGenau(8)
            val id = String(antwort, 0, 4)
            if (id == "FAIL") throw IOException(String(s.lesenGenau(int(antwort))))
            if (id != "OKAY") throw IOException("Unerwartete Antwort: $id")
        }
    }

    /** Datei vom Gerät holen (adb pull). */
    fun herunterladen(v: AdbVerbindung, quelle: String, ziel: OutputStream, fortschritt: (Long) -> Unit = {}) {
        v.oeffnen("sync:").use { s ->
            val pfad = quelle.toByteArray()
            s.schreiben(anfrage("RECV", pfad.size, pfad))
            var gesamt = 0L
            while (true) {
                val kopf = s.lesenGenau(8)
                val id = String(kopf, 0, 4)
                val n = int(kopf)
                when (id) {
                    "DATA" -> {
                        ziel.write(s.lesenGenau(n))
                        gesamt += n
                        fortschritt(gesamt)
                    }
                    "DONE" -> break
                    "FAIL" -> throw IOException(String(s.lesenGenau(n)))
                    else -> throw IOException("Unerwartete Antwort: $id")
                }
            }
        }
    }
}
