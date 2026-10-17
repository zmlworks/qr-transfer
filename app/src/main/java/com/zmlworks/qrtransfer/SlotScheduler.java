package com.zmlworks.qrtransfer;

import java.util.Arrays;

/**
 * Sender pacing for 1 or 2 QR codes shown at once (pure logic, time injected; mirrored line by line in
 * web/sender.html, checked by WebSchedulerParityTest).
 * <p><b>Loop mode</b> (no microphone): slot 0 (A, top/left) cycles through the even chunks and slot 1 (B) through the
 * odd ones, one per period (unchanged since 0.2.0).
 * <p><b>Ack mode</b> (v0.4.0, shared queue): both slots take their next chunk from ONE common queue of not-yet-acknowledged
 * chunks, so whichever slot is acknowledged first takes the next chunk (0.3.x split the chunks into fixed even/odd
 * lanes, and the slot whose lane finished first then stood idle). A slot moves on <em>only</em> when it hears the ack
 * tone {@link Tone#toneOf}(its chunk) — the receiver's tones are unchanged (they encode index mod 4), so 0.3.x
 * receivers work as before. To keep every ack unambiguous:
 * <ul>
 *   <li>the two chunks on screen never share a tone (a tone names exactly one shown chunk);</li>
 *   <li>a tone that just left the screen (acked or put back) is not reused for {@link #QUARANTINE_MS}
 *       ({@link #QUARANTINE_SAME_MS} when it was ever shown next to a chunk of the same parity — a 0.3.x receiver then re-queues
 *       their tones alternately, so a late duplicate can still be in its tone queue), so a late/duplicate ack of the
 *       old chunk cannot be taken for the new one; such acks are counted as stale and ignored;</li>
 *   <li>an ack heard less than {@link #MIN_ACK_MS} after the chunk appeared is too early to be real (screen → camera →
 *       decode → speaker → microphone takes longer) and is ignored as stale: it is a late duplicate meant for the
 *       chunk shown before (a 0.3.x receiver can re-send a tone when a delayed decode of the old chunk arrives after
 *       the new one); the receiver re-acks the new chunk {@link AckPolicy#REACK_MS} later if it was real;</li>
 *   <li>the next chunk preferably has the other parity than the chunk in the other slot (the same pairing as 0.3.x,
 *       fewest re-acks from a 0.3.x receiver); same-parity pairs are used only when no other chunk is left.</li>
 * </ul>
 * No timeout ever skips a chunk. {@link #requeue} lets the UI put a long-unreadable chunk back into the queue (it is
 * shown again later) so the slot can serve other chunks meanwhile. With {@link #setMirror} an otherwise idle slot
 * shows the other slot's chunk too (the UI draws it with another mask) — the last chunks get two chances per frame.
 * The transfer ends on DONE. If every chunk was acknowledged and still no DONE arrives within {@link #VERIFY_MS}
 * (an ack was false), all chunks go back into the queue — still advancing on acks only.
 */
public final class SlotScheduler {
    public static final long VERIFY_MS = 3000, SILENCE_WARN_MS = 5000, IDLE_WAKE_MS = 1000,
            QUARANTINE_MS = 200, QUARANTINE_SAME_MS = 700, MIN_ACK_MS = 250;

    public final int total, slots;
    public final long periodMs;
    public final boolean ackMode;

    private final boolean[] acked;
    private int ackedCount, rounds, goodAcks, staleAcks, requeues;
    private final int[] cur;
    private final long[] shownAt;
    private final boolean[] mirrored;
    /** the chunk in slot s has been on screen together with a different chunk of the same parity */
    private final boolean[] paired;
    private final long[] toneFreeAt = new long[4];
    private double latency = -1;
    private long allAckedAt = -1, lastAckAt = -1;
    private boolean finished, mirror;

    public SlotScheduler(int total, int slots, long periodMs, boolean ackMode) {
        if (total < 1 || slots < 1 || slots > 2) throw new IllegalArgumentException();
        this.total = total;
        this.slots = Math.min(slots, total);   // a 1-chunk transfer needs one code only
        this.periodMs = periodMs;
        this.ackMode = ackMode;
        acked = new boolean[total];
        cur = new int[this.slots];
        shownAt = new long[this.slots];
        mirrored = new boolean[this.slots];
        paired = new boolean[this.slots];
        Arrays.fill(cur, -1);
    }

    /** ack mode: let an idle slot show the other slot's chunk as well (default off) */
    public void setMirror(boolean on) { mirror = on; }

