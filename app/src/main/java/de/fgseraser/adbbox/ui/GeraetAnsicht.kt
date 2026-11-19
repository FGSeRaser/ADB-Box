// ADB Box – ein Gerät: Befehle, Konsole, Dateien (SFTP), Apps (ADB) und Bildschirm (scrcpy).
// von FGSeRaser – https://github.com/FGSeRaser
@file:OptIn(ExperimentalMaterial3Api::class)

package de.fgseraser.adbbox.ui

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.jcraft.jsch.ChannelSftp
import de.fgseraser.adbbox.Befehl
import de.fgseraser.adbbox.GRUEN
import de.fgseraser.adbbox.Geraet
import de.fgseraser.adbbox.Hilfe
import de.fgseraser.adbbox.adb.AdbSchluessel
import de.fgseraser.adbbox.adb.AdbSync
import de.fgseraser.adbbox.adb.AdbVerbindung
import de.fgseraser.adbbox.adb.Spiegel
import de.fgseraser.adbbox.hinweis
import de.fgseraser.adbbox.kopieren
import de.fgseraser.adbbox.ssh.Konsole
import de.fgseraser.adbbox.ssh.Ssh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Hält die Verbindungen zu einem Gerät, solange es offen ist. */
class Sitzung(val ctx: Context, val g: Geraet) {
    val ssh = Ssh(ctx, g)
    @Volatile var adb: AdbVerbindung? = null

    fun adb(beiNachfrage: () -> Unit = {}): AdbVerbindung {
        adb?.let { if (it.offen) return it }
        val v = AdbVerbindung.verbinden(g.host, g.adbPort, AdbSchluessel.laden(ctx), beiNachfrage)
        adb = v
        return v
    }

    fun schliessen() {
        Thread {
            ssh.trennen()
            try { adb?.close() } catch (_: Exception) {}
        }.start()
    }
}

fun adbFehlerText(e: Throwable): String {
    val m = e.message ?: e.toString()
    return if (m.contains("refused", true) || m.contains("ECONNREFUSED") || m.contains("timed out", true) || m.contains("failed to connect", true))
        "ADB nicht erreichbar. Auf dem Gerät nach dem Neustart einmal per Kabel „adb tcpip 5555“ ausführen und USB-Debugging anlassen.\n($m)"
    else m
}

