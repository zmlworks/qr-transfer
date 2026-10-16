package com.zmlworks.qrtransfer;

import java.io.ByteArrayOutputStream;

/**
 * Base45 (RFC 9285). Its 45 characters are exactly the QR "alphanumeric" set, which QR packs at
 * 5.5 bits/char: 2 bytes -> 3 chars = 16.5 bits per 16 bits of data (97 % efficient), versus
 * base64 in byte mode at 75 %. So each QR frame carries ~30 % more data at the same QR version.
 */
public final class Base45 {
    private Base45() {}

    static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:";

    public static String encode(byte[] b, int off, int len) {
        StringBuilder sb = new StringBuilder((len + 1) / 2 * 3);
        int i = off, end = off + len;
        for (; i + 1 < end; i += 2) {
            int n = ((b[i] & 0xff) << 8) | (b[i + 1] & 0xff);
            sb.append(ALPHABET.charAt(n % 45)).append(ALPHABET.charAt((n / 45) % 45)).append(ALPHABET.charAt(n / 2025));
        }
        if (i < end) {
            int n = b[i] & 0xff;
            sb.append(ALPHABET.charAt(n % 45)).append(ALPHABET.charAt(n / 45));
        }
        return sb.toString();
    }

    public static String encode(byte[] b) { return encode(b, 0, b.length); }

    private static int val(char c) {
        int v = ALPHABET.indexOf(c);
        if (v < 0) throw new IllegalArgumentException("bad base45 char");
        return v;
    }

    public static byte[] decode(String s) {
        if (s.length() % 3 == 1) throw new IllegalArgumentException("bad base45 length");
        ByteArrayOutputStream o = new ByteArrayOutputStream(s.length() / 3 * 2 + 1);
        int i = 0;
        for (; i + 2 < s.length(); i += 3) {
            int n = val(s.charAt(i)) + val(s.charAt(i + 1)) * 45 + val(s.charAt(i + 2)) * 2025;
            if (n > 0xffff) throw new IllegalArgumentException("bad base45 triplet");
            o.write(n >> 8);
            o.write(n & 0xff);
        }
        if (i < s.length()) {
            int n = val(s.charAt(i)) + val(s.charAt(i + 1)) * 45;
            if (n > 0xff) throw new IllegalArgumentException("bad base45 pair");
            o.write(n);
        }
        return o.toByteArray();
    }
}
