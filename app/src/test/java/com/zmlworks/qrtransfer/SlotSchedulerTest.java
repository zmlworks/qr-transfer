package com.zmlworks.qrtransfer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** Alternating-bit sender state machine: lost acks, lost frames, duplicate and stale acks, loop mode. */
public class SlotSchedulerTest {
    @Test public void loopModeCyclesEachSlotsParity() {
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
        assertFalse(s.onAck(Tone.A1, 2100));           // acks mean nothing in loop mode
    }

    @Test public void ackModeNeverAdvancesWithoutAck() {
        // the 0.2.0 bug: an adaptive timeout advanced every ~600 ms. Now nothing moves for an hour of silence.
        SlotScheduler s = new SlotScheduler(20, 2, 100, true);
        s.start(0);
        s.onAck(Tone.A0, 170);                        // a few quick acks (which used to shrink the timeout)
        s.onAck(Tone.B0, 180);
        s.onAck(Tone.A1, 340);
        s.onAck(Tone.B1, 350);
        assertEquals(4, s.shown(0));
        assertEquals(5, s.shown(1));
        for (long t = 360; t < 3_600_000; t = s.nextDeadline(t)) assertFalse(s.tick(t));
        assertEquals(4, s.shown(0));
        assertEquals(5, s.shown(1));
        assertTrue(s.silent(3_600_000));
        assertEquals(Tone.A0, s.expectedTone(0));
        assertEquals(Tone.B0, s.expectedTone(1));
    }

    @Test public void advancesOnlyOnMatchingSlotAndBit() {
        SlotScheduler s = new SlotScheduler(10, 2, 250, true);   // A: 0 2 4 6 8, B: 1 3 5 7 9
        s.start(0);
        assertEquals(Tone.A0, s.expectedTone(0));
        assertEquals(Tone.B0, s.expectedTone(1));
        assertFalse(s.onAck(Tone.A1, 100));           // wrong bit for slot A (and not B's tone)
        assertFalse(s.onAck(Tone.B1, 110));
        assertEquals(0, s.shown(0));
        assertEquals(1, s.shown(1));
        assertEquals(2, s.staleAcks());
        assertTrue(s.onAck(Tone.B0, 300));            // B only
        assertEquals(0, s.shown(0));
        assertEquals(3, s.shown(1));
        assertTrue(s.isAcked(1));
        assertFalse(s.isAcked(0));
        assertTrue(s.onAck(Tone.A0, 400));
        assertEquals(2, s.shown(0));
        assertEquals(Tone.A1, s.expectedTone(0));
    }

    @Test public void staleAndDuplicateAcksAreIgnored() {
        SlotScheduler s = new SlotScheduler(10, 2, 250, true);
        s.start(0);
        assertTrue(s.onAck(Tone.A0, 200));            // 0 acked → shows 2 (A1)
        assertFalse(s.onAck(Tone.A0, 210));           // duplicate detection of the same ack burst
        assertFalse(s.onAck(Tone.A0, 900));           // receiver re-acked 0 (it still saw it): stale
        assertEquals(2, s.shown(0));
        assertTrue(s.onAck(Tone.A1, 1000));           // 2 acked → 4 (A0 again)
        assertEquals(4, s.shown(0));
        assertFalse(s.onAck(Tone.A1, 1010));          // late duplicate for 2: other bit, ignored
        assertEquals(4, s.shown(0));
        assertEquals(2, s.goodAcks());
        assertEquals(3, s.staleAcks());
        assertEquals(2, s.ackedCount());
    }

    @Test public void lostAckKeepsChunkUntilReack() {
        SlotScheduler s = new SlotScheduler(6, 2, 250, true);
        s.start(0);
        // the receiver read chunk 0 but its ack was lost: nothing changes...
        assertFalse(s.tick(5000));
        assertEquals(0, s.shown(0));
        // ...until the periodic re-ack is heard
        assertTrue(s.onAck(Tone.A0, 5000 + AckPolicy.REACK_MS));
        assertEquals(2, s.shown(0));
    }

    @Test public void lostFramesJustWait() {
        // camera misses chunk 3 for a long time; slot B waits on it while slot A finishes
        SlotScheduler s = new SlotScheduler(6, 2, 250, true);
        s.start(0);
        s.onAck(Tone.A0, 100); s.onAck(Tone.B0, 100);   // 0, 1
        s.onAck(Tone.A1, 300); s.onAck(Tone.A0, 500);   // 2, 4 → slot A done
        assertEquals(-1, s.shown(0));
        assertEquals(3, s.shown(1));
        for (long t = 600; t < 60_000; t += 100) assertFalse(s.tick(t));
        assertEquals(3, s.shown(1));
        assertTrue(s.onAck(Tone.B1, 60_000));
        assertEquals(5, s.shown(1));
        assertTrue(s.onAck(Tone.B0, 60_200));
        assertEquals(-1, s.shown(1));
        assertEquals(6, s.ackedCount());
    }

    @Test public void oneSlotUsesTheSameToneMapping() {
        SlotScheduler s = new SlotScheduler(5, 1, 250, true);
        s.start(0);
        int[] tones = {Tone.A0, Tone.B0, Tone.A1, Tone.B1, Tone.A0};
        for (int i = 0; i < 5; i++) {
            assertEquals(i, s.shown(0));
            assertEquals(tones[i], s.expectedTone(0));
            assertFalse(s.onAck(tones[(i + 3) % 4], i * 100 + 50));   // the previous chunk's tone: stale
            assertTrue(s.onAck(tones[i], i * 100 + 90));
        }
        assertEquals(-1, s.shown(0));
    }

    @Test public void singleChunkUsesOneSlot() {
        SlotScheduler s = new SlotScheduler(1, 2, 250, true);
        s.start(0);
        assertEquals(1, s.slots);
        assertEquals(0, s.shown(0));
        assertEquals(-1, s.shown(1));
        assertFalse(s.onAck(Tone.B0, 100));
        assertTrue(s.onAck(Tone.A0, 300));
        assertEquals(-1, s.shown(0));
    }

    @Test public void verificationPassWhenAllAckedButNoDone() {
        SlotScheduler s = new SlotScheduler(3, 2, 250, true);
        s.start(0);
        s.onAck(Tone.A0, 300);
        s.onAck(Tone.B0, 300);
        s.onAck(Tone.A1, 600);
        assertEquals(3, s.ackedCount());
        assertEquals(-1, s.shown(0));
        assertFalse(s.tick(600 + SlotScheduler.VERIFY_MS - 1));
        assertTrue(s.tick(600 + SlotScheduler.VERIFY_MS));
        assertEquals(1, s.rounds());
        assertEquals(0, s.ackedCount());
        assertEquals(0, s.shown(0));
        assertEquals(1, s.shown(1));
        assertFalse(s.tick(100_000));                 // the verification pass is ack-driven as well
        assertEquals(0, s.shown(0));
        s.onDone();
        assertTrue(s.finished());
        assertFalse(s.tick(200_000));
        assertFalse(s.onAck(Tone.A0, 200_001));
    }

    @Test public void latencyIsMeasured() {
        SlotScheduler s = new SlotScheduler(6, 2, 250, true);
        s.start(0);
        assertEquals(-1, s.latencyMs());
        s.onAck(Tone.A0, 300);
        assertEquals(300, s.latencyMs());
        s.onAck(Tone.A1, 500);
        assertEquals(270, s.latencyMs());            // 0.7 × 300 + 0.3 × 200
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
}
