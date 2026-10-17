package com.zmlworks.qrtransfer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

public class DecodePipelineTest {
    /** finds a "code" only in frames at least minW wide (stands in for a code that needs ≥ 3 px/module) */
    static final class Fake implements QrEngine {
        final int minW; int calls;
        Fake(int minW) { this.minW = minW; }
        public List<String> decode(byte[] y, int w, int h) { calls++; return w >= minW ? List.of("QT1|x") : Collections.emptyList(); }
        public String name() { return "fake"; }
    }

    @Test public void upscalesOnlyWhenNothingFoundAndRateLimited() {
        Fake f = new Fake(200);
        DecodePipeline p = new DecodePipeline(f, true);
        byte[] y = new byte[100 * 60];
        assertEquals(1, p.decode(y, 100, 60, 0).size());          // nothing at 100 px, found at 200 px
        assertEquals(2, f.calls);
        assertEquals(0, p.decode(y, 100, 60, 100).size());        // rate-limited: no second upscale within 400 ms
        assertEquals(3, f.calls);
        assertEquals(1, p.decode(y, 100, 60, DecodePipeline.UPSCALE_MIN_INTERVAL_MS).size());
        assertEquals(2, p.upscaleTries());
        assertEquals(2, p.upscaleHits());
        assertEquals(3, p.frames());
        assertEquals(2, p.codes());
        assertTrue(p.avgMs() >= 0 && p.avgUpscaleMs() >= 0 && p.lastUpscaleOnlyMs() >= 0);
    }

    @Test public void noUpscaleWhenFoundOrDisabled() {
        Fake f = new Fake(50);
        DecodePipeline p = new DecodePipeline(f, true);
        byte[] y = new byte[100 * 60];
        assertEquals(1, p.decode(y, 100, 60, 0).size());
        assertEquals(1, f.calls);
        Fake g = new Fake(200);
        p.setEngine(g);
        p.setUpscale(false);
        assertEquals(0, p.frames());
        assertEquals(0, p.decode(y, 100, 60, 1000).size());
        assertEquals(1, g.calls);
        assertEquals(0, p.upscaleTries());
    }
}
