// ADB Box – Netzwerk-Scan: findet Geräte im WLAN mit offenem ADB- (5555) oder SSH-Port (8022/22)
// und prüft, ob gespeicherte Geräte gerade erreichbar sind.
// von FGSeRaser – https://github.com/FGSeRaser
@file:OptIn(ExperimentalMaterial3Api::class)

package de.fgseraser.adbbox

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

object Scan {
    val PORTS = mapOf(5555 to "ADB", 8022 to "SSH (Termux)", 22 to "SSH")

    data class Fund(val ip: String, val name: String, val ports: List<Int>)

    fun offen(host: String, port: Int, ms: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), ms); true }
    } catch (_: Exception) {
        false
    }

    /** Eigene IPv4-Adressen mit Präfix (z. B. 192.168.178.23/24). */
    fun eigeneNetze(): List<Pair<String, Int>> {
        val r = mutableListOf<Pair<String, Int>>()
        for (ni in NetworkInterface.getNetworkInterfaces().toList()) {
            if (!ni.isUp || ni.isLoopback) continue
            for (a in ni.interfaceAddresses) {
                val ad = a.address
                if (ad is Inet4Address) r.add(ad.hostAddress!! to a.networkPrefixLength.toInt())
            }
        }
        return r
    }

    /** Scannt das WLAN (/24) nach Geräten. fortschritt: 0..1 */
    suspend fun wlan(fortschritt: (Float) -> Unit, gefunden: (Fund) -> Unit) = withContext(Dispatchers.IO) {
        val netze = eigeneNetze().filter { !it.first.startsWith("100.") && it.second in 16..30 }
        val ziele = mutableListOf<String>()
        for ((ip, _) in netze) {
            val basis = ip.substringBeforeLast('.')
            for (i in 1..254) {
                val z = "$basis.$i"
                if (z != ip && z !in ziele) ziele.add(z)
            }
        }
        if (ziele.isEmpty()) return@withContext
        val sperre = Semaphore(48)
        var fertig = 0
        coroutineScope {
            ziele.map { z ->
                async {
                    sperre.withPermit {
                        val offen = PORTS.keys.filter { offen(z, it, 350) }
                        if (offen.isNotEmpty()) {
                            val name = try {
                                val n = InetAddress.getByName(z).canonicalHostName
                                if (n == z) "" else n
                            } catch (_: Exception) { "" }
                            withContext(Dispatchers.Main) { gefunden(Fund(z, name, offen)) }
                        }
                        synchronized(this@Scan) { fertig++ }
                        withContext(Dispatchers.Main) { fortschritt(fertig.toFloat() / ziele.size) }
                    }
                }
            }.awaitAll()
        }
    }

    /** Ist ein gespeichertes Gerät erreichbar? (SSH oder ADB) */
    suspend fun erreichbar(g: Geraet): Pair<Boolean, Boolean> = withContext(Dispatchers.IO) {
        coroutineScope {
            val ssh = async { offen(g.host, g.sshPort, 2500) }
            val adb = async { offen(g.host, g.adbPort, 2500) }
            ssh.await() to adb.await()
        }
    }
}

@Composable
fun ScanSeite(hinzufuegen: (Geraet) -> Unit, zurueck: () -> Unit) {
    BackHandler { zurueck() }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var kabellos by remember { mutableStateOf<List<de.fgseraser.adbbox.adb.AdbSuche.Dienst>>(emptyList()) }
    var funde by remember { mutableStateOf<List<Scan.Fund>>(emptyList()) }
    var fortschritt by remember { mutableStateOf(0f) }
    var laeuft by remember { mutableStateOf(false) }
    var lauf by remember { mutableStateOf(0) }
    val netze = remember { runCatching { Scan.eigeneNetze() }.getOrDefault(emptyList()) }

    // Kabelloses Debugging (Android 11+) meldet sich per mDNS – mit dem echten, zufälligen Port
    LaunchedEffect(lauf) {
        kabellos = emptyList()
        de.fgseraser.adbbox.adb.AdbSuche.mdns(ctx, de.fgseraser.adbbox.adb.AdbSuche.TYP_VERBINDEN, 10_000) { d ->
            if (kabellos.none { it.host == d.host && it.port == d.port }) kabellos = kabellos + d
        }
    }
    LaunchedEffect(lauf) {
        laeuft = true
        funde = emptyList()
        fortschritt = 0f
        Scan.wlan({ fortschritt = it }) { f -> funde = (funde + f).sortedBy { it.ip.substringAfterLast('.').toIntOrNull() ?: 0 } }
        laeuft = false
        fortschritt = 1f
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Netzwerk scannen") },
            navigationIcon = { IconButton(onClick = zurueck) { Icon(Icons.Default.ArrowBack, "Zurück") } },
            actions = { IconButton(onClick = { if (!laeuft) lauf++ }) { Icon(Icons.Default.Refresh, "Neu scannen") } })
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("Dieses Handy: " + netze.joinToString(", ") { "${it.first}/${it.second}" }.ifEmpty { "kein Netz" },
                    color = Color.LightGray, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { fortschritt }, modifier = Modifier.fillMaxWidth())
                Text(if (laeuft) "Suche im WLAN … (${(fortschritt * 100).toInt()} %)" else "Fertig: ${funde.size} Gerät(e) gefunden",
                    modifier = Modifier.padding(top = 6.dp), fontWeight = FontWeight.Bold)
                Text("Gesucht wird nach offenem ADB (5555), SSH (8022/22) und nach Geräten mit „Kabelloses Debugging“ (Android 11+, beliebiger Port). Noch nicht gekoppelte Geräte erst unter „Gerät bearbeiten“ koppeln. Über Tailscale lässt sich nicht scannen – " +
                    "dort trägst du die 100.x-Adresse aus der Tailscale-App ein. In der Geräteliste siehst du, ob sie erreichbar ist.",
                    color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
            items(kabellos, key = { "k" + it.host + it.port }) { d ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(d.host, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text(d.name, color = Color.LightGray, fontSize = 13.sp)
                            Text("Kabelloses Debugging · ADB-Port ${d.port}", color = GRUEN, fontSize = 13.sp)
                        }
                        Button(onClick = { hinzufuegen(Geraet(name = d.name.substringBefore('-', d.name).take(20), host = d.host, adbPort = d.port)) }) { Text("Hinzufügen") }
                    }
                }
            }
            items(funde, key = { it.ip }) { f ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(f.ip, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            if (f.name.isNotEmpty()) Text(f.name, color = Color.LightGray, fontSize = 13.sp)
                            Text(f.ports.joinToString(" · ") { "${Scan.PORTS[it]} ($it)" }, color = GRUEN, fontSize = 13.sp)
                        }
                        Button(onClick = {
                            val ssh = f.ports.firstOrNull { it != 5555 } ?: 8022
                            hinzufuegen(Geraet(name = f.name.substringBefore('.'), host = f.ip, sshPort = ssh))
                        }) { Text("Hinzufügen") }
                    }
                }
            }
        }
    }
}
