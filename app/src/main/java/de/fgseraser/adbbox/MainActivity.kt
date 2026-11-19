// ADB Box – Hauptfenster: Geräteliste, Gerät bearbeiten, Gerät öffnen, Info.
// von FGSeRaser – https://github.com/FGSeRaser
@file:OptIn(ExperimentalMaterial3Api::class)

package de.fgseraser.adbbox

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.fgseraser.adbbox.adb.AdbSchluessel
import de.fgseraser.adbbox.ssh.SshSchluessel
import de.fgseraser.adbbox.ui.GeraetAnsicht
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val GRUEN = Color(0xFF3DDC84)

sealed class Seite {
    object Liste : Seite()
    data class Bearbeiten(val g: Geraet) : Seite()
    data class Offen(val g: Geraet) : Seite()
    object Info : Seite()
    object Scan : Seite()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = GRUEN, secondary = Color(0xFF8FD4FF), background = Color(0xFF0F1A1E), surface = Color(0xFF14262C))) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    App()
                }
            }
        }
    }
}

fun hinweis(ctx: Context, text: String) {
    Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()
}

fun kopieren(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("ADB Box", text))
    hinweis(ctx, "Kopiert")
}

@Composable
fun App() {
    val ctx = LocalContext.current
    var seite by remember { mutableStateOf<Seite>(Seite.Liste) }
    var geraete by remember { mutableStateOf(Speicher.geraete(ctx)) }
    var update by remember { mutableStateOf<Aktualisierung.Neu?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        update = withContext(Dispatchers.IO) { runCatching { Aktualisierung.pruefen() }.getOrNull() }
    }

    when (val s = seite) {
        is Seite.Liste -> GeraeteListe(
            geraete = geraete,
            update = update,
            beiUpdate = { n ->
                hinweis(ctx, "Lade Version ${n.version} …")
                scope.launch {
                    val f = withContext(Dispatchers.IO) { runCatching { Aktualisierung.laden(ctx, n) } }
                    f.onSuccess { Hilfe.apkInstallieren(ctx, it) }.onFailure { hinweis(ctx, "Download fehlgeschlagen: ${it.message}") }
                }
            },
            oeffnen = { seite = Seite.Offen(it) },
            bearbeiten = { seite = Seite.Bearbeiten(it) },
            neu = { seite = Seite.Bearbeiten(Geraet(name = "", host = "")) },
            info = { seite = Seite.Info },
            scannen = { seite = Seite.Scan },
        )
        is Seite.Bearbeiten -> {
            BackHandler { seite = Seite.Liste }
            GeraetBearbeiten(s.g,
                speichern = { g -> Speicher.setzen(ctx, g); geraete = Speicher.geraete(ctx); seite = Seite.Liste },
                loeschen = { Speicher.loeschen(ctx, s.g.id); geraete = Speicher.geraete(ctx); seite = Seite.Liste },
                zurueck = { seite = Seite.Liste })
        }
        is Seite.Offen -> GeraetAnsicht(s.g,
            aendern = { g -> Speicher.setzen(ctx, g); geraete = Speicher.geraete(ctx) },
            zurueck = { geraete = Speicher.geraete(ctx); seite = Seite.Liste })
        is Seite.Scan -> ScanSeite(hinzufuegen = { seite = Seite.Bearbeiten(it) }, zurueck = { seite = Seite.Liste })
        is Seite.Info -> {
            BackHandler { seite = Seite.Liste }
            InfoSeite(zurueck = { seite = Seite.Liste })
        }
    }
}

