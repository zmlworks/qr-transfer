package com.zmlworks.qrtransfer;

import java.util.Arrays;

/**
 * Sender pacing for 1 or 2 QR codes shown at once (pure logic, time injected; mirrored line by line in
 * web/sender.html, checked by WebSchedulerParityTest).
 * <p>With 2 slots, slot 0 (A, top/left) owns the even chunks and slot 1 (B, bottom/right) the odd ones.
 * <p><b>Loop mode</b> (no microphone): every slot cycles through its chunks, one per period.
 * <p><b>Ack mode</b> (alternating-bit protocol, v0.3.0): each slot shows its chunks strictly in order and moves on
 * <em>only</em> when it hears the ack tone {@link Tone#toneOf}(shown chunk). Consecutive chunks of a slot carry
 * different bits, so a late or repeated ack for the previous chunk (other bit) is ignored as stale. There is no timeout:
 * an unacknowledged chunk simply stays on screen (the camera keeps retrying and the receiver re-acks periodically).
 * A slot whose chunks are all acknowledged shows nothing. The transfer ends on DONE. Only if every chunk was
 * acknowledged and still no DONE arrives within {@link #VERIFY_MS} (an ack was false) is everything shown again —
 * still advancing on acks only.
 */
public final class SlotScheduler {
    public static final long VERIFY_MS = 3000, SILENCE_WARN_MS = 5000, IDLE_WAKE_MS = 1000;

    public final int total, slots;
    public final long periodMs;
    public final boolean ackMode;

    private final boolean[] acked;
    private int ackedCount, rounds, goodAcks, staleAcks;
    private final int[] cur;
    private final long[] shownAt;
    private double latency = -1;
    private long allAckedAt = -1, lastAckAt = -1;
    private boolean finished;

    public SlotScheduler(int total, int slots, long periodMs, boolean ackMode) {
        if (total < 1 || slots < 1 || slots > 2) throw new IllegalArgumentException();
        this.total = total;
        this.slots = Math.min(slots, total);   // a 1-chunk transfer needs one code only
        this.periodMs = periodMs;
        this.ackMode = ackMode;
        acked = new boolean[total];
        cur = new int[this.slots];
        shownAt = new long[this.slots];
        Arrays.fill(cur, -1);
    }

    public void start(long now) {
        for (int s = 0; s < slots; s++) show(s, first(s), now);
    }

    private int first(int s) { return slots == 1 ? 0 : s; }

    /** chunk index shown in slot s, or -1 when the slot is idle (all its chunks acknowledged). */
    public int shown(int s) { return s < slots ? cur[s] : -1; }
    /** ack tone the slot is waiting for (Tone.A0..B1), or -1 */
    public int expectedTone(int s) { return s < slots && cur[s] >= 0 ? Tone.toneOf(cur[s]) : -1; }
    /** how long slot s has shown its current chunk */
    public long waitingMs(int s, long now) { return s < slots && cur[s] >= 0 ? now - shownAt[s] : -1; }

    public boolean isAcked(int i) { return acked[i]; }
    public int ackedCount() { return ackedCount; }
    public int rounds() { return rounds; }
    public int goodAcks() { return goodAcks; }
    public int staleAcks() { return staleAcks; }
    public boolean finished() { return finished; }
    /** smoothed time from showing a chunk to hearing its ack (ms), -1 before the first ack */
    public long latencyMs() { return Math.round(latency); }
    /** no matching ack for a while although something is shown (for a UI hint; nothing is skipped) */
    public boolean silent(long now) {
        if (!ackMode || finished) return false;
        for (int s = 0; s < slots; s++) if (cur[s] >= 0 && now - Math.max(shownAt[s], lastAckAt) >= SILENCE_WARN_MS) return true;
        return false;
    }

    private void show(int s, int i, long now) {
        cur[s] = i;
        shownAt[s] = now;
    }

    /** number of chunks owned by slot s */
    private int count(int s) { return (total - first(s) + slots - 1) / slots; }

    private void mark(int i) {
        if (i >= 0 && !acked[i]) { acked[i] = true; ackedCount++; }
    }

    /**
     * An ack tone (Tone.A0..B1) was heard.
     * @return true when the display changed (the tone matched a shown chunk's slot and bit); false = stale/foreign
     */
    public boolean onAck(int tone, long now) {
        if (!ackMode || finished || tone < 0 || tone > Tone.B1) return false;
        for (int s = 0; s < slots; s++) {
            if (cur[s] < 0 || Tone.toneOf(cur[s]) != tone) continue;
            mark(cur[s]);
            goodAcks++;
            long dt = now - shownAt[s];
            latency = latency < 0 ? dt : latency * 0.7 + dt * 0.3;
            lastAckAt = now;
            int n = cur[s] + slots;
            show(s, n < total ? n : -1, now);   // strictly the next one: the bit alternates
            if (ackedCount == total && allAckedAt < 0) allAckedAt = now;
            return true;
        }
        staleAcks++;
        return false;
    }

    /** Receiver confirmed the whole transfer. */
    public void onDone() { finished = true; }

    /**
     * Loop mode: advance slots whose period is up. Ack mode: never advances; only starts a verification pass
     * when everything was acknowledged but DONE did not come.
     * @return true when the display changed
     */
    public boolean tick(long now) {
        if (finished) return false;
        if (ackMode) {
            if (ackedCount == total && allAckedAt >= 0 && now - allAckedAt >= VERIFY_MS) {
                Arrays.fill(acked, false);
                ackedCount = 0;
                allAckedAt = -1;
                rounds++;
                for (int s = 0; s < slots; s++) show(s, first(s), now);
                return true;
            }
            return false;
        }
        boolean changed = false;
        for (int s = 0; s < slots; s++) {
            if (now - shownAt[s] >= periodMs) {
                int n = cur[s] + slots;
                show(s, n < total ? n : first(s), now);
                changed = true;
            }
        }
        return changed;
    }

    /** Earliest time tick() can change something (for the timer). */
    public long nextDeadline(long now) {
        long d;
        if (ackMode) d = allAckedAt >= 0 ? allAckedAt + VERIFY_MS : now + IDLE_WAKE_MS;
        else {
            d = Long.MAX_VALUE;
            for (int s = 0; s < slots; s++) d = Math.min(d, shownAt[s] + periodMs);
        }
        return Math.max(d, now + 10);
    }

    /** Manual step (paused): move slot s by d positions among its own chunks. */
    public void step(int s, int d, long now) {
        if (s >= slots) return;
        int c = cur[s] < 0 ? first(s) : cur[s];
        int count = count(s);
        int pos = (c - first(s)) / slots;
        pos = ((pos + d) % count + count) % count;
        show(s, first(s) + pos * slots, now);
    }
}
