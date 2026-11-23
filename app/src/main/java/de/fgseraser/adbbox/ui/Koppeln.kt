// ADB Box – Kabelloses Debugging (Android 11+): mit Kopplungscode oder QR-Code koppeln und den
// (zufälligen) ADB-Port automatisch finden. von FGSeRaser – https://github.com/FGSeRaser
@file:OptIn(ExperimentalMaterial3Api::class)

package de.fgseraser.adbbox.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import de.fgseraser.adbbox.GRUEN
import de.fgseraser.adbbox.adb.AdbSchluessel
import de.fgseraser.adbbox.adb.AdbSuche
import de.fgseraser.adbbox.adb.AdbTls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ROT = Color(0xFFFF8A8A)

/** Bereich im „Gerät bearbeiten“: Koppeln und Port finden. */
@Composable
fun KabellosBereich(host: String, hostSetzen: (String) -> Unit, portSetzen: (Int) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var codeDialog by remember { mutableStateOf(false) }
    var qrDialog by remember { mutableStateOf(false) }
    var meldung by remember { mutableStateOf("") }
    var suche by remember { mutableStateOf(-1f) }

    Text("Kabelloses Debugging (Android 11+)", fontWeight = FontWeight.Bold, color = GRUEN)
    Text("Ohne Kabel: Auf dem Gerät Entwickleroptionen → Kabelloses Debugging einschalten. Einmal koppeln, danach findet ADB Box den Port selbst – er ändert sich bei jedem Einschalten.",
        color = Color.LightGray, fontSize = 13.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { codeDialog = true }, modifier = Modifier.weight(1f)) { Text("Kopplungscode") }
        Button(onClick = { qrDialog = true }, modifier = Modifier.weight(1f)) { Text("QR-Code") }
    }
    OutlinedButton(onClick = {
        if (host.isBlank()) { meldung = "Erst die Adresse (IP) eintragen."; return@OutlinedButton }
        suche = 0f; meldung = "Suche ADB-Port auf $host …"
        scope.launch {
            val p = AdbSuche.verbindungsPort(ctx, host) { suche = it }
            suche = -1f
            if (p != null) { portSetzen(p); meldung = "ADB-Port gefunden: $p – jetzt speichern." }
            else meldung = "Kein ADB-Port gefunden. Ist „Kabelloses Debugging“ an und das Gerät im selben WLAN?"
        }
    }, enabled = suche < 0, modifier = Modifier.fillMaxWidth()) { Text("ADB-Port automatisch finden") }
    if (suche >= 0) LinearProgressIndicator(progress = { suche }, modifier = Modifier.fillMaxWidth())
    if (meldung.isNotBlank()) Text(meldung, fontSize = 13.sp, color = if (meldung.contains("Kein") || meldung.contains("falsch") || meldung.contains("Fehler")) ROT else Color.White)

    if (codeDialog) CodeKoppeln(host, { codeDialog = false }) { h, p ->
        codeDialog = false
        hostSetzen(h)
        if (p != null) { portSetzen(p); meldung = "Gekoppelt! ADB-Port $p eingetragen – jetzt speichern." }
        else meldung = "Gekoppelt! Den ADB-Port (steht unter „IP-Adresse und Port“) noch eintragen oder suchen lassen."
    }
    if (qrDialog) QrKoppeln({ qrDialog = false }) { h, p ->
        qrDialog = false
        hostSetzen(h)
        if (p != null) { portSetzen(p); meldung = "Gekoppelt! $h:$p eingetragen – jetzt speichern." }
        else meldung = "Gekoppelt mit $h! Den ADB-Port noch eintragen oder suchen lassen."
    }
}

