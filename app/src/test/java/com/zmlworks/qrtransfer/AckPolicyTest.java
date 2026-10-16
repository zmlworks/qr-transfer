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

    @Test public void beepsOncePerChangeAndAgainAfterReack() {
        AckPolicy p = new AckPolicy();
        assertEquals(0, p.onFrame(4, 0));
        assertEquals(1, p.onFrame(5, 0));      // same camera frame, other slot
        assertEquals(-1, p.onFrame(4, 33));    // still the same code in view
        assertEquals(-1, p.onFrame(5, 66));
        assertEquals(0, p.onFrame(6, 100));    // sender advanced slot A
        assertEquals(-1, p.onFrame(5, 500));
        assertEquals(1, p.onFrame(5, 1000));   // sender keeps showing it: its ack was lost → re-ack
        assertEquals(-1, p.onFrame(5, 1500));
        assertEquals(1, p.onFrame(5, 2000));
    }

    @Test public void duplicatesAreAcknowledged() {
        AckPolicy p = new AckPolicy();
        assertEquals(0, p.onFrame(0, 0));
        assertEquals(0, p.onFrame(2, 50));
        assertEquals(0, p.onFrame(0, 100));    // chunk 0 again (already received): still ack so the sender moves on
    }

    @Test public void doneRepeatsAtMostEveryTwoSeconds() {
        AckPolicy p = new AckPolicy();
        assertTrue(p.shouldRepeatDone(0));
        assertFalse(p.shouldRepeatDone(1999));
        assertTrue(p.shouldRepeatDone(2000));
        p.reset();
        assertTrue(p.shouldRepeatDone(2001));
        assertEquals(1, p.onFrame(3, 2001));
    }
}
