package com.zmlworks.qrtransfer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Random;

public class SlotSchedulerTest {
    @Test public void slotsOwnParities() {
        SlotScheduler s = new SlotScheduler(7, 2, 250, false);
        s.start(0);
        assertEquals(0, s.shown(0));
        assertEquals(1, s.shown(1));
        List<Integer> a = new ArrayList<>(), b = new ArrayList<>();
        for (long t = 250; t <= 2000; t += 250) {
            assertTrue(s.tick(t));
            a.add(s.shown(0));
            b.add(s.shown(1));
        }
        assertEquals(List.of(2, 4, 6, 0, 2, 4, 6, 0), a);
        assertEquals(List.of(3, 5, 1, 3, 5, 1, 3, 5), b);
    }

    @Test public void singleChunkUsesOneSlot() {
        SlotScheduler s = new SlotScheduler(1, 2, 250, true);
        s.start(0);
        assertEquals(1, s.slots);
        assertEquals(0, s.shown(0));
        assertEquals(-1, s.shown(1));
        assertTrue(s.onAck(0, 300));
        assertEquals(-1, s.shown(0));   // idle: all acknowledged, waiting for DONE
    }

    @Test public void oneSlotAcceptsEitherTone() {
        SlotScheduler s = new SlotScheduler(4, 1, 250, true);
        s.start(0);
        assertTrue(s.onAck(1, 300));    // chunk 0 is even but tone choice depends on parity: B for odd...
        assertEquals(1, s.shown(0));
        assertTrue(s.onAck(1, 600));
        assertEquals(2, s.shown(0));
        assertTrue(s.onAck(0, 900));
        assertEquals(3, s.ackedCount());
    }

    @Test public void ackAdvancesOnlyItsSlot() {
        SlotScheduler s = new SlotScheduler(10, 2, 250, true);
        s.start(0);
        assertTrue(s.onAck(1, 300));
        assertEquals(0, s.shown(0));
        assertEquals(3, s.shown(1));
        assertTrue(s.isAcked(1));
        assertFalse(s.isAcked(0));
        assertTrue(s.onAck(0, 400));
        assertEquals(2, s.shown(0));
    }

    @Test public void toneRightAfterSwitchBelongsToPreviousChunk() {
        SlotScheduler s = new SlotScheduler(10, 2, 250, true);
        s.start(0);
        long t = s.timeoutMs();
        assertTrue(s.tick(t));                 // timeout: 0 → 2 without ack
        assertEquals(2, s.shown(0));
        assertFalse(s.onAck(0, t + 60));       // late tone for chunk 0
        assertTrue(s.isAcked(0));
        assertFalse(s.isAcked(2));
        assertEquals(2, s.shown(0));           // keeps showing 2
    }

    @Test public void timeoutResendsLaterAndAdaptsToLatency() {
        SlotScheduler s = new SlotScheduler(6, 2, 250, true);
        s.start(0);
        assertEquals(1700, s.timeoutMs());     // 2.5 × 600 + 200
        long now = 0;
        for (int k = 0; k < 20; k++) {         // receiver acks reliably after 300 ms
            now += 300;
            s.onAck(0, now);
            s.onAck(1, now);
            if (s.ackedCount() == 6) break;
        }
        assertEquals(6, s.ackedCount());
        assertTrue(s.latencyMs() < 450);
        assertTrue(s.timeoutMs() < 1300);
    }

    @Test public void skipsAcknowledgedAndResendsMissing() {
        SlotScheduler s = new SlotScheduler(6, 2, 250, true);   // A: 0 2 4, B: 1 3 5
        s.start(0);
        s.onAck(0, 300);                       // 0 ok → shows 2
        s.tick(300 + s.timeoutMs());           // 2 times out → 4
        assertEquals(4, s.shown(0));
        s.onAck(0, 300 + s.timeoutMs() + 400); // 4 ok → next unacked is 2 (resend)
        assertEquals(2, s.shown(0));
    }

    @Test public void lastChunkIsResentUntilAcked() {
        SlotScheduler s = new SlotScheduler(2, 2, 250, true);
        s.start(0);
        s.onAck(1, 300);
        assertEquals(-1, s.shown(1));
        long t = s.timeoutMs();
        s.tick(t);
        assertEquals(0, s.shown(0));           // same chunk again (resend)
        s.tick(2 * t + 1);
        assertEquals(0, s.shown(0));
        s.onAck(0, 2 * t + 400);
        assertEquals(-1, s.shown(0));
    }

    @Test public void verificationRoundWhenNoDone() {
        SlotScheduler s = new SlotScheduler(3, 2, 250, true);
        s.start(0);
        s.onAck(0, 300);
        s.onAck(1, 300);
        s.onAck(0, 600);
        assertEquals(3, s.ackedCount());
        assertEquals(-1, s.shown(0));
        assertFalse(s.tick(600 + SlotScheduler.VERIFY_MS - 1));
        assertTrue(s.tick(600 + SlotScheduler.VERIFY_MS));
        assertEquals(1, s.rounds());
        assertEquals(0, s.ackedCount());
        assertEquals(0, s.shown(0));
        assertEquals(1, s.shown(1));
        s.onDone();
        assertTrue(s.finished());
        assertFalse(s.tick(100_000));
    }

