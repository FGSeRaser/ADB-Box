// ADB Box – findet Geräte mit „Kabelloses Debugging“: per mDNS (wie „adb mdns services“) und,
// falls das nicht klappt, per Port-Suche auf einer IP (der Port ist bei Android 11+ zufällig, z. B. 37xxx).
// von FGSeRaser – https://github.com/FGSeRaser
package de.fgseraser.adbbox.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resume

object AdbSuche {
    const val TYP_VERBINDEN = "_adb-tls-connect._tcp"
    const val TYP_KOPPELN = "_adb-tls-pairing._tcp"

    data class Dienst(val name: String, val host: String, val port: Int)

    /** Sucht [dauerMs] lang nach mDNS-Diensten eines Typs. [neu] wird für jeden gefundenen Dienst aufgerufen. */
    suspend fun mdns(ctx: Context, typ: String, dauerMs: Long, neu: (Dienst) -> Unit = {}): List<Dienst> {
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
        val roh = CopyOnWriteArrayList<NsdServiceInfo>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(t: String?, c: Int) {}
            override fun onStopDiscoveryFailed(t: String?, c: Int) {}
            override fun onDiscoveryStarted(t: String?) {}
            override fun onDiscoveryStopped(t: String?) {}
            override fun onServiceFound(info: NsdServiceInfo) { roh.add(info) }
            override fun onServiceLost(info: NsdServiceInfo) {}
        }
        val ergebnis = mutableListOf<Dienst>()
        val erledigt = mutableSetOf<String>()
        try {
            nsd.discoverServices(typ, NsdManager.PROTOCOL_DNS_SD, listener)
            val ende = System.currentTimeMillis() + dauerMs
            while (System.currentTimeMillis() < ende) {
                for (info in roh.toList()) {
                    if (!erledigt.add(info.serviceName)) continue
                    val d = aufloesen(nsd, info) ?: continue
                    ergebnis.add(d)
                    withContext(Dispatchers.Main) { neu(d) }
                }
                delay(250)
            }
        } catch (_: Exception) {
        } finally {
            try { nsd.stopServiceDiscovery(listener) } catch (_: Exception) {}
        }
        return ergebnis
    }

    @Suppress("DEPRECATION")
    private suspend fun aufloesen(nsd: NsdManager, info: NsdServiceInfo): Dienst? = withTimeoutOrNull(5000) {
        suspendCancellableCoroutine { k ->
            try {
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(i: NsdServiceInfo?, c: Int) { if (k.isActive) k.resume(null) }
                    override fun onServiceResolved(i: NsdServiceInfo) {
                        val h = i.host?.hostAddress
                        if (k.isActive) k.resume(if (h == null) null else Dienst(i.serviceName, h, i.port))
                    }
                })
            } catch (_: Exception) {
                if (k.isActive) k.resume(null)
            }
        }
    }

    /** Spricht der Port ADB? (Antwort auf CNXN ist CNXN, AUTH oder STLS) */
    fun istAdb(host: String, port: Int): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), 800)
            s.soTimeout = 1500
            val daten = "host::\u0000".toByteArray()
            val b = ByteBuffer.allocate(24 + daten.size).order(ByteOrder.LITTLE_ENDIAN)
            var summe = 0
            for (x in daten) summe += (x.toInt() and 0xff)
            b.putInt(AdbVerbindung.CNXN).putInt(0x01000000).putInt(256 * 1024).putInt(daten.size).putInt(summe).putInt(AdbVerbindung.CNXN.inv()).put(daten)
            s.getOutputStream().write(b.array())
            val kopf = ByteArray(24)
            DataInputStream(s.getInputStream()).readFully(kopf)
            val befehl = ByteBuffer.wrap(kopf).order(ByteOrder.LITTLE_ENDIAN).int
            befehl == AdbVerbindung.CNXN || befehl == AdbVerbindung.AUTH || befehl == AdbVerbindung.STLS
        }
    } catch (_: Exception) {
        false
    }

    /** Sucht auf einer IP den ADB-Port: erst 5555, dann alle hohen Ports (Kabelloses Debugging). fortschritt 0..1 */
    suspend fun portFinden(host: String, fortschritt: (Float) -> Unit = {}): Int? = withContext(Dispatchers.IO) {
        if (istAdb(host, 5555)) return@withContext 5555
        val von = 30000
        val bis = 65535
        val sperre = Semaphore(300)
        var fertig = 0
        var gefunden: Int? = null
        coroutineScope {
            (von..bis).chunked(512).forEach { block ->
                if (gefunden != null) return@forEach
                block.map { p ->
                    async {
                        sperre.withPermit {
                            val offen = try {
                                Socket().use { it.connect(InetSocketAddress(host, p), 350); true }
                            } catch (_: Exception) { false }
                            if (offen && gefunden == null && istAdb(host, p)) gefunden = p
                        }
                    }
                }.awaitAll()
                fertig += block.size
                withContext(Dispatchers.Main) { fortschritt(fertig.toFloat() / (bis - von + 1)) }
            }
        }
        gefunden
    }

    /** Erst mDNS (schnell), dann Port-Suche. */
    suspend fun verbindungsPort(ctx: Context, host: String, fortschritt: (Float) -> Unit = {}): Int? {
        val m = mdns(ctx, TYP_VERBINDEN, 3500).firstOrNull { it.host == host }
        if (m != null) return m.port
        return portFinden(host, fortschritt)
    }

    /** Zufälliger Name und Passwort für das QR-Koppeln. */
    fun qrDaten(): Pair<String, String> {
        val z = "abcdefghijkmnpqrstuvwxyz23456789"
        val r = java.security.SecureRandom()
        val name = "ADBBox-" + (1..6).map { z[r.nextInt(z.length)] }.joinToString("")
        val pw = (1..10).map { z[r.nextInt(z.length)] }.joinToString("")
        return name to pw
    }

    /** Text im QR-Code (so erwartet ihn Android unter „Gerät über QR-Code koppeln“). */
    fun qrText(name: String, passwort: String) = "WIFI:T:ADB;S:$name;P:$passwort;;"
}
