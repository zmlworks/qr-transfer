package com.zmlworks.qrtransfer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AckPolicyTest {
    @Test public void slotIsParity() {
        assertEquals(0, AckPolicy.slotOf(0));
        assertEquals(1, AckPolicy.slotOf(1));
        assertEquals(0, AckPolicy.slotOf(10));
        assertEquals(1, AckPolicy.slotOf(7));
    }

    @Test public void acksCarrySlotAndBit() {
        AckPolicy p = new AckPolicy();
        assertEquals(Tone.A0, p.onFrame(0, 0));
        assertEquals(Tone.B0, p.onFrame(1, 0));      // same camera frame, other slot
        assertEquals(Tone.A1, p.onFrame(2, 100));
        assertEquals(Tone.B1, p.onFrame(3, 100));
        assertEquals(Tone.A0, p.onFrame(4, 200));
        assertEquals(Tone.B0, p.onFrame(5, 200));
    }

    @Test public void dedupesRepeatsAndReacksPeriodically() {
        AckPolicy p = new AckPolicy();
        assertEquals(Tone.A0, p.onFrame(4, 0));
        assertEquals(Tone.B0, p.onFrame(5, 0));
        assertEquals(-1, p.onFrame(4, 33));            // same code still in view: no new tone
        assertEquals(-1, p.onFrame(5, 66));
        assertEquals(Tone.A1, p.onFrame(6, 100));      // sender advanced slot A
        assertEquals(-1, p.onFrame(5, 500));
        assertEquals(Tone.B0, p.onFrame(5, AckPolicy.REACK_MS));   // still shown: its ack was lost → re-ack
        assertEquals(-1, p.onFrame(5, AckPolicy.REACK_MS + 300));
        assertEquals(Tone.B0, p.onFrame(5, 2 * AckPolicy.REACK_MS));
    }

    @Test public void duplicatesAreAcknowledged() {
        AckPolicy p = new AckPolicy();
        assertEquals(Tone.A0, p.onFrame(0, 0));
        assertEquals(Tone.A1, p.onFrame(2, 50));
        assertEquals(Tone.A0, p.onFrame(0, 100));      // chunk 0 again (already received): ack again
    }

    @Test public void doneRepeatsAtMostEveryTwoSeconds() {
        AckPolicy p = new AckPolicy();
        assertTrue(p.shouldRepeatDone(0));
        assertFalse(p.shouldRepeatDone(1999));
        assertTrue(p.shouldRepeatDone(2000));
        p.reset();
        assertTrue(p.shouldRepeatDone(2001));
        assertEquals(Tone.B1, p.onFrame(3, 2001));
    }
}
