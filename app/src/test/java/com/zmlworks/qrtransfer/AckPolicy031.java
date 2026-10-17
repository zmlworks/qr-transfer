package com.zmlworks.qrtransfer;

/** 0.3.1 receiver ack policy (per-slot de-duplication), test-only baseline for TransferSimulationTest.
 *
 * Receiver side of the alternating-bit acknowledgment (v0.3.0): which tone to play for a decoded frame.
 * <p>The tone is a function of the chunk index alone ({@link Tone#toneOf}: slot = index &amp; 1, bit = (index &gt;&gt; 1) &amp; 1),
 * so it does not depend on where the code is in the camera image nor on how many codes the sender shows.
 * <ul>
 *   <li>A chunk is acknowledged as soon as it is decoded, whether new or a duplicate (the sender may have missed the
 *       earlier ack and is still showing it).</li>
 *   <li>Repeats are de-duplicated per slot: while the same chunk stays in view it is re-acknowledged only every
 *       {@link #REACK_MS} (in case that ack was lost too), not on every camera frame.</li>
 *   <li>A late re-ack of a chunk the sender has already left carries the other bit, so the sender ignores it.</li>
 * </ul>
 */
final class AckPolicy031 {
    public static final long REACK_MS = 700, DONE_REPEAT_MS = 2000;
    private final int[] last = {-1, -1};
    private final long[] at = {Long.MIN_VALUE / 2, Long.MIN_VALUE / 2};
    private long doneAt = Long.MIN_VALUE / 2;

    public static int slotOf(int index) { return index & 1; }

    /** @return tone id (Tone.A0..B1) to play now, or -1 for none */
    public int onFrame(int index, long nowMs) {
        int s = slotOf(index);
        if (index != last[s] || nowMs - at[s] >= REACK_MS) {
            last[s] = index;
            at[s] = nowMs;
            return Tone.toneOf(index);
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