    public void start(long now) {
        if (ackMode) fillAll(now);
        else for (int s = 0; s < slots; s++) show(s, first(s), now);
    }

    private int first(int s) { return slots == 1 ? 0 : s; }

    /** chunk index shown in slot s, or -1 when the slot is idle. */
    public int shown(int s) { return s < slots ? cur[s] : -1; }
    /** true when slot s shows the other slot's chunk (see {@link #setMirror}) */
    public boolean isMirror(int s) { return s < slots && mirrored[s]; }
    /** ack tone the slot is waiting for (Tone.A0..B1), or -1 */
    public int expectedTone(int s) { return s < slots && cur[s] >= 0 ? Tone.toneOf(cur[s]) : -1; }
    /** how long slot s has shown its current chunk */
    public long waitingMs(int s, long now) { return s < slots && cur[s] >= 0 ? now - shownAt[s] : -1; }

    public boolean isAcked(int i) { return acked[i]; }
    public int ackedCount() { return ackedCount; }
    public int rounds() { return rounds; }
    public int goodAcks() { return goodAcks; }
    public int staleAcks() { return staleAcks; }
    public int requeues() { return requeues; }
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
        mirrored[s] = false;
        paired[s] = false;
        int o = slots == 2 ? cur[1 - s] : -1;
        if (i >= 0 && o >= 0 && o != i && ((o ^ i) & 1) == 0) { paired[0] = true; paired[1] = true; }
    }

    /** number of chunks owned by slot s in loop mode */
    private int count(int s) { return (total - first(s) + slots - 1) / slots; }

    private void mark(int i) {
        if (i >= 0 && !acked[i]) { acked[i] = true; ackedCount++; }
    }

    private boolean onScreen(int i) {
        for (int s = 0; s < slots; s++) if (cur[s] == i) return true;
        return false;
    }

    /**
     * Next chunk for slot s from the shared queue: the lowest not-acked chunk that is not on screen, whose tone differs
     * from the other slot's and is not in quarantine; preferably of the other parity than the other slot's chunk.
     * @param skip a chunk index not to take (-1 = none)
     */
    private int pick(int s, int skip, long now) {
        int other = slots == 2 ? cur[1 - s] : -1;
        int otherTone = other >= 0 ? Tone.toneOf(other) : -1;
        int fallback = -1;
        for (int i = 0; i < total; i++) {
            if (acked[i] || i == skip || onScreen(i)) continue;
            int t = Tone.toneOf(i);
            if (t == otherTone || now < toneFreeAt[t]) continue;
            if (other < 0 || ((i ^ other) & 1) == 1) return i;
            if (fallback < 0) fallback = i;
        }
        return fallback;
    }

    /** fill slot s (idle or mirroring) with a queued chunk, else mirror the other slot's chunk, else idle. */
    private boolean fill(int s, int skip, long now) {
        int p = pick(s, skip, now);
        if (p >= 0) { show(s, p, now); return true; }
        if (cur[s] >= 0 && !mirrored[s]) return false;
        int other = slots == 2 ? cur[1 - s] : -1;
        if (mirror && other >= 0 && !mirrored[1 - s] && !acked[other]) {
            if (cur[s] == other && mirrored[s]) return false;
            cur[s] = other;
            shownAt[s] = now;
            mirrored[s] = true;
            paired[s] = false;
            return true;
        }
        if (cur[s] < 0) return false;
        show(s, -1, now);
        return true;
    }

    private void fillAll(long now) {
        for (int s = 0; s < slots; s++) if (cur[s] < 0 || mirrored[s]) fill(s, -1, now);
    }

    /**
     * The chunk in slot s leaves the screen: its tone may not be reused for a while — longer if it was ever shown next
     * to a chunk of the same parity (a 0.3.x receiver then keeps re-queueing both tones).
     */
    private void quarantine(int s, long now) {
        int i = cur[s];
        if (i < 0) return;
        int t = Tone.toneOf(i);
        toneFreeAt[t] = Math.max(toneFreeAt[t], now + (paired[s] ? QUARANTINE_SAME_MS : QUARANTINE_MS));
    }

    /**
     * An ack tone (Tone.A0..B1) was heard.
     * @return true when the display changed (the tone matched a shown chunk); false = stale/foreign
     */
    public boolean onAck(int tone, long now) {
        if (!ackMode || finished || tone < 0 || tone > Tone.B1) return false;
        int c = -1;
        long since = now;
        for (int s = 0; s < slots; s++) {
            if (cur[s] < 0 || Tone.toneOf(cur[s]) != tone) continue;
            c = cur[s];
            since = Math.min(since, shownAt[s]);
        }
        if (c < 0 || now - since < MIN_ACK_MS) {
            staleAcks++;
            return false;
        }
        mark(c);
        goodAcks++;
        long dt = now - since;
        latency = latency < 0 ? dt : latency * 0.7 + dt * 0.3;
        lastAckAt = now;
        for (int s = 0; s < slots; s++) if (cur[s] == c) { quarantine(s, now); show(s, -1, now); }
        fillAll(now);
        if (ackedCount == total && allAckedAt < 0) allAckedAt = now;
        return true;
    }

    /**
     * Ack mode: put the chunk of slot s back into the queue (it was not read for long) and show another one, if there is
     * one. The chunk stays unacknowledged and comes back later. @return true when the display changed
     */
    public boolean requeue(int s, long now) {
        if (!ackMode || finished || s >= slots || cur[s] < 0 || mirrored[s]) return false;
        int c = cur[s];
        int[] cur0 = cur.clone();
        long[] at0 = shownAt.clone(), free0 = toneFreeAt.clone();
        boolean[] mir0 = mirrored.clone(), pair0 = paired.clone();
        // its ack may still be on the way: keep the tone off the screen as long as after a same-parity pair
        toneFreeAt[Tone.toneOf(c)] = now + QUARANTINE_SAME_MS;
        for (int k = 0; k < slots; k++) if (cur[k] == c) show(k, -1, now);
        int p = pick(s, c, now);
        if (p < 0) {   // nothing else may be shown now: keep it
            System.arraycopy(cur0, 0, cur, 0, slots);
            System.arraycopy(at0, 0, shownAt, 0, slots);
            System.arraycopy(mir0, 0, mirrored, 0, slots);
            System.arraycopy(pair0, 0, paired, 0, slots);
            System.arraycopy(free0, 0, toneFreeAt, 0, 4);
            return false;
        }
        show(s, p, now);
        for (int k = 0; k < slots; k++) if (cur[k] < 0) fill(k, c, now);
        requeues++;
        return true;
    }

    /** Receiver confirmed the whole transfer. */
    public void onDone() { finished = true; }

    /**
     * Loop mode: advance slots whose period is up. Ack mode: never skips a chunk; fills idle/mirroring slots once a
     * quarantined tone is free again, and starts a verification pass when everything was acknowledged but DONE did
     * not come.
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
                for (int s = 0; s < slots; s++) show(s, -1, now);
                fillAll(now);
                return true;
            }
            boolean changed = false;
            for (int s = 0; s < slots; s++) if (cur[s] < 0 || mirrored[s]) changed |= fill(s, -1, now);
            return changed;
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
        if (ackMode) {
            d = allAckedAt >= 0 ? allAckedAt + VERIFY_MS : now + IDLE_WAKE_MS;
            boolean waiting = false;
            for (int s = 0; s < slots; s++) if (cur[s] < 0 || mirrored[s]) waiting = true;
            if (waiting) for (long f : toneFreeAt) if (f > now) d = Math.min(d, f);
        } else {
            d = Long.MAX_VALUE;
            for (int s = 0; s < slots; s++) d = Math.min(d, shownAt[s] + periodMs);
        }
        return Math.max(d, now + 10);
    }

    /**
     * Manual step (paused). Loop mode: move slot s by d positions among its own chunks. Ack mode: move slot s by d
     * chunks, skipping the other slot's chunk and tone (so every ack stays unambiguous).
     */
    public void step(int s, int d, long now) {
        if (s >= slots) return;
        if (ackMode) {
            int other = slots == 2 ? cur[1 - s] : -1, c = cur[s] < 0 ? (d > 0 ? total - 1 : 0) : cur[s];
            for (int k = 1; k <= total; k++) {
                int j = (((c + k * Integer.signum(d)) % total) + total) % total;
                if (j == other || (other >= 0 && Tone.toneOf(j) == Tone.toneOf(other))) continue;
                if (cur[s] >= 0 && j != cur[s]) quarantine(s, now);
                show(s, j, now);
                return;
            }
            return;
        }
        int c = cur[s] < 0 ? first(s) : cur[s];
        int count = count(s);
        int pos = (c - first(s)) / slots;
        pos = ((pos + d) % count + count) % count;
        show(s, first(s) + pos * slots, now);
    }
}