    @Test public void manualStep() {
        SlotScheduler s = new SlotScheduler(5, 2, 250, false);   // A: 0 2 4, B: 1 3
        s.start(0);
        s.step(0, -1, 10);
        assertEquals(4, s.shown(0));
        s.step(1, 1, 10);
        assertEquals(3, s.shown(1));
        s.step(1, 1, 10);
        assertEquals(1, s.shown(1));
    }

    // ---------------- end-to-end simulation ----------------

    /** Simulated sender screen + receiver camera + speaker/mic; returns ms until DONE is heard (or -1). */
    static long simulate(int total, int slots, boolean ack, double pRead, double pHear, double pFalseAck, long seed) {
        return simulate(total, slots, ack, 250, pRead, pHear, pFalseAck, seed);
    }

    static long simulate(int total, int slots, boolean ack, long period, double pRead, double pHear, double pFalseAck, long seed) {
        Random rnd = new Random(seed);
        SlotScheduler s = new SlotScheduler(total, slots, period, ack);
        AckPolicy policy = new AckPolicy();
        BitSet have = new BitSet();
        long[] shownSince = new long[2];
        int[] lastShown = {-2, -2};
        List<long[]> inFlight = new ArrayList<>();   // {arrivalMs, tone(0/1/2=DONE)}
        boolean complete = false;
        s.start(0);
        long nextTick = s.nextDeadline(0);
        for (long t = 0; t < 600_000; t += 5) {
            // sender: timer + heard tones
            for (int k = 0; k < inFlight.size(); k++) {
                long[] e = inFlight.get(k);
                if (e[0] <= t) {
                    inFlight.remove(k--);
                    if (e[1] == 2) { s.onDone(); return t; }
                    s.onAck((int) e[1], t);
                    nextTick = s.nextDeadline(t);
                }
            }
            if (t >= nextTick) { s.tick(t); nextTick = s.nextDeadline(t); }
            for (int k = 0; k < s.slots; k++) if (s.shown(k) != lastShown[k]) { lastShown[k] = s.shown(k); shownSince[k] = t; }
            // receiver: 30 fps camera, needs a code on screen for ≥ 40 ms (exposure + display refresh)
            if (t % 33 < 5) {
                for (int k = 0; k < s.slots; k++) {
                    int idx = s.shown(k);
                    if (idx < 0 || t - shownSince[k] < 40 || rnd.nextDouble() > pRead) continue;
                    if (!complete) have.set(idx);
                    long base = t + 80 + rnd.nextInt(60);              // decode + audio output latency
                    if (have.cardinality() == total && !complete) {
                        complete = true;
                        if (rnd.nextDouble() < pHear) inFlight.add(new long[]{base + 150, 2});
                        policy.shouldRepeatDone(t);
                        continue;
                    }
                    if (complete) {
                        if (policy.shouldRepeatDone(t) && rnd.nextDouble() < pHear) inFlight.add(new long[]{base + 150, 2});
                        continue;
                    }
                    int tone = policy.onFrame(idx, t);
                    if (tone >= 0 && rnd.nextDouble() < pHear) inFlight.add(new long[]{base + 40, tone});
                }
                // spurious tone from the environment
                if (ack && rnd.nextDouble() < pFalseAck) inFlight.add(new long[]{t, rnd.nextInt(2)});
            }
        }
        return -1;
    }

    @Test public void simulatedTransfersComplete() {
        for (long seed = 1; seed <= 20; seed++) {
            assertTrue(simulate(60, 2, true, 0.9, 0.95, 0, seed) > 0);
            assertTrue(simulate(60, 2, true, 0.5, 0.6, 0.002, seed) > 0);     // bad camera, noisy room
            assertTrue(simulate(61, 1, true, 0.9, 0.95, 0, seed) > 0);
            assertTrue(simulate(61, 2, false, 0.7, 1.0, 0, seed) > 0);        // no mic: timed loop
        }
    }

    @Test public void twoSlotsRoughlyDoubleThroughput() {
        long one = 0, two = 0;
        for (long seed = 1; seed <= 20; seed++) {
            one += simulate(100, 1, true, 0.9, 0.95, 0, seed);
            two += simulate(100, 2, true, 0.9, 0.95, 0, seed);
        }
        double speedup = (double) one / two;
        assertTrue("speed-up " + speedup, speedup > 1.7);
        // ack pacing is never much slower than a blind loop that happens to match the camera, and much faster than
        // a blind loop that is too fast for a weak camera (codes missed → many passes)
        long blind4 = 0, blind10 = 0, ackWeak = 0;
        for (long seed = 1; seed <= 20; seed++) {
            blind4 += simulate(100, 2, false, 250, 0.9, 0.95, 0, seed);
            blind10 += simulate(100, 2, false, 100, 0.25, 0.95, 0, seed);
            ackWeak += simulate(100, 2, true, 100, 0.25, 0.95, 0, seed);
        }
        System.out.println("sim: 1 slot " + one / 20 + " ms, 2 slots " + two / 20 + " ms, blind 4fps " + blind4 / 20
                + " ms; weak camera: blind 10fps " + blind10 / 20 + " ms vs ack " + ackWeak / 20 + " ms");
        assertTrue(two < blind4 * 1.15);
        assertTrue(ackWeak < blind10);
    }
}
