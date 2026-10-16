package com.zmlworks.qrtransfer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.EncodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.qrcode.decoder.Mode;
import com.google.zxing.qrcode.encoder.Encoder;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.zip.DataFormatException;

public class ProtocolTest {

    @Test public void base45Rfc9285Vectors() {
        assertEquals("BB8", Base45.encode("AB".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("%69 VD92EX0", Base45.encode("Hello!!".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("UJCLQE7W581", Base45.encode("base-45".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("ietf!", new String(Base45.decode("QED8WEX0"), StandardCharsets.US_ASCII));
        try { Base45.decode("GGW"); fail("65535 < GGW value must be rejected"); } catch (IllegalArgumentException expected) { }
        Random r = new Random(1);
        for (int n = 0; n < 50; n++) {
            byte[] b = new byte[r.nextInt(40)];
            r.nextBytes(b);
            assertArrayEquals(b, Base45.decode(Base45.encode(b)));
        }
    }

    @Test public void crcCheckValue() {
        assertEquals("CBF43926", Protocol.hex8(Protocol.crc32("123456789".getBytes(StandardCharsets.US_ASCII))));
    }

    private static Protocol.Content roundTrip(Protocol.Content c, int chunk, boolean compress, long seed) throws Exception {
        List<String> frames = new ArrayList<>(Protocol.frames("1234ABCD", c, chunk, compress));
        // receive out of order, with duplicates
        List<String> rx = new ArrayList<>(frames);
        rx.addAll(frames.subList(0, frames.size() / 2));
        Collections.shuffle(rx, new Random(seed));
        Protocol.Assembler a = new Protocol.Assembler();
        int news = 0;
        for (String f : rx) {
            assertTrue(f.matches("[0-9A-Z $%*+\\-./:]+"));
            Protocol.Assembler.Result res = a.accept(f);
            if (res == Protocol.Assembler.Result.NEW) news++;
        }
        assertEquals(frames.size(), news);
        assertTrue(a.complete());
        assertEquals("", a.missingRanges(10));
        return a.assemble();
    }

    @Test public void splitAndReassembleManySizes() throws Exception {
        Random r = new Random(42);
        for (int size : new int[]{0, 1, 2, 199, 200, 201, 1000, 4097, 30_000}) {
            for (int chunk : new int[]{2, 200, 400, 1000}) {
                if (size / chunk > 3000) continue;
                byte[] data = new byte[size];
                r.nextBytes(data);
                Protocol.Content got = roundTrip(new Protocol.Content(true, "f.bin", data), chunk, true, size + chunk);
                assertArrayEquals(data, got.data);
                assertTrue(got.isFile);
                assertEquals("f.bin", got.name);
            }
        }
    }

    @Test public void textIsCompressedAndNamePreserved() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) sb.append("電波を使わないデータ受け渡し ").append(i).append('\n');
        byte[] data = sb.toString().getBytes(StandardCharsets.UTF_8);
        Protocol.Content c = new Protocol.Content(false, "", data);
        int plain = Protocol.frames("00000000", c, 400, false).size(), packed = Protocol.frames("00000000", c, 400, true).size();
        assertTrue("deflate should shrink text: " + packed + " vs " + plain, packed * 3 < plain);
        assertEquals(sb.toString(), roundTrip(c, 400, true, 7).text());
    }

    @Test public void missingRangesAndRestart() {
        List<String> f = Protocol.frames("AAAAAAAA", new Protocol.Content(false, "", new byte[2000]), 100, false);
        assertEquals(21, f.size());
        Protocol.Assembler a = new Protocol.Assembler();
        assertEquals(Protocol.Assembler.Result.IGNORED, a.accept("hello"));
        assertEquals(Protocol.Assembler.Result.IGNORED, a.accept("QT1:AAAAAAAA:5:3:00000000:"));  // index >= total
        for (int i : new int[]{3, 4, 5, 9}) a.accept(f.get(i));
        assertEquals("1-3, 7-9, 11-21", a.missingRanges(10));
        assertEquals("1-3, …", a.missingRanges(1));
        assertEquals(Protocol.Assembler.Result.DUPLICATE, a.accept(f.get(3)));
        List<String> g = Protocol.frames("BBBBBBBB", new Protocol.Content(false, "", new byte[10]), 100, false);
        assertEquals(Protocol.Assembler.Result.RESTARTED, a.accept(g.get(0)));
        assertTrue(a.complete());
        assertEquals("BBBBBBBB", a.id());
    }

    @Test public void corruptionIsDetected() {
        byte[] data = "The quick brown fox jumps over the lazy dog, again and again and again.".getBytes(StandardCharsets.US_ASCII);
        List<String> f = Protocol.frames("CCCCCCCC", new Protocol.Content(false, "", data), 1000, false);
        String s = f.get(0);
        // flip one payload character to another valid base45 char -> CRC must catch it
        int pos = s.length() - 10;
        char ch = s.charAt(pos) == 'A' ? 'B' : 'A';
        Protocol.Assembler a = new Protocol.Assembler();
        a.accept(s.substring(0, pos) + ch + s.substring(pos + 1));
        try { a.assemble(); fail("expected CRC mismatch"); } catch (DataFormatException e) { assertTrue(e.getMessage(), e.getMessage().startsWith("CRC") || e.getMessage().startsWith("bad")); }
        assertNull(Protocol.parse("QT1:short:0:1:00000000:"));
    }

    // ---- ZXing round trips ----
    private static String decode(LuminanceSource src) throws Exception {
        Map<DecodeHintType, Object> h = new EnumMap<>(DecodeHintType.class);
        h.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        return new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(src)), h).getText();
    }

    private static LuminanceSource render(BitMatrix m, int scale, int quiet) {
        int n = m.getWidth(), size = (n + 2 * quiet) * scale;
        int[] px = new int[size * size];
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++) {
                int mx = x / scale - quiet, my = y / scale - quiet;
                boolean dark = mx >= 0 && my >= 0 && mx < n && my < n && m.get(mx, my);
                px[y * size + x] = dark ? 0xFF000000 : 0xFFFFFFFF;
            }
        return new RGBLuminanceSource(size, size, px);
    }

    @Test public void zxingEncodeDecodeUsesAlphanumericMode() throws Exception {
        byte[] data = new byte[700];
        new Random(3).nextBytes(data);
        for (String frame : Protocol.frames(Protocol.newId(), new Protocol.Content(true, "x", data), 400, false)) {
            assertEquals(Mode.ALPHANUMERIC, Encoder.encode(frame, ErrorCorrectionLevel.M).getMode());
            Map<EncodeHintType, Object> h = new EnumMap<>(EncodeHintType.class);
            h.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            h.put(EncodeHintType.MARGIN, 0);
            BitMatrix m = new QRCodeWriter().encode(frame, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0, h);
            assertEquals(frame, decode(render(m, 3, 4)));
        }
    }

    @Test public void framesFromWebSenderDecode() throws Exception {
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                getClass().getClassLoader().getResourceAsStream("web_frames.txt"), StandardCharsets.UTF_8))) {
            for (String l; (l = r.readLine()) != null; ) lines.add(l);
        }
        int cases = 0;
        for (int i = 0; i < lines.size(); ) {
            String[] h = lines.get(i++).split(" ");
            assertEquals("#case", h[0]);
            boolean isFile = h[2].equals("1");
            String name = h[3].equals("-") ? "" : new String(Base64.getDecoder().decode(h[3]), StandardCharsets.UTF_8);
            byte[] expect = h.length > 4 ? Base64.getDecoder().decode(h[4]) : new byte[0];
            Protocol.Assembler a = new Protocol.Assembler();
            List<String> frames = new ArrayList<>();
            while (i < lines.size() && !lines.get(i).startsWith("#case")) frames.add(lines.get(i++));
            Collections.reverse(frames);
            for (String f : frames) a.accept(f);
            assertTrue(h[1], a.complete());
            Protocol.Content c = a.assemble();
            assertArrayEquals(h[1], expect, c.data);
            assertEquals(isFile, c.isFile);
            assertEquals(name, c.name);
            cases++;
        }
        assertEquals(4, cases);
    }

    @Test public void qrSymbolFromWebSenderDecodesWithZxing() throws Exception {
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                getClass().getClassLoader().getResourceAsStream("web_qr_matrix.txt"), StandardCharsets.UTF_8))) {
            for (String l; (l = r.readLine()) != null; ) lines.add(l);
        }
        int n = lines.size() - 1;
        BitMatrix m = new BitMatrix(n);
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) if (lines.get(y + 1).charAt(x) == '1') m.set(x, y);
        assertEquals(lines.get(0), decode(render(m, 4, 4)));
        assertFalse(lines.get(0).isEmpty());
    }
}