@Composable
fun GeraetAnsicht(g: Geraet, aendern: (Geraet) -> Unit, zurueck: () -> Unit) {
    val ctx = LocalContext.current
    val sitzung = remember(g.id) { Sitzung(ctx.applicationContext, g) }
    var geraet by remember { mutableStateOf(g) }
    var tab by remember { mutableStateOf(0) }
    DisposableEffect(g.id) { onDispose { sitzung.schliessen() } }
    BackHandler { zurueck() }
    val tabs = listOf("Befehle", "Konsole", "Dateien", "Apps", "Bildschirm")
    Scaffold(topBar = {
        TopAppBar(title = { Text(geraet.name.ifBlank { geraet.host }) },
            navigationIcon = { IconButton(onClick = zurueck) { Icon(Icons.Default.ArrowBack, "Zurück") } })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
            }
            Box(Modifier.fillMaxSize()) {
                when (tab) {
                    0 -> BefehleTab(sitzung, geraet) { neu -> geraet = neu; aendern(neu) }
                    1 -> KonsoleTab(sitzung)
                    2 -> DateienTab(sitzung)
                    3 -> AppsTab(sitzung)
                    4 -> BildschirmTab(sitzung)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Befehle
@Composable
fun BefehleTab(s: Sitzung, g: Geraet, aendern: (Geraet) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var ergebnis by remember { mutableStateOf<Pair<String, String>?>(null) }
    var laeuft by remember { mutableStateOf(false) }
    var eingabe by remember { mutableStateOf("") }
    var perAdb by remember { mutableStateOf(false) }
    var neuDialog by remember { mutableStateOf(false) }

    fun ausfuehren(titel: String, befehl: String, adb: Boolean) {
        laeuft = true
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { if (adb) s.adb { }.shell(befehl) else s.ssh.ausfuehren(befehl) }
            }
            laeuft = false
            ergebnis = titel to r.getOrElse { if (adb) "Fehler: " + adbFehlerText(it) else "Fehler: ${it.message}" }
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(eingabe, { eingabe = it }, label = { Text("Befehl") }, modifier = Modifier.weight(1f), singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { if (eingabe.isNotBlank()) ausfuehren(eingabe, eingabe, perAdb) }))
                IconButton(onClick = { if (eingabe.isNotBlank()) ausfuehren(eingabe, eingabe, perAdb) }) { Icon(Icons.Default.PlayArrow, "Ausführen", tint = GRUEN) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = !perAdb, onClick = { perAdb = false }, label = { Text("über SSH (Termux)") })
                Spacer(Modifier.width(8.dp))
                FilterChip(selected = perAdb, onClick = { perAdb = true }, label = { Text("über ADB-Shell") })
            }
            if (laeuft) LinearProgressIndicator(Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text("Schnellbefehle", fontWeight = FontWeight.Bold, color = GRUEN)
        }
        items(g.befehle) { b ->
            Card(Modifier.fillMaxWidth().clickable { ausfuehren(b.name, b.text, b.perAdb) }) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(b.name, fontWeight = FontWeight.Bold)
                        Text((if (b.perAdb) "ADB: " else "SSH: ") + b.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color.LightGray, maxLines = 1)
                    }
                    IconButton(onClick = { aendern(g.copy(befehle = g.befehle - b)) }) { Icon(Icons.Default.Delete, "Löschen", tint = Color.Gray) }
                }
            }
        }
        item {
            OutlinedButton(onClick = { neuDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("+ Schnellbefehl") }
            Spacer(Modifier.height(16.dp))
            Text("SSH-Schlüssel", fontWeight = FontWeight.Bold, color = GRUEN)
            Text("Einmal mit Passwort verbinden und dann den App-Schlüssel eintragen – danach geht SSH ohne Passwort.", color = Color.LightGray, fontSize = 13.sp)
            OutlinedButton(onClick = {
                laeuft = true
                scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching { s.ssh.schluesselEintragen() } }
                    laeuft = false
                    ergebnis = "Schlüssel eintragen" to r.getOrElse { "Fehler: ${it.message}" }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("Schlüssel auf dem Gerät eintragen") }
        }
    }

    ergebnis?.let { (titel, text) ->
        AlertDialog(onDismissRequest = { ergebnis = null }, title = { Text(titel, maxLines = 1) },
            text = {
                Box(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                    Box(Modifier.horizontalScroll(rememberScrollState())) {
                        Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { ergebnis = null }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { kopieren(ctx, text) }) { Text("Kopieren") } })
    }
    if (neuDialog) {
        var name by remember { mutableStateOf("") }
        var text by remember { mutableStateOf("") }
        var adb by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { neuDialog = false }, title = { Text("Neuer Schnellbefehl") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name, z. B. Bot neu starten") }, singleLine = true)
                    OutlinedTextField(text, { text = it }, label = { Text("Befehl") }, textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(adb, { adb = it })
                        Spacer(Modifier.width(8.dp))
                        Text(if (adb) "über ADB-Shell" else "über SSH (Termux)")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (text.isNotBlank()) aendern(g.copy(befehle = g.befehle + Befehl(name.ifBlank { text }, text, adb)))
                    neuDialog = false
                }) { Text("Speichern") }
            },
            dismissButton = { TextButton(onClick = { neuDialog = false }) { Text("Abbrechen") } })
    }
}

// ------------------------------------------------------------------ Konsole
private val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B[()][A-Za-z0-9]|\u001B[=>]|\u0007")

