package com.zmlworks.qrtransfer;

/**
 * Receiver side of the alternating-bit acknowledgment (v0.3.0): which tone to play for a decoded frame.
 * <p>The tone is a function of the chunk index alone ({@link Tone#toneOf}: slot = index &amp; 1, bit = (index &gt;&gt; 1) &amp; 1),
 * so it does not depend on where the code is in the camera image nor on how many codes the sender shows.
 * <ul>
 *   <li>A chunk is acknowledged as soon as it is decoded, whether new or a duplicate (the sender may have missed the
 *       earlier ack and is still showing it).</li>
 *   <li>Repeats are de-duplicated per <b>tone</b> (0.4.0; 0.3.x did it per slot = index parity): while the same chunk
 *       stays in view it is re-acknowledged only every {@link #REACK_MS} (in case that ack was lost too), not on every
 *       camera frame. Per tone, two codes of the same parity in view (the 0.4.0 sender's shared queue can show them)
 *       no longer re-trigger each other's tone on every frame; for the 0.3.x even/odd senders nothing changes.</li>
 *   <li>A late re-ack of a chunk the sender has already left is ignored by the sender (other bit, or its tone is
 *       not on screen).</li>
 * </ul>
 * The tones themselves (frequencies, {@link Tone#toneOf}) are unchanged since 0.3.0.
 */
public final class AckPolicy {
    public static final long REACK_MS = 700, DONE_REPEAT_MS = 2000;
    private final int[] last = {-1, -1, -1, -1};
    private final long[] at = {Long.MIN_VALUE / 2, Long.MIN_VALUE / 2, Long.MIN_VALUE / 2, Long.MIN_VALUE / 2};
    private long doneAt = Long.MIN_VALUE / 2;

    public static int slotOf(int index) { return index & 1; }

    /** @return tone id (Tone.A0..B1) to play now, or -1 for none */
    public int onFrame(int index, long nowMs) {
        int t = Tone.toneOf(index);
        if (index != last[t] || nowMs - at[t] >= REACK_MS) {
            last[t] = index;
            at[t] = nowMs;
            return t;
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
        java.util.Arrays.fill(last, -1);
        java.util.Arrays.fill(at, Long.MIN_VALUE / 2);
        doneAt = Long.MIN_VALUE / 2;
    }
}