@Composable
fun GeraeteListe(
    geraete: List<Geraet>, update: Aktualisierung.Neu?, beiUpdate: (Aktualisierung.Neu) -> Unit,
    oeffnen: (Geraet) -> Unit, bearbeiten: (Geraet) -> Unit, neu: () -> Unit, info: () -> Unit, scannen: () -> Unit,
) {
    // Erreichbarkeit der gespeicherten Geräte (SSH, ADB)
    var status by remember { mutableStateOf<Map<String, Pair<Boolean, Boolean>>>(emptyMap()) }
    var pruefRunde by remember { mutableStateOf(0) }
    LaunchedEffect(geraete, pruefRunde) {
        status = emptyMap()
        for (g in geraete) {
            launch { val r = Scan.erreichbar(g); status = status + (g.id to r) }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("ADB Box", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { pruefRunde++ }) { Icon(Icons.Default.Refresh, "Erreichbarkeit prüfen") }
                    IconButton(onClick = scannen) { Icon(Icons.Default.Search, "Netzwerk scannen") }
                    IconButton(onClick = info) { Icon(Icons.Default.Info, "Info") }
                })
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = neu, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Gerät") })
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (update != null) item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E5A3A)), modifier = Modifier.fillMaxWidth().clickable { beiUpdate(update) }) {
                    Text("Neue Version ${update.version} – tippen zum Installieren", Modifier.padding(16.dp), fontWeight = FontWeight.Bold)
                }
            }
            if (geraete.isEmpty()) item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Noch kein Gerät. Tippe unten auf „Gerät“ und trag z. B. dein S8+ mit seiner Tailscale-Adresse ein – oder such im WLAN:", color = Color.LightGray)
                    Button(onClick = scannen) { Text("Netzwerk scannen") }
                }
            }
            items(geraete, key = { it.id }) { g ->
                Card(Modifier.fillMaxWidth().clickable { oeffnen(g) }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        val st = status[g.id]
                        val farbe = when {
                            st == null -> Color.Gray
                            st.first || st.second -> GRUEN
                            else -> Color(0xFFFF6A6A)
                        }
                        Text("●", color = farbe, fontSize = 22.sp, modifier = Modifier.padding(end = 12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(g.name.ifBlank { g.host }, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text("${g.host} · SSH ${g.sshPort} · ADB ${g.adbPort}", color = Color.LightGray, fontSize = 13.sp)
                            if (st != null) Text(
                                if (!st.first && !st.second) "nicht erreichbar" else listOfNotNull(if (st.first) "SSH online" else null, if (st.second) "ADB online" else null).joinToString(" · "),
                                color = farbe, fontSize = 12.sp)
                        }
                        IconButton(onClick = { bearbeiten(g) }) { Icon(Icons.Default.Edit, "Bearbeiten") }
                    }
                }
            }
            item {
                Text("ADB Box ${BuildConfig.VERSION_NAME} · von FGSeRaser · github.com/FGSeRaser", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 24.dp))
            }
        }
    }
}

