package com.zmlworks.qrtransfer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Random;

public class Upscale2xTest {
    @Test public void flatStaysFlatAndSizeDoubles() {
        int w = 37, h = 21;
        byte[] src = new byte[w * h];
        java.util.Arrays.fill(src, (byte) 200);
        byte[] out = Upscale2x.run(src, w, h, null, new int[2 * w * h]);
        assertEquals(4 * w * h, out.length);
        for (byte b : out) assertEquals(200, b & 0xff);
    }

    @Test public void clampsAndKeepsEdges() {
        // black/white checkerboard: bicubic overshoots, the output must clamp to 0..255 and keep the contrast
        int w = 16, h = 16;
        byte[] src = new byte[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) src[y * w + x] = (byte) (((x / 2 + y / 2) & 1) == 0 ? 0 : 255);
        byte[] out = Upscale2x.run(src, w, h, null, new int[2 * w * h]);
        int lo = 255, hi = 0;
        for (byte b : out) { lo = Math.min(lo, b & 0xff); hi = Math.max(hi, b & 0xff); }
        assertEquals(0, lo);
        assertEquals(255, hi);
        // a module centre stays its colour
        assertEquals(0, out[(2 * 1) * 2 * w + 2 * 1] & 0xff, 20);
        assertEquals(255, out[(2 * 1) * 2 * w + 2 * 3] & 0xff, 20);
    }

    @Test public void reusesBuffersAndIsDeterministic() {
        int w = 50, h = 30;
        byte[] src = new byte[w * h];
        new Random(1).nextBytes(src);
        int[] tmp = new int[2 * w * h];
        byte[] a = Upscale2x.run(src, w, h, null, tmp);
        byte[] b = Upscale2x.run(src, w, h, a.clone(), tmp);
        assertTrue(java.util.Arrays.equals(a, b));
        byte[] big = new byte[4 * w * h + 100];
        assertTrue(Upscale2x.run(src, w, h, big, tmp) == big);
    }
}
