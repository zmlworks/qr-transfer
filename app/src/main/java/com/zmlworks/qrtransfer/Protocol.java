package com.zmlworks.qrtransfer;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * QR split-transfer protocol "QT1" (pure Java; shared format with web/sender.html).
 *
 * <pre>
 * frame   = "QT1:" ID ":" INDEX ":" TOTAL ":" CRC ":" BASE45(chunk)
 *           ID    8 upper-case hex chars, random per transfer
 *           INDEX 0-based decimal, TOTAL decimal (number of frames)
 *           CRC   8 upper-case hex chars = CRC-32 of the ORIGINAL content (before compression)
 * chunks  = envelope split into fixed-size byte pieces (last may be shorter)
 * envelope= u8 version(=1) | u8 flags | u16be nameLen | name (UTF-8) | payload
 *           flags bit0 = file (else text), bit1 = payload is raw DEFLATE (RFC 1951)
 * </pre>
 * Every character is in the QR alphanumeric set, so encoders use alphanumeric mode.
 */
public final class Protocol {
    private Protocol() {}

    public static final String MAGIC = "QT1";
    public static final int FLAG_FILE = 1, FLAG_DEFLATE = 2;
    public static final int MAX_CONTENT = 4 * 1024 * 1024;

    public static long crc32(byte[] b) {
        CRC32 c = new CRC32();
        c.update(b, 0, b.length);
        return c.getValue();
    }

    public static String hex8(long v) { return String.format(Locale.ROOT, "%08X", v & 0xffffffffL); }

    public static String newId() {
        return hex8(new SecureRandom().nextInt());
    }

    /** Decoded transfer content. */
    public static final class Content {
        public final boolean isFile;
        public final String name;
        public final byte[] data;
        public Content(boolean isFile, String name, byte[] data) { this.isFile = isFile; this.name = name; this.data = data; }
        public String text() { return new String(data, StandardCharsets.UTF_8); }
    }

    static byte[] deflate(byte[] in) {
        Deflater d = new Deflater(Deflater.BEST_COMPRESSION, true);
        d.setInput(in);
        d.finish();
        ByteArrayOutputStream o = new ByteArrayOutputStream(in.length / 2 + 64);
        byte[] buf = new byte[8192];
        while (!d.finished()) o.write(buf, 0, d.deflate(buf));
        d.end();
        return o.toByteArray();
    }

    static byte[] inflate(byte[] in, int limit) throws DataFormatException {
        Inflater f = new Inflater(true);
        // raw ("nowrap") inflate: older zlib wants one extra dummy byte after the stream
        f.setInput(java.util.Arrays.copyOf(in, in.length + 1));
        ByteArrayOutputStream o = new ByteArrayOutputStream(in.length * 3);
        byte[] buf = new byte[8192];
        try {
            while (!f.finished()) {
                int n = f.inflate(buf);
                if (n == 0 && (f.needsInput() || f.needsDictionary())) throw new DataFormatException("truncated");
                o.write(buf, 0, n);
                if (o.size() > limit) throw new DataFormatException("too large");
            }
        } finally { f.end(); }
        return o.toByteArray();
    }

    public static byte[] envelope(Content c, boolean tryCompress) {
        byte[] name = c.name == null ? new byte[0] : c.name.getBytes(StandardCharsets.UTF_8);
        if (name.length > 0xffff) throw new IllegalArgumentException("name too long");
        byte[] payload = c.data;
        int flags = c.isFile ? FLAG_FILE : 0;
        if (tryCompress && c.data.length > 64) {
            byte[] z = deflate(c.data);
            if (z.length < c.data.length * 0.95) { payload = z; flags |= FLAG_DEFLATE; }
        }
        ByteArrayOutputStream o = new ByteArrayOutputStream(4 + name.length + payload.length);
        o.write(1);
        o.write(flags);
        o.write(name.length >> 8);
        o.write(name.length & 0xff);
        o.write(name, 0, name.length);
        o.write(payload, 0, payload.length);
        return o.toByteArray();
    }

    public static Content openEnvelope(byte[] env) throws DataFormatException {
        if (env.length < 4 || env[0] != 1) throw new DataFormatException("bad envelope");
        int flags = env[1] & 0xff, nl = ((env[2] & 0xff) << 8) | (env[3] & 0xff);
        if (4 + nl > env.length) throw new DataFormatException("bad name length");
        String name = new String(env, 4, nl, StandardCharsets.UTF_8);
        byte[] payload = java.util.Arrays.copyOfRange(env, 4 + nl, env.length);
        if ((flags & FLAG_DEFLATE) != 0) payload = inflate(payload, MAX_CONTENT);
        return new Content((flags & FLAG_FILE) != 0, name, payload);
    }