@Composable
fun KonsoleTab(s: Sitzung) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var konsole by remember { mutableStateOf<Konsole?>(null) }
    var eingabe by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    val liste = rememberLazyListState()
    DisposableEffect(Unit) { onDispose { konsole?.schliessen() } }

    fun verbinden() {
        status = "Verbinde …"
        scope.launch {
            val k = withContext(Dispatchers.IO) { runCatching { s.ssh.konsole() } }
            k.onFailure { status = "Fehler: ${it.message}" }
            k.onSuccess { kon ->
                konsole = kon
                status = ""
                launch(Dispatchers.IO) {
                    val b = ByteArray(8192)
                    try {
                        while (true) {
                            val n = kon.eingang.read(b)
                            if (n < 0) break
                            val neu = String(b, 0, n).replace(ANSI, "").replace("\r", "")
                            withContext(Dispatchers.Main) {
                                var t = text + neu
                                if (t.length > 60_000) t = t.takeLast(50_000)
                                text = t
                            }
                        }
                    } catch (_: Exception) {}
                    withContext(Dispatchers.Main) { status = "Verbindung beendet"; konsole = null }
                }
            }
        }
    }

    LaunchedEffect(text) { liste.scrollToItem(0, Int.MAX_VALUE / 2) }

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        if (konsole == null) {
            Button(onClick = { verbinden() }, modifier = Modifier.fillMaxWidth()) { Text("Konsole öffnen (SSH)") }
            if (status.isNotEmpty()) Text(status, color = Color(0xFFFFB0A0))
        }
        LazyColumn(state = liste, modifier = Modifier.weight(1f).fillMaxWidth().background(Color(0xFF05090B)).padding(6.dp)) {
            item {
                Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color(0xFFCFF5DA))
            }
        }
        if (konsole != null) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((n, z) in listOf("Strg+C" to "\u0003", "Tab" to "\t", "↑" to "\u001B[A", "↓" to "\u001B[B", "Esc" to "\u001B", "Strg+D" to "\u0004", "Leeren" to "")) {
                    AssistChip(onClick = {
                        if (n == "Leeren") text = "" else scope.launch(Dispatchers.IO) { runCatching { konsole?.senden(z) } }
                    }, label = { Text(n) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(eingabe, { eingabe = it }, modifier = Modifier.weight(1f), singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        val t = eingabe; eingabe = ""
                        scope.launch(Dispatchers.IO) { runCatching { konsole?.senden(t + "\n") } }
                    }))
                IconButton(onClick = {
                    val t = eingabe; eingabe = ""
                    scope.launch(Dispatchers.IO) { runCatching { konsole?.senden(t + "\n") } }
                }) { Icon(Icons.Default.Send, "Senden", tint = GRUEN) }
            }
        }
    }
}

// ------------------------------------------------------------------ Dateien (SFTP)
data class Eintrag(val name: String, val ordner: Boolean, val groesse: Long)

fun dateiName(ctx: Context, uri: Uri): String {
    var n = "datei"
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) n = c.getString(0) ?: n
    }
    return n
}