@Composable
fun GeraetBearbeiten(g: Geraet, speichern: (Geraet) -> Unit, loeschen: () -> Unit, zurueck: () -> Unit) {
    var name by remember { mutableStateOf(g.name) }
    var host by remember { mutableStateOf(g.host) }
    var sshPort by remember { mutableStateOf(g.sshPort.toString()) }
    var benutzer by remember { mutableStateOf(g.sshBenutzer) }
    var passwort by remember { mutableStateOf(g.sshPasswort) }
    var adbPort by remember { mutableStateOf(g.adbPort.toString()) }
    var loeschenFragen by remember { mutableStateOf(false) }
    Scaffold(topBar = {
        TopAppBar(title = { Text(if (g.host.isEmpty()) "Neues Gerät" else "Gerät bearbeiten") },
            navigationIcon = { IconButton(onClick = zurueck) { Icon(Icons.Default.ArrowBack, "Zurück") } })
    }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Name (z. B. S8+)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(host, { host = it.trim() }, label = { Text("Adresse (Tailscale-IP 100.x.x.x, Name oder WLAN-IP)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Text("SSH (Termux)", fontWeight = FontWeight.Bold, color = GRUEN)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(sshPort, { sshPort = it.filter(Char::isDigit) }, label = { Text("Port") }, modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(benutzer, { benutzer = it.trim() }, label = { Text("Benutzer") }, modifier = Modifier.weight(2f), singleLine = true)
            }
            OutlinedTextField(passwort, { passwort = it }, label = { Text("Passwort (leer lassen, wenn der Schlüssel eingetragen ist)") }, modifier = Modifier.fillMaxWidth(),
                singleLine = true, visualTransformation = PasswordVisualTransformation())
            Text("ADB (Bildschirm, Apps)", fontWeight = FontWeight.Bold, color = GRUEN)
            OutlinedTextField(adbPort, { adbPort = it.filter(Char::isDigit) }, label = { Text("ADB-Port") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Text("Termux: Port 8022, Benutzer egal. ADB: nach jedem Neustart einmal per Kabel „adb tcpip 5555“.", color = Color.LightGray, fontSize = 13.sp)
            Button(onClick = {
                speichern(g.copy(name = name.trim(), host = host, sshPort = sshPort.toIntOrNull() ?: 8022, sshBenutzer = benutzer,
                    sshPasswort = passwort, adbPort = adbPort.toIntOrNull() ?: 5555))
            }, enabled = host.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Speichern") }
            if (Speicher.geraete(LocalContext.current).any { it.id == g.id }) {
                OutlinedButton(onClick = { loeschenFragen = true }, modifier = Modifier.fillMaxWidth()) { Text("Gerät löschen", color = Color(0xFFFF8A8A)) }
            }
        }
    }
    if (loeschenFragen) AlertDialog(onDismissRequest = { loeschenFragen = false },
        title = { Text("Gerät löschen?") }, text = { Text("${g.name.ifBlank { g.host }} wird aus der Liste entfernt.") },
        confirmButton = { TextButton(onClick = loeschen) { Text("Löschen") } },
        dismissButton = { TextButton(onClick = { loeschenFragen = false }) { Text("Abbrechen") } })
}

@Composable
fun InfoSeite(zurueck: () -> Unit) {
    val ctx = LocalContext.current
    var sshPub by remember { mutableStateOf("…") }
    var adbFinger by remember { mutableStateOf("…") }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            sshPub = runCatching { SshSchluessel.oeffentlich(ctx) }.getOrElse { "Fehler: ${it.message}" }
            adbFinger = runCatching { AdbSchluessel.fingerabdruck(AdbSchluessel.laden(ctx)) }.getOrElse { "Fehler: ${it.message}" }
        }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Info & Einrichtung") }, navigationIcon = { IconButton(onClick = zurueck) { Icon(Icons.Default.ArrowBack, "Zurück") } })
    }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("ADB Box ${BuildConfig.VERSION_NAME}", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("von FGSeRaser", color = GRUEN)
            TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/FGSeRaser"))) }) { Text("github.com/FGSeRaser") }

            Text("So richtest du das S8+ ein", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("SSH (Termux):\n" +
                "1. In Termux: pkg install openssh\n" +
                "2. passwd  (Passwort festlegen)\n" +
                "3. sshd  (startet den SSH-Server auf Port 8022)\n" +
                "4. Hier ein Gerät mit der Tailscale-Adresse, Port 8022 und dem Passwort anlegen.\n" +
                "5. Im Gerät unter Befehle → „Schlüssel eintragen“ tippen – danach brauchst du kein Passwort mehr.\n\n" +
                "ADB (Bildschirm & Apps):\n" +
                "1. Auf dem S8+ Entwickleroptionen → USB-Debugging an.\n" +
                "2. Nach jedem Neustart einmal per Kabel: adb tcpip 5555  (am PC oder von einem anderen Handy per OTG)\n" +
                "3. Beim ersten Verbinden fragt das S8+ „USB-Debugging zulassen?“ – mit „Immer zulassen“ bestätigen.",
                color = Color.LightGray, fontSize = 14.sp)

            Text("SSH-Schlüssel dieser App", fontWeight = FontWeight.Bold)
            Text(sshPub, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color.LightGray)
            OutlinedButton(onClick = { kopieren(ctx, sshPub) }) { Text("Kopieren") }
            Text("ADB-Fingerabdruck", fontWeight = FontWeight.Bold)
            Text(adbFinger, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color.LightGray)
            Text("Bildschirm spiegeln nutzt den freien scrcpy-Server von Genymobile (Apache-2.0). Er wird beim ersten Mal von GitHub geladen.",
                color = Color.Gray, fontSize = 12.sp)
        }
    }
}
