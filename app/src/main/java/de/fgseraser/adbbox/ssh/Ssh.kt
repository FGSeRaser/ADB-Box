// ADB Box – SSH-Verbindung (z. B. zu Termux auf dem S8+): Befehle, Konsole und Dateien (SFTP).
// von FGSeRaser – https://github.com/FGSeRaser
package de.fgseraser.adbbox.ssh

import android.content.Context
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.Session
import de.fgseraser.adbbox.Geraet
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

object SshSchluessel {
    fun privat(ctx: Context): File = File(ctx.filesDir, "ssh_id_rsa")
    fun oeffentlichDatei(ctx: Context): File = File(ctx.filesDir, "ssh_id_rsa.pub")

    /** Erzeugt einmal einen eigenen SSH-Schlüssel für die App. */
    fun sicherstellen(ctx: Context) {
        if (privat(ctx).exists() && oeffentlichDatei(ctx).exists()) return
        val kp = KeyPair.genKeyPair(JSch(), KeyPair.RSA, 3072)
        kp.writePrivateKey(privat(ctx).absolutePath)
        kp.writePublicKey(oeffentlichDatei(ctx).absolutePath, "ADB-Box@FGSeRaser")
        kp.dispose()
    }

    fun oeffentlich(ctx: Context): String {
        sicherstellen(ctx)
        return oeffentlichDatei(ctx).readText().trim()
    }
}

class Ssh(private val ctx: Context, private val g: Geraet) {
    private var session: Session? = null

    val verbunden: Boolean get() = session?.isConnected == true

    fun verbinden() {
        if (verbunden) return
        SshSchluessel.sicherstellen(ctx)
        val jsch = JSch()
        jsch.addIdentity(SshSchluessel.privat(ctx).absolutePath)
        val s = jsch.getSession(g.sshBenutzer.ifBlank { "user" }, g.host, g.sshPort)
        if (g.sshPasswort.isNotEmpty()) s.setPassword(g.sshPasswort)
        s.setConfig("StrictHostKeyChecking", "no")
        s.setConfig("PreferredAuthentications", "publickey,password,keyboard-interactive")
        s.setServerAliveInterval(20_000)
        s.connect(15_000)
        session = s
    }

    fun trennen() {
        try { session?.disconnect() } catch (_: Exception) {}
        session = null
    }

    /** Führt einen Befehl aus und liefert Ausgabe + Fehler. */
    fun ausfuehren(befehl: String): String {
        verbinden()
        val c = session!!.openChannel("exec") as ChannelExec
        c.setCommand(befehl)
        val aus = ByteArrayOutputStream()
        val fehler = ByteArrayOutputStream()
        c.setErrStream(fehler)
        val ein = c.inputStream
        c.connect(15_000)
        ein.copyTo(aus)
        while (!c.isClosed) Thread.sleep(50)
        c.disconnect()
        val t = aus.toString() + fehler.toString()
        return t.ifBlank { "(fertig, keine Ausgabe – Code ${c.exitStatus})" }
    }

    /** Interaktive Konsole. */
    fun konsole(): Konsole {
        verbinden()
        val c = session!!.openChannel("shell") as ChannelShell
        c.setPtyType("dumb", 120, 40, 0, 0)
        val ein = c.inputStream
        val aus = c.outputStream
        c.connect(15_000)
        return Konsole(c, ein, aus)
    }

    fun sftp(): ChannelSftp {
        verbinden()
        val c = session!!.openChannel("sftp") as ChannelSftp
        c.connect(15_000)
        return c
    }

    /** Eigenen Schlüssel auf dem Gerät eintragen (danach geht es ohne Passwort). */
    fun schluesselEintragen(): String {
        val pub = SshSchluessel.oeffentlich(ctx).replace("'", "")
        return ausfuehren("mkdir -p ~/.ssh && chmod 700 ~/.ssh && touch ~/.ssh/authorized_keys && " +
            "(grep -qF '$pub' ~/.ssh/authorized_keys || echo '$pub' >> ~/.ssh/authorized_keys) && chmod 600 ~/.ssh/authorized_keys && echo Schlüssel eingetragen")
    }
}

class Konsole(private val kanal: ChannelShell, val eingang: InputStream, private val ausgang: OutputStream) {
    val offen: Boolean get() = !kanal.isClosed
    fun senden(text: String) {
        ausgang.write(text.toByteArray())
        ausgang.flush()
    }
    fun schliessen() {
        try { kanal.disconnect() } catch (_: Exception) {}
    }
}