@Composable
private fun CodeKoppeln(host0: String, schliessen: () -> Unit, fertig: (String, Int?) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf(host0) }
    var port by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var laeuft by remember { mutableStateOf(false) }
    var fehler by remember { mutableStateOf("") }
    var gefunden by remember { mutableStateOf<List<AdbSuche.Dienst>>(emptyList()) }

    // Kopplungsdienste im WLAN automatisch suchen (erscheinen, solange der Kopplungs-Dialog auf dem Gerät offen ist)
    LaunchedEffect(Unit) {
        while (true) {
            AdbSuche.mdns(ctx, AdbSuche.TYP_KOPPELN, 4000) { d ->
                if (gefunden.none { it.host == d.host && it.port == d.port }) gefunden = gefunden + d
                if (port.isBlank() && (host.isBlank() || host == d.host)) { host = d.host; port = d.port.toString() }
            }
        }
    }

    AlertDialog(onDismissRequest = { if (!laeuft) schliessen() }, title = { Text("Mit Kopplungscode koppeln") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Auf dem Gerät: Kabelloses Debugging → „Gerät mit Kopplungscode koppeln“. Dort stehen der 6-stellige Code und „IP-Adresse und Port“ (der Kopplungs-Port ist ein anderer als der normale ADB-Port).",
                    fontSize = 13.sp, color = Color.LightGray)
                if (gefunden.isNotEmpty()) {
                    Text("Im WLAN gefunden – antippen:", fontSize = 13.sp, color = GRUEN)
                    gefunden.forEach { d ->
                        Text("${d.host}:${d.port}", fontFamily = FontFamily.Monospace, modifier = Modifier.fillMaxWidth().clickable { host = d.host; port = d.port.toString() }.padding(4.dp))
                    }
                } else {
                    Text("Suche Kopplungs-Dialog im WLAN …", fontSize = 12.sp, color = Color.Gray)
                }
                OutlinedTextField(host, { host = it.trim() }, label = { Text("IP-Adresse") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("Kopplungs-Port") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, label = { Text("Kopplungscode (6 Ziffern)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                if (laeuft) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (fehler.isNotBlank()) Text(fehler, color = ROT, fontSize = 13.sp)
            }
        },
        confirmButton = {
            TextButton(enabled = !laeuft && host.isNotBlank() && port.isNotBlank() && code.length == 6, onClick = {
                laeuft = true; fehler = ""
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            val paar = AdbSchluessel.laden(ctx)
                            AdbTls.koppeln(host, port.toInt(), code, paar, AdbSchluessel.oeffentlich(paar))
                        }
                        val p = AdbSuche.verbindungsPort(ctx, host)
                        fertig(host, p)
                    } catch (e: Exception) {
                        fehler = e.message ?: e.toString()
                    }
                    laeuft = false
                }
            }) { Text("Koppeln") }
        },
        dismissButton = { TextButton(onClick = schliessen, enabled = !laeuft) { Text("Abbrechen") } })
}

@Composable
private fun QrKoppeln(schliessen: () -> Unit, fertig: (String, Int?) -> Unit) {
    val ctx = LocalContext.current
    val (name, pw) = remember { AdbSuche.qrDaten() }
    val bild = remember { qrBild(AdbSuche.qrText(name, pw), 720) }
    var status by remember { mutableStateOf("Warte, bis das Gerät den Code scannt …") }
    var fehler by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val ende = System.currentTimeMillis() + 180_000
        while (System.currentTimeMillis() < ende) {
            val d = AdbSuche.mdns(ctx, AdbSuche.TYP_KOPPELN, 2500).firstOrNull { it.name == name }
            if (d != null) {
                status = "Gerät ${d.host} gefunden – koppele …"
                try {
                    withContext(Dispatchers.IO) {
                        val paar = AdbSchluessel.laden(ctx)
                        AdbTls.koppeln(d.host, d.port, pw, paar, AdbSchluessel.oeffentlich(paar))
                    }
                    status = "Gekoppelt! Suche ADB-Port …"
                    val p = AdbSuche.verbindungsPort(ctx, d.host)
                    fertig(d.host, p)
                } catch (e: Exception) {
                    status = "Fehler: ${e.message}"
                    fehler = true
                }
                return@LaunchedEffect
            }
            delay(300)
        }
        status = "Zeit abgelaufen – bitte noch mal versuchen."
        fehler = true
    }

    AlertDialog(onDismissRequest = schliessen, title = { Text("Mit QR-Code koppeln") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Auf dem anderen Gerät: Kabelloses Debugging → „Gerät über QR-Code koppeln“ und diesen Code scannen. Beide Geräte müssen im selben WLAN sein.",
                    fontSize = 13.sp, color = Color.LightGray)
                Box(Modifier.background(Color.White).padding(10.dp)) {
                    Image(bild.asImageBitmap(), "QR-Code", Modifier.size(240.dp))
                }
                Text(status, color = if (fehler) ROT else Color.White, fontSize = 14.sp)
                if (!fehler) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = schliessen) { Text("Schließen") } })
}

private fun qrBild(text: String, groesse: Int): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, groesse, groesse, mapOf(EncodeHintType.MARGIN to 1))
    val pixel = IntArray(groesse * groesse)
    for (y in 0 until groesse) for (x in 0 until groesse) pixel[y * groesse + x] = if (m.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    return Bitmap.createBitmap(pixel, groesse, groesse, Bitmap.Config.ARGB_8888)
}
