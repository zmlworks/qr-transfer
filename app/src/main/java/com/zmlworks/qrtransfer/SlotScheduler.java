package com.zmlworks.qrtransfer;

import java.util.Arrays;

/**
 * Sender pacing for 1 or 2 QR codes shown at once (pure logic, time injected; mirrored in web/sender.html).
 * <p>With 2 slots, slot 0 (A, top/left) owns the even chunks and slot 1 (B, bottom/right) the odd ones, matching the
 * receiver's tone choice ({@link AckPolicy}). With 1 slot, either tone acknowledges the single code.
 * <p>Timed mode (no microphone): every slot cycles through its chunks once per period.
 * <p>Ack mode: a slot advances as soon as its tone is heard (adaptive pacing) or after an adaptive timeout
 * (≈ 2.5 × measured ack latency, clamped) — the unacknowledged chunk is resent in a later pass. A tone heard within
 * {@link #GUARD_MS} of a switch is credited to the previous chunk (it was already on its way). When every chunk is
 * acknowledged but no DONE arrives within {@link #VERIFY_MS}, everything is sent again (an ack may have been false).
 */
public final class SlotScheduler {
    public static final long GUARD_MS = 150, MIN_TIMEOUT_MS = 500, MAX_TIMEOUT_MS = 4000, INIT_LATENCY_MS = 600,
            VERIFY_MS = 2500;

    public final int total, slots;
    public final long periodMs;
    public final boolean ackMode;

    private final boolean[] acked;
    private int ackedCount, rounds;
    private final int[] cur, prev;
    private final long[] shownAt;
    private double latency = INIT_LATENCY_MS;
    private long allAckedAt = -1;
    private boolean finished;

    public SlotScheduler(int total, int slots, long periodMs, boolean ackMode) {
        if (total < 1 || slots < 1 || slots > 2) throw new IllegalArgumentException();
        this.total = total;
        this.slots = Math.min(slots, total);   // a 1-chunk transfer needs one code only
        this.periodMs = periodMs;
        this.ackMode = ackMode;
        acked = new boolean[total];
        cur = new int[this.slots];
        prev = new int[this.slots];
        shownAt = new long[this.slots];
        Arrays.fill(cur, -1);
        Arrays.fill(prev, -1);
    }

    public void start(long now) {
        for (int s = 0; s < slots; s++) show(s, first(s), now);
    }

    private boolean owns(int s, int i) { return slots == 1 || i % slots == s; }

    private int first(int s) { return slots == 1 ? 0 : s; }

    /** chunk index shown in slot s, or -1 when the slot is idle (all its chunks acknowledged). */
    public int shown(int s) { return s < slots ? cur[s] : -1; }

    public boolean isAcked(int i) { return acked[i]; }
    public int ackedCount() { return ackedCount; }
    public int rounds() { return rounds; }
    public boolean finished() { return finished; }
    public long latencyMs() { return Math.round(latency); }

    /** Time a slot may wait for its tone before moving on. */
    public long timeoutMs() {
        if (!ackMode) return periodMs;
        long t = Math.round(2.5 * latency + 200);
        return Math.max(Math.max(MIN_TIMEOUT_MS, periodMs), Math.min(MAX_TIMEOUT_MS, t));
    }

    private void show(int s, int i, long now) {
        if (cur[s] >= 0) prev[s] = cur[s];
        cur[s] = i;
        shownAt[s] = now;
    }

    /** number of chunks owned by slot s */
    private int count(int s) { return (total - first(s) + slots - 1) / slots; }

    /** Next chunk of slot s after `from` (cyclic, from = -1 → its first); in ack mode only unacknowledged ones
     *  (may return `from` itself when it is the only one left); -1 if none. */
    private int next(int s, int from) {
        int n = count(s), pos = from < 0 ? -1 : (from - first(s)) / slots;
        for (int k = 1; k <= n; k++) {
            int j = first(s) + ((pos + k) % n) * slots;
            if (!ackMode || !acked[j]) return j;
        }
        return -1;
    }

    private void mark(int i) {
        if (i >= 0 && !acked[i]) { acked[i] = true; ackedCount++; }
    }

    /**
     * A tone for slot `tone` (0 = A, 1 = B) was heard.
     * @return true when the display changed
     */
    public boolean onAck(int tone, long now) {
        if (!ackMode || finished) return false;
        int s = slots == 1 ? 0 : tone;
        if (s >= slots) return false;
        if (cur[s] < 0) {   // idle slot: a late tone for what it showed last
            if (prev[s] >= 0 && now - shownAt[s] < GUARD_MS) mark(prev[s]);
            return checkAll(now);
        }
        long dt = now - shownAt[s];
        if (dt < GUARD_MS && prev[s] >= 0) {   // belongs to the previous chunk
            mark(prev[s]);
            return checkAll(now);
        }
        mark(cur[s]);
        latency = latency * 0.7 + Math.min(dt, MAX_TIMEOUT_MS) * 0.3;
        show(s, next(s, cur[s]), now);
        checkAll(now);
        return true;
    }

    private boolean checkAll(long now) {
        if (ackedCount == total && allAckedAt < 0) allAckedAt = now;
        return false;
    }

    /** Receiver confirmed the whole transfer. */
    public void onDone() { finished = true; }

    /**
     * Advance slots whose time is up.
     * @return true when the display changed
     */
    public boolean tick(long now) {
        if (finished) return false;
        boolean changed = false;
        if (ackMode && ackedCount == total) {
            if (allAckedAt < 0) allAckedAt = now;
            if (now - allAckedAt >= VERIFY_MS) {   // no DONE: send everything again
                Arrays.fill(acked, false);
                ackedCount = 0;
                allAckedAt = -1;
                rounds++;
                for (int s = 0; s < slots; s++) show(s, next(s, cur[s] >= 0 ? cur[s] : -1), now);
                return true;
            }
        }
        long t = timeoutMs();
        for (int s = 0; s < slots; s++) {
            if (cur[s] < 0) {
                // idle; wake up if something of ours became unacknowledged again
                int n = ackMode ? next(s, -1) : -1;
                if (n >= 0) { show(s, n, now); changed = true; }
                continue;
            }
            if (now - shownAt[s] >= t) {
                int n = next(s, cur[s]);
                show(s, n, now);   // n may equal cur (only one left): resend, the receiver re-acks after a while
                changed = true;
            }
        }
        return changed;
    }

    /** Earliest time tick() can change something (for the timer). */
    public long nextDeadline(long now) {
        long t = timeoutMs(), d = now + Math.max(t, 50);
        for (int s = 0; s < slots; s++) if (cur[s] >= 0) d = Math.min(d, shownAt[s] + t);
        if (ackMode && allAckedAt >= 0) d = Math.min(d, allAckedAt + VERIFY_MS);
        return Math.max(d, now + 10);
    }

    /** Manual step (paused): move slot s by d positions among its own chunks (ignores acks). */
    public void step(int s, int d, long now) {
        if (s >= slots) return;
        int c = cur[s] < 0 ? first(s) : cur[s];
        int count = count(s);
        int pos = (c - first(s)) / slots;
        pos = ((pos + d) % count + count) % count;
        show(s, first(s) + pos * slots, now);
    }
}
