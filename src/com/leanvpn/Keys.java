package com.leanvpn;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * One random seed gives both keys: the Ed25519 public key registered with Proton, and the
 * X25519 WireGuard private key that matches it (the same clamped scalar, as libsodium does).
 */
final class Keys {
    private Keys() {}

    private static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    private static final BigInteger D =
        BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P);
    private static final BigInteger GX =
        new BigInteger("15112221349535400772501151409588531511454012693041857206046113283949847762202");
    private static final BigInteger GY =
        new BigInteger("46316835694926478169428394003475163141307993866256225615783033603165251855960");

    static byte[] newSeed() {
        byte[] seed = new byte[32];
        new SecureRandom().nextBytes(seed);
        return seed;
    }

    static String wireGuardPrivateKey(byte[] seed) {
        return Base64.getEncoder().encodeToString(scalar(seed));
    }

    /** Ed25519 public key as PKIX PEM, the format the certificate endpoint expects. */
    static String publicPem(byte[] seed) {
        byte[] prefix = {0x30, 0x2A, 0x30, 0x05, 0x06, 0x03, 0x2B, 0x65, 0x70, 0x03, 0x21, 0x00};
        byte[] der = Arrays.copyOf(prefix, prefix.length + 32);
        System.arraycopy(ed25519Public(seed), 0, der, prefix.length, 32);
        return "-----BEGIN PUBLIC KEY-----\n" + Base64.getEncoder().encodeToString(der) + "\n-----END PUBLIC KEY-----\n";
    }

    /** RFC 8032 public key: the clamped scalar times the base point, compressed. Runs once per key. */
    static byte[] ed25519Public(byte[] seed) {
        byte[] k = scalar(seed);
        byte[] be = new byte[32];
        for (int i = 0; i < 32; i++) be[i] = k[31 - i];
        BigInteger[] q = mul(new BigInteger(1, be), new BigInteger[] {GX, GY, BigInteger.ONE, GX.multiply(GY).mod(P)});
        BigInteger zi = q[2].modInverse(P);
        BigInteger x = q[0].multiply(zi).mod(P);
        BigInteger y = q[1].multiply(zi).mod(P);
        if (x.testBit(0)) y = y.setBit(255);
        byte[] raw = y.toByteArray();
        byte[] out = new byte[32];
        for (int i = 0; i < 32 && i < raw.length; i++) out[i] = raw[raw.length - 1 - i];
        return out;
    }

    private static byte[] scalar(byte[] seed) {
        byte[] k;
        try {
            k = Arrays.copyOf(MessageDigest.getInstance("SHA-512").digest(seed), 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        k[0] &= (byte) 248;
        k[31] &= 127;
        k[31] |= 64;
        return k;
    }

    // Extended twisted Edwards coordinates (X, Y, Z, T), straight from RFC 8032.
    private static BigInteger[] add(BigInteger[] p, BigInteger[] q) {
        BigInteger a = p[1].subtract(p[0]).multiply(q[1].subtract(q[0])).mod(P);
        BigInteger b = p[1].add(p[0]).multiply(q[1].add(q[0])).mod(P);
        BigInteger c = p[3].multiply(q[3]).multiply(D).shiftLeft(1).mod(P);
        BigInteger d = p[2].multiply(q[2]).shiftLeft(1).mod(P);
        BigInteger e = b.subtract(a), f = d.subtract(c), g = d.add(c), h = b.add(a);
        return new BigInteger[] {e.multiply(f).mod(P), g.multiply(h).mod(P), f.multiply(g).mod(P), e.multiply(h).mod(P)};
    }

    private static BigInteger[] mul(BigInteger s, BigInteger[] p) {
        BigInteger[] q = {BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO};
        for (int i = 0; i < s.bitLength(); i++) {
            if (s.testBit(i)) q = add(q, p);
            p = add(p, p);
        }
        return q;
    }
}