@Composable
fun DateienTab(s: Sitzung) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pfad by remember { mutableStateOf("") }
    var eintraege by remember { mutableStateOf<List<Eintrag>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var laeuft by remember { mutableStateOf(false) }
    var gewaehlt by remember { mutableStateOf<Eintrag?>(null) }

    fun <T> sftp(arbeit: (ChannelSftp) -> T, fertig: (T) -> Unit = {}) {
        laeuft = true
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val c = s.ssh.sftp()
                    try { arbeit(c) } finally { c.disconnect() }
                }
            }
            laeuft = false
            r.onSuccess(fertig).onFailure { status = "Fehler: ${it.message}" }
        }
    }

    fun laden(neu: String) {
        sftp({ c ->
            val p = if (neu.isEmpty()) c.pwd() else c.realpath(neu)
            @Suppress("UNCHECKED_CAST")
            val l = (c.ls(p) as java.util.Vector<ChannelSftp.LsEntry>)
                .filter { it.filename != "." && it.filename != ".." }
                .map { Eintrag(it.filename, it.attrs.isDir, it.attrs.size) }
                .sortedWith(compareBy({ !it.ordner }, { it.name.lowercase() }))
            p to l
        }) { (p, l) -> pfad = p; eintraege = l; status = "" }
    }

    LaunchedEffect(Unit) { laden("") }

    val hochladen = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val ziel = pfad
        sftp({ c ->
            for (u in uris) {
                val name = dateiName(ctx, u)
                ctx.contentResolver.openInputStream(u)!!.use { c.put(it, "$ziel/$name") }
            }
            uris.size
        }) { n -> hinweis(ctx, "$n Datei(en) hochgeladen"); laden(ziel) }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (pfad.isNotEmpty() && pfad != "/") laden(pfad.substringBeforeLast('/').ifEmpty { "/" }) }) { Icon(Icons.Default.KeyboardArrowUp, "Hoch") }
            Text(pfad, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 2)
            IconButton(onClick = { laden(pfad) }) { Icon(Icons.Default.Refresh, "Neu laden") }
        }
        if (laeuft) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (status.isNotEmpty()) Text(status, color = Color(0xFFFFB0A0), modifier = Modifier.padding(8.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(eintraege, key = { it.name }) { e ->
                Row(Modifier.fillMaxWidth().clickable {
                    if (e.ordner) laden("$pfad/${e.name}") else gewaehlt = e
                }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (e.ordner) "📁" else if (e.name.endsWith(".apk", true)) "📦" else "📄", fontSize = 22.sp)
                    Spacer(Modifier.width(12.dp))
                    Text(e.name, Modifier.weight(1f), maxLines = 1)
                    if (!e.ordner) Text(Hilfe.groesse(e.groesse), color = Color.Gray, fontSize = 12.sp)
                }
            }
        }
        Button(onClick = { hochladen.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth().padding(8.dp)) { Text("Vom Handy hochladen") }
    }

    gewaehlt?.let { e ->
        val voll = "$pfad/${e.name}"
        val apk = e.name.endsWith(".apk", true)
        AlertDialog(onDismissRequest = { gewaehlt = null }, title = { Text(e.name, maxLines = 2) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(Hilfe.groesse(e.groesse), color = Color.Gray)
                    Button(onClick = {
                        gewaehlt = null
                        sftp({ c -> Hilfe.inDownloads(ctx, e.name) { out -> c.get(voll, out) } }) { ort -> hinweis(ctx, "Gespeichert: $ort") }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Auf dieses Handy laden") }
                    if (apk) {
                        Button(onClick = {
                            gewaehlt = null
                            sftp({ c -> Hilfe.inCache(ctx, "install.apk") { out -> c.get(voll, out) } }) { f -> Hilfe.apkInstallieren(ctx, f) }
                        }, modifier = Modifier.fillMaxWidth()) { Text("Auf diesem Handy installieren") }
                        Button(onClick = {
                            gewaehlt = null
                            laeuft = true
                            scope.launch {
                                val r = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val c = s.ssh.sftp()
                                        val f = try { Hilfe.inCache(ctx, "remote.apk") { out -> c.get(voll, out) } } finally { c.disconnect() }
                                        adbInstallieren(s.adb { }, f)
                                    }
                                }
                                laeuft = false
                                hinweis(ctx, r.getOrElse { "Fehler: " + adbFehlerText(it) })
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("Per ADB auf dem Gerät installieren") }
                    }
                    OutlinedButton(onClick = {
                        gewaehlt = null
                        sftp({ c -> c.rm(voll) }) { laden(pfad) }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Löschen", color = Color(0xFFFF8A8A)) }
                }
            },
            confirmButton = { TextButton(onClick = { gewaehlt = null }) { Text("Schließen") } })
    }
}

fun adbInstallieren(v: AdbVerbindung, apk: File): String {
    val ziel = "/data/local/tmp/adbbox-install.apk"
    apk.inputStream().use { AdbSync.hochladen(v, it, ziel) }
    val r = v.shell("pm install -r -t $ziel; rm -f $ziel").trim()
    return if (r.contains("Success")) "Installiert!" else "Installation: $r"
}

// ------------------------------------------------------------------ Apps (ADB)
data class App(val paket: String, val aus: Boolean)

@Composable
fun AppsTab(s: Sitzung) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var verbunden by remember { mutableStateOf(s.adb?.offen == true) }
    var status by remember { mutableStateOf("") }
    var laeuft by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf("") }
    var system by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf<List<App>>(emptyList()) }
    var suche by remember { mutableStateOf("") }
    var gewaehlt by remember { mutableStateOf<App?>(null) }

    fun arbeit(text: String, a: (AdbVerbindung) -> String, danach: () -> Unit = {}) {
        laeuft = true
        status = text
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { a(s.adb { status = "Bitte auf dem Gerät „USB-Debugging zulassen“ bestätigen (Immer zulassen)." }) }
            }
            laeuft = false
            verbunden = s.adb?.offen == true
            status = r.getOrElse { "Fehler: " + adbFehlerText(it) }
            if (r.isSuccess) danach()
        }
    }

    fun liste() {
        laeuft = true
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val v = s.adb { }
                    val aus = v.shell("pm list packages -d").lines().map { it.removePrefix("package:").trim() }.toSet()
                    val l = v.shell("pm list packages ${if (system) "-s" else "-3"}").lines()
                        .map { it.removePrefix("package:").trim() }.filter { it.isNotEmpty() }.sorted()
                    val i = v.shell("getprop ro.product.model; getprop ro.build.version.release").lines().filter { it.isNotBlank() }
                    Triple(l.map { App(it, it in aus) }, i.joinToString(" · Android "), "")
                }
            }
            laeuft = false
            verbunden = s.adb?.offen == true
            r.onSuccess { apps = it.first; info = it.second; status = "" }.onFailure { status = "Fehler: " + adbFehlerText(it) }
        }
    }

    val installieren = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        arbeit("Installiere …", { v ->
            val f = Hilfe.inCache(ctx, "lokal.apk") { out -> ctx.contentResolver.openInputStream(uri)!!.use { it.copyTo(out) } }
            adbInstallieren(v, f)
        }) { liste() }
    }

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        if (!verbunden) {
            Button(onClick = { liste() }, modifier = Modifier.fillMaxWidth()) { Text("Per ADB verbinden") }
            Text("Beim ersten Mal fragt das Gerät „USB-Debugging zulassen?“ – mit „Immer zulassen“ bestätigen.", color = Color.LightGray, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
        } else {
            if (info.isNotEmpty()) Text(info, fontWeight = FontWeight.Bold, color = GRUEN)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = !system, onClick = { system = false; liste() }, label = { Text("Meine Apps") })
                FilterChip(selected = system, onClick = { system = true; liste() }, label = { Text("System") })
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { installieren.launch(arrayOf("application/vnd.android.package-archive", "*/*")) }) { Text("APK installieren") }
            }
            OutlinedTextField(suche, { suche = it }, label = { Text("Suchen") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        if (laeuft) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (status.isNotEmpty()) Text(status, color = if (status.startsWith("Fehler")) Color(0xFFFFB0A0) else Color.LightGray, modifier = Modifier.padding(6.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(apps.filter { suche.isBlank() || it.paket.contains(suche, true) }, key = { it.paket }) { a ->
                Row(Modifier.fillMaxWidth().clickable { gewaehlt = a }.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(a.paket, Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = if (a.aus) Color.Gray else Color.White)
                    if (a.aus) Text("aus", color = Color(0xFFFFB0A0), fontSize = 12.sp)
                }
            }
        }
    }

    gewaehlt?.let { a ->
        AlertDialog(onDismissRequest = { gewaehlt = null }, title = { Text(a.paket, fontSize = 16.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { gewaehlt = null; arbeit("Starte …", { v -> v.shell("monkey -p ${a.paket} -c android.intent.category.LAUNCHER 1"); "Gestartet" }) },
                        modifier = Modifier.fillMaxWidth()) { Text("Öffnen") }
                    Button(onClick = {
                        gewaehlt = null
                        arbeit("Sichere APK …", { v ->
                            val pfad = v.shell("pm path ${a.paket}").lines().firstOrNull { it.startsWith("package:") }?.removePrefix("package:")?.trim()
                                ?: throw IllegalStateException("APK nicht gefunden")
                            val ort = Hilfe.inDownloads(ctx, "${a.paket}.apk") { out -> AdbSync.herunterladen(v, pfad, out) }
                            "Gespeichert: $ort"
                        })
                    }, modifier = Modifier.fillMaxWidth()) { Text("APK auf dieses Handy sichern") }
                    Button(onClick = {
                        gewaehlt = null
                        arbeit(if (a.aus) "Aktiviere …" else "Deaktiviere …", { v ->
                            v.shell(if (a.aus) "pm enable ${a.paket}" else "pm disable-user --user 0 ${a.paket}").trim()
                        }) { liste() }
                    }, modifier = Modifier.fillMaxWidth()) { Text(if (a.aus) "Aktivieren" else "Deaktivieren") }
                    OutlinedButton(onClick = {
                        gewaehlt = null
                        arbeit("Deinstalliere …", { v ->
                            v.shell(if (system) "pm uninstall -k --user 0 ${a.paket}" else "pm uninstall ${a.paket}").trim()
                        }) { liste() }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Deinstallieren", color = Color(0xFFFF8A8A)) }
                    if (system) Text("System-Apps werden nur für den Benutzer entfernt. Zurückholen: Befehl „cmd package install-existing ${a.paket}“.",
                        fontSize = 12.sp, color = Color.Gray)
                }
            },
            confirmButton = { TextButton(onClick = { gewaehlt = null }) { Text("Schließen") } })
    }
}

// ------------------------------------------------------------------ Bildschirm (scrcpy)
@SuppressLint("ClickableViewAccessibility")
@Composable
fun BildschirmTab(s: Sitzung) {
    val ctx = LocalContext.current
    var aktiv by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var stufe by remember { mutableStateOf(1) }
    var groesse by remember { mutableStateOf(0 to 0) }
    var spiegel by remember { mutableStateOf<Spiegel?>(null) }
    DisposableEffect(Unit) { onDispose { spiegel?.stoppen() } }
    val stufen = listOf(Triple("Niedrig", 720, 2_000_000), Triple("Mittel", 1080, 4_000_000), Triple("Hoch", 1600, 8_000_000))

    Column(Modifier.fillMaxSize()) {
        if (!aktiv) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Bildschirm des Geräts anzeigen und steuern (wie scrcpy).", color = Color.LightGray)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    stufen.forEachIndexed { i, t -> FilterChip(selected = stufe == i, onClick = { stufe = i }, label = { Text(t.first) }) }
                }
                Text("Unterwegs über Tailscale lieber „Niedrig“ wählen.", color = Color.Gray, fontSize = 12.sp)
                Button(onClick = { status = "Verbinde …"; aktiv = true }, modifier = Modifier.fillMaxWidth()) { Text("Spiegeln starten") }
                if (status.isNotEmpty()) Text(status, color = Color(0xFFFFB0A0))
            }
        } else {
            Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
                val (w, h) = groesse
                val mod = if (w > 0 && h > 0) Modifier.aspectRatio(w.toFloat() / h) else Modifier.fillMaxSize()
                AndroidView(modifier = mod, factory = { c ->
                    SurfaceView(c).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                if (spiegel != null) return
                                val t = stufen[stufe]
                                Thread {
                                    try {
                                        val v = s.adb { status = "Bitte auf dem Gerät „USB-Debugging zulassen“ bestätigen." }
                                        val sp = Spiegel(v, t.second, t.third,
                                            beiGroesse = { bw, bh -> post { groesse = bw to bh } },
                                            beiFehler = { m -> post { status = "Fehler: $m" } })
                                        post { spiegel = sp; status = "" }
                                        sp.starten(ctx.applicationContext, holder.surface)
                                    } catch (e: Exception) {
                                        post { status = "Fehler: " + adbFehlerText(e) }
                                    }
                                    post { spiegel = null; aktiv = false }
                                }.start()
                            }
                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                            override fun surfaceDestroyed(holder: SurfaceHolder) { spiegel?.stoppen() }
                        })
                        setOnTouchListener { view, e ->
                            val sp = spiegel ?: return@setOnTouchListener true
                            if (sp.breite == 0 || view.width == 0) return@setOnTouchListener true
                            val fx = sp.breite.toFloat() / view.width
                            val fy = sp.hoehe.toFloat() / view.height
                            fun senden(aktion: Int, i: Int) {
                                sp.beruehrung(aktion, e.getPointerId(i).toLong(), (e.getX(i) * fx).toInt(), (e.getY(i) * fy).toInt())
                            }
                            when (e.actionMasked) {
                                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> senden(0, e.actionIndex)
                                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> senden(1, e.actionIndex)
                                MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) senden(2, i)
                            }
                            true
                        }
                    }
                })
                if (status.isNotEmpty()) Text(status, color = Color.White, modifier = Modifier.align(Alignment.TopCenter).padding(12.dp))
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((n, k) in listOf("◀ Zurück" to Spiegel.TASTE_ZURUECK, "● Home" to Spiegel.TASTE_HOME, "▢ Apps" to Spiegel.TASTE_APPS,
                    "⏻" to Spiegel.TASTE_POWER, "🔉" to Spiegel.TASTE_LEISER, "🔊" to Spiegel.TASTE_LAUTER)) {
                    FilledTonalButton(onClick = { spiegel?.taste(k) }) { Text(n) }
                }
                OutlinedButton(onClick = { spiegel?.stoppen() }) { Text("Beenden") }
            }
        }
    }
}
