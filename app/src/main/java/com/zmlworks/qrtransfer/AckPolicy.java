package com.zmlworks.qrtransfer;

/**
 * Receiver side: which acknowledgment tone to play for a decoded frame.
 * The slot is the chunk number's parity (even → A, odd → B), so it is known from the frame itself, independent of
 * where the code is in the camera image. A tone is played when the slot shows a different chunk than last time,
 * or again after {@link #REACK_MS} if the sender keeps showing the same one (its ack was not heard → resend).
 * Duplicates (already received) are acknowledged too, otherwise the sender could not move on.
 */
public final class AckPolicy {
    public static final long REACK_MS = 1000, DONE_REPEAT_MS = 2000;
    private final int[] last = {-1, -1};
    private final long[] at = {Long.MIN_VALUE / 2, Long.MIN_VALUE / 2};
    private long doneAt = Long.MIN_VALUE / 2;

    public static int slotOf(int index) { return index & 1; }

    /** @return slot whose tone to play now (0/1), or -1 for none */
    public int onFrame(int index, long nowMs) {
        int s = slotOf(index);
        if (index != last[s] || nowMs - at[s] >= REACK_MS) {
            last[s] = index;
            at[s] = nowMs;
            return s;
        }
        return -1;
    }

    /** After completion the sender may not have heard DONE; repeat it while its codes are still seen. */
    public boolean shouldRepeatDone(long nowMs) {
        if (nowMs - doneAt < DONE_REPEAT_MS) return false;
        doneAt = nowMs;
        return true;
    }

    public void reset() {
        last[0] = last[1] = -1;
        at[0] = at[1] = Long.MIN_VALUE / 2;
        doneAt = Long.MIN_VALUE / 2;
    }
}