    /** Build all frames. chunkBytes should be even so each chunk is whole base45 triplets. */
    public static List<String> frames(String id, Content c, int chunkBytes, boolean compress) {
        byte[] env = envelope(c, compress);
        String crc = hex8(crc32(c.data));
        int total = Math.max(1, (env.length + chunkBytes - 1) / chunkBytes);
        List<String> out = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            int off = i * chunkBytes, len = Math.min(chunkBytes, env.length - off);
            out.add(MAGIC + ":" + id + ":" + i + ":" + total + ":" + crc + ":" + Base45.encode(env, off, len));
        }
        return out;
    }

    /** Parsed frame header + chunk, or null if the text is not a QT1 frame. */
    public static final class Frame {
        public final String id, crc;
        public final int index, total;
        public final byte[] chunk;
        Frame(String id, int index, int total, String crc, byte[] chunk) {
            this.id = id; this.index = index; this.total = total; this.crc = crc; this.chunk = chunk;
        }
    }

    public static Frame parse(String s) {
        if (s == null || !s.startsWith(MAGIC + ":")) return null;
        String[] p = s.split(":", 6);
        if (p.length != 6 || p[1].length() != 8 || p[4].length() != 8) return null;
        try {
            int idx = Integer.parseInt(p[2]), total = Integer.parseInt(p[3]);
            if (total < 1 || total > 100_000 || idx < 0 || idx >= total) return null;
            return new Frame(p[1], idx, total, p[4], Base45.decode(p[5]));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Receiver-side reassembly. Not thread-safe; call from one thread. */
    public static final class Assembler {
        public enum Result { NEW, DUPLICATE, RESTARTED, IGNORED }

        private String id, crc;
        private byte[][] chunks;
        private final BitSet have = new BitSet();
        private int count, lastIndex = -1;

        public Result accept(String text) {
            Frame f = parse(text);
            if (f == null) return Result.IGNORED;
            Result r = Result.NEW;
            if (id == null || !id.equals(f.id) || chunks.length != f.total || !crc.equals(f.crc)) {
                r = id == null ? Result.NEW : Result.RESTARTED;
                id = f.id; crc = f.crc; chunks = new byte[f.total][]; have.clear(); count = 0;
            }
            if (have.get(f.index)) return Result.DUPLICATE;
            chunks[f.index] = f.chunk;
            have.set(f.index);
            count++;
            lastIndex = f.index;
            return r;
        }

        public boolean started() { return id != null; }
        public String id() { return id; }
        public String crc() { return crc; }
        public int total() { return chunks == null ? 0 : chunks.length; }
        public int received() { return count; }
        public int lastIndex() { return lastIndex; }
        public boolean has(int i) { return have.get(i); }
        public boolean complete() { return chunks != null && count == chunks.length; }

        /** Missing indices compressed to ranges, 1-based for display: "1-3, 7, 9-12". */
        public String missingRanges(int maxItems) {
            StringBuilder sb = new StringBuilder();
            int items = 0, n = total(), i = have.nextClearBit(0);
            while (i < n) {
                int j = have.nextSetBit(i);
                if (j < 0 || j > n) j = n;
                if (items == maxItems) { sb.append(", …"); break; }
                if (sb.length() > 0) sb.append(", ");
                sb.append(i + 1);
                if (j - 1 > i) sb.append('-').append(j);
                items++;
                i = have.nextClearBit(j);
            }
            return sb.toString();
        }

        /** Join, decompress, verify CRC-32. Throws if incomplete or corrupt. */
        public Content assemble() throws DataFormatException {
            if (!complete()) throw new DataFormatException("incomplete");
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            for (byte[] c : chunks) o.write(c, 0, c.length);
            Content c = openEnvelope(o.toByteArray());
            String got = hex8(crc32(c.data));
            if (!got.equals(crc)) throw new DataFormatException("CRC mismatch: expected " + crc + ", got " + got);
            return c;
        }

        public void reset() { id = null; crc = null; chunks = null; have.clear(); count = 0; lastIndex = -1; }
    }
}
