// ADB Box – Kabelloses Debugging (Android 11+): TLS 1.3 mit eigenem Zertifikat und Koppeln per Code
// (wie „adb pair ip:port code“). Ablauf wie in AOSP adb (pairing_connection / pairing_auth):
// TLS-Handshake → exportiertes Schlüsselmaterial an den Code hängen → SPAKE2 → AES-128-GCM → PeerInfo tauschen.
// von FGSeRaser – https://github.com/FGSeRaser
package de.fgseraser.adbbox.adb;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509TrustManager;

public final class AdbTls {
    private AdbTls() {}

    private static X509Certificate zertifikat;
    private static SSLContext kontext;

    // ------------------------------------------------------------------ Zertifikat (selbst signiert, DER von Hand)
    public static synchronized X509Certificate zertifikat(KeyPair paar) throws Exception {
        if (zertifikat != null && zertifikat.getPublicKey().equals(paar.getPublic())) return zertifikat;
        byte[] sha256Rsa = der(0x30, cat(oid(new int[]{1, 2, 840, 113549, 1, 1, 11}), new byte[]{0x05, 0x00}));
        byte[] name = der(0x30, der(0x31, der(0x30, cat(oid(new int[]{2, 5, 4, 3}), der(0x0c, "ADB Box".getBytes(StandardCharsets.UTF_8))))));
        SimpleDateFormat f = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        byte[] von = der(0x17, f.format(new Date(System.currentTimeMillis() - 86_400_000L)).getBytes(StandardCharsets.US_ASCII));
        byte[] bis = der(0x17, "491231235959Z".getBytes(StandardCharsets.US_ASCII));
        byte[] serien = der(0x02, new BigInteger(63, new SecureRandom()).add(BigInteger.ONE).toByteArray());
        byte[] version = der(0xa0, der(0x02, new byte[]{2}));
        byte[] tbs = der(0x30, cat(version, serien, sha256Rsa, name, der(0x30, cat(von, bis)), name, paar.getPublic().getEncoded()));
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(paar.getPrivate());
        s.update(tbs);
        byte[] sig = s.sign();
        byte[] bits = new byte[sig.length + 1];
        System.arraycopy(sig, 0, bits, 1, sig.length);
        byte[] cert = der(0x30, cat(tbs, sha256Rsa, der(0x03, bits)));
        zertifikat = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new java.io.ByteArrayInputStream(cert));
        return zertifikat;
    }

    private static byte[] der(int tag, byte[] inhalt) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(tag);
        int n = inhalt.length;
        if (n < 128) o.write(n);
        else if (n < 256) { o.write(0x81); o.write(n); }
        else if (n < 65536) { o.write(0x82); o.write(n >> 8); o.write(n & 0xff); }
        else { o.write(0x83); o.write(n >> 16); o.write((n >> 8) & 0xff); o.write(n & 0xff); }
        o.write(inhalt, 0, n);
        return o.toByteArray();
    }

    private static byte[] oid(int[] teile) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(teile[0] * 40 + teile[1]);
        for (int i = 2; i < teile.length; i++) {
            int v = teile[i];
            byte[] tmp = new byte[5];
            int k = 0;
            do { tmp[k++] = (byte) (v & 0x7f); v >>>= 7; } while (v != 0);
            for (int j = k - 1; j >= 0; j--) o.write(tmp[j] | (j > 0 ? 0x80 : 0));
        }
        return der(0x06, o.toByteArray());
    }

    private static byte[] cat(byte[]... teile) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] t : teile) o.write(t, 0, t.length);
        return o.toByteArray();
    }

    // ------------------------------------------------------------------ TLS
    public static synchronized SSLContext kontext(KeyPair paar) throws Exception {
        if (kontext != null) return kontext;
        final X509Certificate cert = zertifikat(paar);
        final PrivateKey priv = paar.getPrivate();
        KeyManager km = new X509ExtendedKeyManager() {
            @Override public String[] getClientAliases(String t, Principal[] i) { return new String[]{"adbbox"}; }
            @Override public String chooseClientAlias(String[] t, Principal[] i, Socket s) { return "adbbox"; }
            @Override public String chooseEngineClientAlias(String[] t, Principal[] i, SSLEngine e) { return "adbbox"; }
            @Override public String[] getServerAliases(String t, Principal[] i) { return null; }
            @Override public String chooseServerAlias(String t, Principal[] i, Socket s) { return null; }
            @Override public X509Certificate[] getCertificateChain(String a) { return new X509Certificate[]{cert}; }
            @Override public PrivateKey getPrivateKey(String a) { return priv; }
        };
        // adbd benutzt ein selbst signiertes Zertifikat – vertraut wird über die Kopplung, nicht über die Kette
        TrustManager tm = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] c, String a) {}
            @Override public void checkServerTrusted(X509Certificate[] c, String a) {}
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        SSLContext c;
        try {
            c = SSLContext.getInstance("TLSv1.3");
        } catch (Exception e) {
            throw new IOException("Dieses Handy kann kein TLS 1.3 (erst ab Android 10). Kabelloses Debugging geht nur ab Android 10 auf diesem Gerät.");
        }
        c.init(new KeyManager[]{km}, new TrustManager[]{tm}, new SecureRandom());
        kontext = c;
        return c;
    }

    /** Schaltet eine bestehende Verbindung auf TLS um (nach A_STLS). */
    public static SSLSocket umschalten(Socket roh, String host, int port, KeyPair paar) throws Exception {
        SSLSocket s = (SSLSocket) kontext(paar).getSocketFactory().createSocket(roh, host, port, true);
        s.setEnabledProtocols(new String[]{"TLSv1.3"});
        s.startHandshake();
        return s;
    }

    /** RFC 5705 Keying Material Exporter (Android 10+: android.net.ssl.SSLSockets bzw. Conscrypt). */
    static byte[] exportieren(SSLSocket s, String label, int laenge) throws IOException {
        Throwable letzter = null;
        for (String klasse : new String[]{"android.net.ssl.SSLSockets", "com.android.org.conscrypt.Conscrypt", "org.conscrypt.Conscrypt"}) {
            try {
                Class<?> k = Class.forName(klasse);
                java.lang.reflect.Method m = k.getMethod("exportKeyingMaterial", SSLSocket.class, String.class, byte[].class, int.class);
                return (byte[]) m.invoke(null, s, label, null, laenge);
            } catch (Throwable t) {
                letzter = t;
            }
        }
        throw new IOException("TLS-Schlüsselexport nicht möglich: " + letzter);
    }

    // ------------------------------------------------------------------ Koppeln
    private static final int PEER_INFO_GROESSE = 8192;
    private static final byte TYP_SPAKE2 = 0;
    private static final byte TYP_PEER_INFO = 1;

    /**
     * Koppelt mit einem Gerät (Einstellungen → Entwickleroptionen → Kabelloses Debugging → „Gerät mit Kopplungscode koppeln“).
     * @param oeffentlich unser öffentlicher Schlüssel im adb-Format (Base64 + Name), wird auf dem Gerät gespeichert
     * @return Info der Gegenseite (meist die Geräte-GUID)
     */
    public static String koppeln(String host, int port, String code, KeyPair paar, byte[] oeffentlich) throws Exception {
        Socket roh = new Socket();
        roh.setTcpNoDelay(true);
        roh.connect(new InetSocketAddress(host, port), 10_000);
        roh.setSoTimeout(20_000);
        SSLSocket s = null;
        try {
            s = (SSLSocket) kontext(paar).getSocketFactory().createSocket(roh, host, port, true);
            s.setEnabledProtocols(new String[]{"TLSv1.3"});
            s.startHandshake();
            byte[] material = exportieren(s, "adb-label\u0000", 64);
            byte[] c = code.trim().getBytes(StandardCharsets.UTF_8);
            byte[] passwort = cat(c, material);

            DataOutputStream aus = new DataOutputStream(s.getOutputStream());
            DataInputStream ein = new DataInputStream(s.getInputStream());

            Spake2 spake = new Spake2(true, Spake2.NAME_CLIENT, Spake2.NAME_SERVER);
            schreiben(aus, TYP_SPAKE2, spake.nachricht(passwort, null));
            byte[] ihre = lesen(ein, TYP_SPAKE2);
            byte[] schluessel = hkdf(spake.verarbeiten(ihre), "adb pairing_auth aes-128-gcm key".getBytes(StandardCharsets.US_ASCII), 16);

            byte[] info = new byte[PEER_INFO_GROESSE];
            info[0] = 0; // ADB_RSA_PUB_KEY
            System.arraycopy(oeffentlich, 0, info, 1, Math.min(oeffentlich.length, PEER_INFO_GROESSE - 1));
            schreiben(aus, TYP_PEER_INFO, gcm(true, schluessel, 0, info));
            byte[] antwort;
            try {
                antwort = lesen(ein, TYP_PEER_INFO);
            } catch (EOFException e) {
                throw new IOException("Kopplungscode falsch oder abgelaufen – bitte neuen Code auf dem Gerät anzeigen lassen.");
            }
            byte[] klar;
            try {
                klar = gcm(false, schluessel, 0, antwort);
            } catch (AEADBadTagException e) {
                throw new IOException("Kopplungscode falsch.");
            }
            int ende = 1;
            while (ende < klar.length && klar[ende] != 0) ende++;
            return new String(klar, 1, ende - 1, StandardCharsets.UTF_8);
        } finally {
            try { if (s != null) s.close(); else roh.close(); } catch (Exception ignored) {}
        }
    }

    private static void schreiben(DataOutputStream aus, byte typ, byte[] daten) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(6 + daten.length).order(ByteOrder.BIG_ENDIAN);
        b.put((byte) 1).put(typ).putInt(daten.length).put(daten);
        aus.write(b.array());
        aus.flush();
    }

    private static byte[] lesen(DataInputStream ein, byte typ) throws IOException {
        byte[] kopf = new byte[6];
        ein.readFully(kopf);
        ByteBuffer b = ByteBuffer.wrap(kopf).order(ByteOrder.BIG_ENDIAN);
        byte version = b.get();
        byte t = b.get();
        int n = b.getInt();
        if (version != 1 || t != typ || n <= 0 || n > 2 * PEER_INFO_GROESSE + 64) throw new IOException("Unerwartete Antwort beim Koppeln");
        byte[] d = new byte[n];
        ein.readFully(d);
        return d;
    }

    static byte[] hkdf(byte[] ikm, byte[] info, int laenge) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] t = new byte[0];
        for (int i = 1; o.size() < laenge; i++) {
            mac.update(t);
            mac.update(info);
            mac.update((byte) i);
            t = mac.doFinal();
            o.write(t, 0, t.length);
        }
        byte[] r = new byte[laenge];
        System.arraycopy(o.toByteArray(), 0, r, 0, laenge);
        return r;
    }

    static byte[] gcm(boolean verschluesseln, byte[] schluessel, long folge, byte[] daten) throws Exception {
        byte[] iv = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(folge).array();
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(verschluesseln ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, new SecretKeySpec(schluessel, "AES"), new GCMParameterSpec(128, iv));
        return c.doFinal(daten);
    }
}
