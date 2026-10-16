package com.zmlworks.qrtransfer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public class FrameDecoderTest {
    static BitMatrix qr(String text) throws Exception {
        Map<EncodeHintType, Object> h = new EnumMap<>(EncodeHintType.class);
        h.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        h.put(EncodeHintType.MARGIN, 4);
        return new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, h);
    }

    /** grey frame w×h with codes drawn at (x, y) with `scale` px per module; mid-grey background, mild noise */
    static byte[] frame(int w, int h, Object... codes) {
        byte[] y = new byte[w * h];
        java.util.Arrays.fill(y, (byte) 90);
        for (int k = 0; k < codes.length; k += 4) {
            BitMatrix m = (BitMatrix) codes[k];
            int ox = (Integer) codes[k + 1], oy = (Integer) codes[k + 2], sc = (Integer) codes[k + 3];
            for (int r = 0; r < m.getHeight() * sc; r++)
                for (int c = 0; c < m.getWidth() * sc; c++) {
                    int px = ox + c, py = oy + r;
                    if (px < 0 || py < 0 || px >= w || py >= h) continue;
                    y[py * w + px] = (byte) (m.get(c / sc, r / sc) ? 30 : 225);
                }
        }
        Random rnd = new Random(5);
        for (int i = 0; i < y.length; i++) y[i] = (byte) Math.max(0, Math.min(255, (y[i] & 0xFF) + rnd.nextInt(31) - 15));
        return y;
    }

    static String frameText(int index) {
        Protocol.Content c = new Protocol.Content(false, "", ("payload for the QR split-transfer test ✓ ").repeat(120)
                .getBytes(StandardCharsets.UTF_8));
        return Protocol.frames("1234ABCD", c, 400, false).get(index);
    }

    static Set<String> decodeSome(FrameDecoder d, byte[] y, int w, int h) {
        Set<String> got = new HashSet<>();
        for (int i = 0; i < 2; i++) got.addAll(d.decode(y, w, h));   // both split directions
        return got;
    }

    @Test public void twoStackedCodesInPortraitFrame() throws Exception {
        String a = frameText(0), b = frameText(1);
        BitMatrix ma = qr(a), mb = qr(b);
        int sc = 4, w = 720, h = 1280, side = ma.getWidth() * sc, x = (w - side) / 2, y0 = (h - 2 * side - 40) / 2;
        byte[] y = frame(w, h, ma, x, y0, sc, mb, x, y0 + side + 40, sc);
        Set<String> got = new HashSet<>(new FrameDecoder().decode(y, w, h));
        assertEquals(Set.of(a, b), got);
    }

    @Test public void twoCodesInRotatedLandscapeSensorFrame() throws Exception {
        // phone held upright → camera sensor (landscape) sees the stacked codes side by side
        String a = frameText(2), b = frameText(3);
        BitMatrix ma = qr(a), mb = qr(b);
        int sc = 5, w = 1280, h = 720, side = ma.getWidth() * sc, x0 = (w - 2 * side - 30) / 2, y0 = (h - side) / 2;
        byte[] y = frame(w, h, ma, x0, y0, sc, mb, x0 + side + 30, y0, sc);
        assertEquals(Set.of(a, b), decodeSome(new FrameDecoder(), y, w, h));
    }

    @Test public void singleCodeStillWorks() throws Exception {
        String a = frameText(4);
        BitMatrix ma = qr(a);
        byte[] y = frame(1280, 720, ma, 400, 100, 7);
        List<String> got = new FrameDecoder().decode(y, 1280, 720);
        assertEquals(List.of(a), got);
    }

    @Test public void emptyFrameGivesNothing() {
        assertTrue(new FrameDecoder().decode(frame(640, 480), 640, 480).isEmpty());
    }

    @Test public void touchingCodesAreSplitByHalves() throws Exception {
        // codes with almost no gap (quiet zones overlap): the half split must still read both
        String a = frameText(5), b = frameText(6);
        BitMatrix ma = qr(a), mb = qr(b);
        int sc = 5, w = 1280, h = 720, side = ma.getWidth() * sc;
        int x0 = (w - 2 * side + 2 * sc) / 2, x2 = x0 + (ma.getWidth() - 2) * sc;   // quiet zones overlap by 2 modules
        byte[] y = frame(w, h, ma, x0, (h - side) / 2, sc, mb, x2, (h - side) / 2, sc);
        Set<String> got = decodeSome(new FrameDecoder(), y, w, h);
        assertEquals(Set.of(a, b), got);
    }

    @Test public void randomLayoutsSweep() throws Exception {
        // random scale (4–6 px/module), position jitter, gap from "touching" to ~16 modules, both orientations
        Random r = new Random(1);
        int both = 0, n = 60;
        for (int k = 0; k < n; k++) {
            int i = r.nextInt(12);
            String ta = frameText(i), tb = frameText(i + 1);
            BitMatrix ma = qr(ta), mb = qr(tb);
            int sc = 4 + r.nextInt(3);
            boolean land = r.nextBoolean();
            int w = land ? 1920 : 1080, h = land ? 1080 : 1920, side = ma.getWidth() * sc, gap = r.nextInt(80) - 2 * sc;
            byte[] y;
            if (land) {
                int x0 = Math.max(0, (w - 2 * side - gap) / 2 + r.nextInt(81) - 40), y0 = Math.max(0, (h - side) / 2 + r.nextInt(81) - 40);
                y = frame(w, h, ma, x0, y0, sc, mb, x0 + side + gap, y0, sc);
            } else {
                int x0 = Math.max(0, (w - side) / 2 + r.nextInt(81) - 40), y0 = Math.max(0, (h - 2 * side - gap) / 2 + r.nextInt(81) - 40);
                y = frame(w, h, ma, x0, y0, sc, mb, x0, y0 + side + gap, sc);
            }
            if (decodeSome(new FrameDecoder(), y, w, h).equals(Set.of(ta, tb))) both++;
        }
        assertTrue("both codes in " + both + "/" + n, both >= 55);
    }
}
