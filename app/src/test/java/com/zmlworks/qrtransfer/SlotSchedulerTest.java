package com.zmlworks.qrtransfer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Sender state machine (0.4.0 shared queue): lost acks, lost frames, duplicate and stale acks, quarantine, loop mode. */
public class SlotSchedulerTest {
    static final long Q = SlotScheduler.QUARANTINE_MS, QS = SlotScheduler.QUARANTINE_SAME_MS;

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
        assertTrue(s.onAck(Tone.A0, 400));            // 0
        assertTrue(s.onAck(Tone.B0, 420));            // 1
        assertTrue(s.onAck(Tone.A1, 800));            // 2
        assertTrue(s.onAck(Tone.B1, 820));            // 3
        assertEquals(4, s.shown(0));
        assertEquals(5, s.shown(1));
        for (long t = 830; t < 3_600_000; t = s.nextDeadline(t)) assertFalse(s.tick(t));
        assertEquals(4, s.shown(0));
        assertEquals(5, s.shown(1));
        assertTrue(s.silent(3_600_000));
        assertEquals(Tone.A0, s.expectedTone(0));
        assertEquals(Tone.B0, s.expectedTone(1));
    }

    @Test public void startsLikeTheOldLanesAndAdvancesOnlyOnAShownTone() {
        SlotScheduler s = new SlotScheduler(10, 2, 250, true);
        s.start(0);
        assertEquals(0, s.shown(0));
        assertEquals(1, s.shown(1));
        assertFalse(s.onAck(Tone.A1, 100));           // no shown chunk has this tone
        assertFalse(s.onAck(Tone.B1, 110));
        assertEquals(2, s.staleAcks());
        assertTrue(s.onAck(Tone.B0, 300));            // B only
        assertEquals(0, s.shown(0));
        assertEquals(3, s.shown(1));                  // next odd (other parity than A's chunk), tone B1
        assertTrue(s.isAcked(1));
        assertFalse(s.isAcked(0));
        assertTrue(s.onAck(Tone.A0, 400));
        assertEquals(2, s.shown(0));
        assertEquals(Tone.A1, s.expectedTone(0));
    }

    /** The user's 0.3.1 report: the bottom lane finished first and the top lane worked alone. Now B helps. */
    @Test public void aFreeSlotTakesTheNextChunkFromTheSharedQueue() {
        SlotScheduler s = new SlotScheduler(8, 2, 250, true);
        s.start(0);                                    // A: 0, B: 1
        long t = 0;
        // A's chunk 0 is hard to read; B gets every odd chunk quickly
        assertTrue(s.onAck(Tone.B0, t += 500));        // 1 → 3
        assertEquals(3, s.shown(1));
        assertTrue(s.onAck(Tone.B1, t += 500));        // 3 → 5
        assertTrue(s.onAck(Tone.B0, t += 500));        // 5 → 7
        assertTrue(s.onAck(Tone.B1, t += 500));        // 7 → odd chunks done: B takes an even one (0.3.x: idle)
        assertEquals(0, s.shown(0));
        assertEquals(2, s.shown(1));                   // tone A1 ≠ A0 of chunk 0
        assertTrue(s.onAck(Tone.A1, t += 500));        // 2 → 6 (A1 again after the same-parity quarantine)…
        assertEquals(-1, s.shown(1));                  // …4 has A0 = A's tone and 6's A1 is quarantined
        assertFalse(s.tick(t + QS - 1));
        assertTrue(s.tick(t + QS));
        assertEquals(6, s.shown(1));
        assertNotEquals(s.expectedTone(0), s.expectedTone(1));
        assertTrue(s.onAck(Tone.A0, t += 2000));       // finally 0 → slot A takes 4 (A0 is quarantined only after 0 left;
        assertEquals(-1, s.shown(0));                  //   4 shares the just-acked tone: waits)
        assertTrue(s.tick(t + QS));
        assertEquals(4, s.shown(0));
        assertTrue(s.onAck(Tone.A1, t += 1000));       // 6
        assertTrue(s.onAck(Tone.A0, t += 1000));       // 4
        assertEquals(8, s.ackedCount());
    }

    @Test public void staleAndDuplicateAcksAreIgnored() {
        SlotScheduler s = new SlotScheduler(10, 2, 250, true);
        s.start(0);
        assertTrue(s.onAck(Tone.A0, 300));            // 0 acked → shows 2 (A1)
        assertFalse(s.onAck(Tone.A0, 310));           // duplicate detection of the same ack burst
        assertFalse(s.onAck(Tone.A0, 900));           // receiver re-acked 0 (it still saw it): stale
        assertEquals(2, s.shown(0));
        assertTrue(s.onAck(Tone.A1, 1000));           // 2 acked → 4 (A0 again; its quarantine ended at 300 + Q)
        assertEquals(4, s.shown(0));
        assertFalse(s.onAck(Tone.A1, 1010));          // late duplicate for 2: its tone is not on screen
        assertEquals(4, s.shown(0));
        assertEquals(2, s.goodAcks());
        assertEquals(3, s.staleAcks());
        assertEquals(2, s.ackedCount());
    }

    @Test public void tooEarlyAckIsALateDuplicate() {
        // a 0.3.1 receiver may replay the tone of the chunk shown before; a "reply" faster than any real round trip
        // must not confirm the chunk that just appeared
        SlotScheduler s = new SlotScheduler(10, 2, 250, true);
        s.start(0);
        assertTrue(s.onAck(Tone.A0, 400));            // A: 0 → 2 (A1) at 400
        assertFalse(s.onAck(Tone.A1, 400 + SlotScheduler.MIN_ACK_MS - 1));
        assertEquals(2, s.shown(0));
        assertTrue(s.onAck(Tone.A1, 400 + SlotScheduler.MIN_ACK_MS));
    }

    @Test public void justLeftToneIsQuarantined() {
        // 1 slot: 0 A0, 1 B0, 2 A1, 3 B1, 4 A0. Chunk 1 is put back (requeue): its tone B0 is not reused for
        // QUARANTINE_SAME_MS, so a late B0 meant for the first showing cannot confirm the next B0 chunk early.
        SlotScheduler s = new SlotScheduler(5, 1, 250, true);
        s.start(0);
        assertTrue(s.onAck(Tone.A0, 300));
        assertEquals(1, s.shown(0));
        assertTrue(s.requeue(0, 400));                // 1 back to the queue → 2
        assertEquals(2, s.shown(0));
        assertTrue(s.onAck(Tone.A1, 700));
        assertEquals(3, s.shown(0));                  // 1 (B0) still in quarantine
        assertFalse(s.onAck(Tone.B0, 750));           // late B0 for the first showing of 1: stale
        assertTrue(s.onAck(Tone.B1, 1000));
        assertEquals(4, s.shown(0));                  // 1's B0 is free at 400 + QS = 1100: 4 first
        assertTrue(s.onAck(Tone.A0, 1300));
        assertEquals(1, s.shown(0));
        assertTrue(s.onAck(Tone.B0, 1600));
        assertEquals(5, s.ackedCount());
        assertTrue(QS > Q);
    }

    @Test public void twoShownChunksNeverShareATone() {
        Random r = new Random(3);
        for (int total : new int[]{2, 3, 5, 9, 33}) {
            SlotScheduler s = new SlotScheduler(total, 2, 250, true);
            s.setMirror(r.nextBoolean());
            s.start(0);
            long t = 0;
            for (int k = 0; k < 3000 && !s.finished(); k++) {
                t += r.nextInt(300);
                int a = s.shown(0), b = s.shown(1);
                if (a >= 0 && b >= 0 && a != b) assertNotEquals(Tone.toneOf(a), Tone.toneOf(b));
                if (a >= 0 && b >= 0 && a == b) assertTrue(s.isMirror(0) || s.isMirror(1));
                int e = s.expectedTone(r.nextInt(2));
                if (r.nextInt(3) == 0) s.tick(t);
                else if (r.nextInt(20) == 0) s.requeue(r.nextInt(2), t);
                else s.onAck(e >= 0 && r.nextBoolean() ? e : r.nextInt(4), t);
            }
        }
    }

    @Test public void lostAckKeepsChunkUntilReack() {
        SlotScheduler s = new SlotScheduler(6, 2, 250, true);
        s.start(0);
        assertFalse(s.tick(5000));
        assertEquals(0, s.shown(0));
        assertTrue(s.onAck(Tone.A0, 5000 + AckPolicy.REACK_MS));
        assertEquals(2, s.shown(0));
    }

    @Test public void lostFramesJustWait() {
        // the camera misses chunk 3 for a long time: it stays on screen, the other slot finishes the rest
        SlotScheduler s = new SlotScheduler(6, 2, 250, true);
        s.start(0);
        s.onAck(Tone.A0, 400); s.onAck(Tone.B0, 400);   // 0, 1 → A: 2, B: 3
        assertEquals(2, s.shown(0));
        assertEquals(3, s.shown(1));
        s.onAck(Tone.A1, 800);                          // 2 → 4
        s.onAck(Tone.A0, 1200);                         // 4 → 5 (B0, the last one)
        assertEquals(5, s.shown(0));
        s.onAck(Tone.B0, 1600);                         // 5 → A idle
        assertEquals(-1, s.shown(0));
        assertEquals(3, s.shown(1));
        for (long t = 1700; t < 60_000; t += 100) assertFalse(s.tick(t));
        assertEquals(3, s.shown(1));
        assertTrue(s.onAck(Tone.B1, 60_000));
        assertEquals(-1, s.shown(1));
        assertEquals(6, s.ackedCount());
    }

    @Test public void mirrorShowsTheLastChunkTwice() {
        SlotScheduler s = new SlotScheduler(3, 2, 250, true);
        s.setMirror(true);
        s.start(0);                                     // A: 0, B: 1
        s.onAck(Tone.B0, 400);                          // B → 2 (A1; even, but the only one left)
        assertEquals(2, s.shown(1));
        s.onAck(Tone.A0, 800);                          // A: nothing left → mirrors B's chunk 2
        assertEquals(2, s.shown(0));
        assertTrue(s.isMirror(0));
        assertFalse(s.isMirror(1));
        assertTrue(s.onAck(Tone.A1, 1200));             // either copy read → both done
        assertEquals(-1, s.shown(0));
        assertEquals(-1, s.shown(1));
        assertEquals(3, s.ackedCount());
    }

    @Test public void requeuePutsAnUnreadChunkBack() {
        SlotScheduler s = new SlotScheduler(6, 2, 250, true);
        s.start(0);                                     // A: 0, B: 1
        assertTrue(s.requeue(0, 3000));                 // 0 unread for long → A takes 2, 0 goes back to the queue
        assertEquals(2, s.shown(0));
        assertEquals(1, s.requeues());
        assertFalse(s.isAcked(0));
        assertFalse(s.onAck(Tone.A0, 3100));            // late ack of 0: its tone is off screen → stale, 0 stays queued
        assertFalse(s.isAcked(0));
        s.onAck(Tone.A1, 4000);                         // 2 → 0 again (lowest queued, A0 free again)
        assertEquals(0, s.shown(0));
        assertTrue(s.onAck(Tone.A0, 4500));
        assertTrue(s.isAcked(0));
        // nothing else to show → requeue keeps the chunk
        SlotScheduler one = new SlotScheduler(1, 1, 250, true);
        one.start(0);
        assertFalse(one.requeue(0, 5000));
        assertEquals(0, one.shown(0));
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
        assertEquals(-1, s.shown(1));
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
        s.onAck(Tone.A1, 700);
        assertEquals(330, s.latencyMs());            // 0.7 × 300 + 0.3 × 400
    }

    @Test public void manualStep() {
        SlotScheduler s = new SlotScheduler(5, 2, 250, false);   // loop: A: 0 2 4, B: 1 3
        s.start(0);
        s.step(0, -1, 10);
        assertEquals(4, s.shown(0));
        s.step(1, 1, 10);
        assertEquals(3, s.shown(1));
        s.step(1, 1, 10);
        assertEquals(1, s.shown(1));
        SlotScheduler a = new SlotScheduler(9, 2, 250, true);    // ack: any chunk, never the other slot's tone
        a.start(0);                                               // A: 0 (A0), B: 1 (B0)
        a.step(0, 1, 10);
        assertEquals(2, a.shown(0));
        a.step(0, 1, 10);
        assertEquals(3, a.shown(0));
        a.step(0, 1, 10);
        assertEquals(4, a.shown(0));
        a.step(0, 1, 10);
        assertEquals(6, a.shown(0));                  // 5 has B0 = B's tone
    }
}
