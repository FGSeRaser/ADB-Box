// ADB Box – SPAKE2 über Ed25519, bitgenau wie BoringSSL (spake25519.cc), das adbd beim
// „Gerät mit Kopplungscode koppeln“ benutzt. Kleine, langsame aber übersichtliche BigInteger-Version
// (es werden nur eine Handvoll Punktmultiplikationen gebraucht).
// von FGSeRaser – https://github.com/FGSeRaser
package de.fgseraser.adbbox.adb;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

public final class Spake2 {
    private static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    private static final BigInteger D = BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P);
    private static final BigInteger D2 = D.add(D).mod(P);
    private static final BigInteger L = BigInteger.ONE.shiftLeft(252).add(new BigInteger("27742317777372353535851937790883648493"));
    private static final BigInteger SQRT_M1 = BigInteger.valueOf(2).modPow(P.subtract(BigInteger.ONE).shiftRight(2), P);
    private static final BigInteger ZWEI_256 = BigInteger.ONE.shiftLeft(256);

    // Punkte M und N aus BoringSSL (spake25519.cc)
    private static final Punkt M = Punkt.lesen(hex("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e"));
    private static final Punkt N = Punkt.lesen(hex("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778"));
    private static final Punkt B = Punkt.lesen(hex("5866666666666666666666666666666666666666666666666666666666666666"));

    public static final byte[] NAME_CLIENT = "adb pair client\u0000".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    public static final byte[] NAME_SERVER = "adb pair server\u0000".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    private final boolean alice;
    private final byte[] meinName;
    private final byte[] ihrName;
    private BigInteger privat;
    private BigInteger passwortSkalar;
    private byte[] passwortHash;
    private byte[] meineNachricht;

    /** alice = Client (adb), bob = Server (adbd). */
    public Spake2(boolean alice, byte[] meinName, byte[] ihrName) {
        this.alice = alice;
        this.meinName = meinName.clone();
        this.ihrName = ihrName.clone();
    }

    /** Erzeugt die eigene 32-Byte-Nachricht. zufall darf null sein (dann SecureRandom). */
    public byte[] nachricht(byte[] passwort, byte[] zufall64) throws Exception {
        byte[] r = zufall64 != null ? zufall64.clone() : new byte[64];
        if (zufall64 == null) new SecureRandom().nextBytes(r);
        // reduzieren, dann mit dem Kofaktor 8 multiplizieren
        privat = leLesen(r).mod(L).shiftLeft(3).mod(ZWEI_256);
        Punkt p = B.mal(privat);
        passwortHash = MessageDigest.getInstance("SHA-512").digest(passwort);
        BigInteger s = leLesen(passwortHash).mod(L);
        // „password scalar hack“: Vielfache von L addieren, bis die unteren 3 Bits 0 sind
        BigInteger ordnung = L;
        for (int bit = 1; bit <= 4; bit <<= 1) {
            if (s.testBit(Integer.numberOfTrailingZeros(bit))) s = s.add(ordnung).mod(ZWEI_256);
            ordnung = ordnung.shiftLeft(1);
        }
        passwortSkalar = s;
        Punkt maske = (alice ? M : N).mal(s);
        meineNachricht = p.plus(maske).schreiben();
        return meineNachricht.clone();
    }

    /** Verarbeitet die Nachricht der Gegenseite und liefert den 64-Byte-Schlüssel. */
    public byte[] verarbeiten(byte[] ihre) throws Exception {
        if (ihre.length != 32) throw new IllegalArgumentException("SPAKE2-Nachricht muss 32 Byte haben");
        Punkt qStern = Punkt.lesen(ihre);
        if (qStern == null) throw new IllegalArgumentException("Ungültiger Punkt");
        Punkt q = qStern.plus((alice ? N : M).mal(passwortSkalar).negiert());
        byte[] dh = q.mal(privat).schreiben();
        MessageDigest sha = MessageDigest.getInstance("SHA-512");
        if (alice) {
            mitLaenge(sha, meinName); mitLaenge(sha, ihrName); mitLaenge(sha, meineNachricht); mitLaenge(sha, ihre);
        } else {
            mitLaenge(sha, ihrName); mitLaenge(sha, meinName); mitLaenge(sha, ihre); mitLaenge(sha, meineNachricht);
        }
        mitLaenge(sha, dh);
        mitLaenge(sha, passwortHash);
        return sha.digest();
    }

    private static void mitLaenge(MessageDigest sha, byte[] d) {
        long l = d.length;
        byte[] le = new byte[8];
        for (int i = 0; i < 8; i++) { le[i] = (byte) (l & 0xff); l >>>= 8; }
        sha.update(le);
        sha.update(d);
    }

    private static BigInteger leLesen(byte[] b) {
        byte[] r = new byte[b.length + 1];
        for (int i = 0; i < b.length; i++) r[b.length - i] = b[i];
        return new BigInteger(r);
    }

    static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    /** Punkt auf Edwards25519 in erweiterten Koordinaten (X:Y:Z:T). */
    static final class Punkt {
        final BigInteger x, y, z, t;

        Punkt(BigInteger x, BigInteger y, BigInteger z, BigInteger t) { this.x = x; this.y = y; this.z = z; this.t = t; }

        static final Punkt NULL = new Punkt(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO);

        Punkt plus(Punkt o) {
            BigInteger a = y.subtract(x).multiply(o.y.subtract(o.x)).mod(P);
            BigInteger b = y.add(x).multiply(o.y.add(o.x)).mod(P);
            BigInteger c = t.multiply(D2).multiply(o.t).mod(P);
            BigInteger d = z.multiply(BigInteger.valueOf(2)).multiply(o.z).mod(P);
            BigInteger e = b.subtract(a), f = d.subtract(c), g = d.add(c), h = b.add(a);
            return new Punkt(e.multiply(f).mod(P), g.multiply(h).mod(P), f.multiply(g).mod(P), e.multiply(h).mod(P));
        }

        Punkt negiert() { return new Punkt(P.subtract(x).mod(P), y, z, P.subtract(t).mod(P)); }

        Punkt mal(BigInteger k) {
            Punkt r = NULL;
            Punkt a = this;
            for (int i = 0; i < k.bitLength(); i++) {
                if (k.testBit(i)) r = r.plus(a);
                a = a.plus(a);
            }
            return r;
        }

        byte[] schreiben() {
            BigInteger zi = z.modInverse(P);
            BigInteger ax = x.multiply(zi).mod(P);
            BigInteger ay = y.multiply(zi).mod(P);
            byte[] out = new byte[32];
            byte[] be = ay.toByteArray();
            for (int i = 0; i < be.length && i < 32; i++) out[i] = be[be.length - 1 - i];
            if (ax.testBit(0)) out[31] |= (byte) 0x80;
            return out;
        }

        static Punkt lesen(byte[] s) {
            byte[] b = Arrays.copyOf(s, 32);
            boolean vorzeichen = (b[31] & 0x80) != 0;
            b[31] &= 0x7f;
            BigInteger y = leLesen(b);
            if (y.compareTo(P) >= 0) return null;
            BigInteger yy = y.multiply(y).mod(P);
            BigInteger u = yy.subtract(BigInteger.ONE).mod(P);
            BigInteger v = D.multiply(yy).add(BigInteger.ONE).mod(P);
            BigInteger xx = u.multiply(v.modInverse(P)).mod(P);
            BigInteger x = xx.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P);
            if (!x.multiply(x).mod(P).equals(xx)) x = x.multiply(SQRT_M1).mod(P);
            if (!x.multiply(x).mod(P).equals(xx)) return null;
            if (x.signum() == 0 && vorzeichen) return null;
            if (x.testBit(0) != vorzeichen) x = P.subtract(x);
            return new Punkt(x, y, BigInteger.ONE, x.multiply(y).mod(P));
        }
    }
}
