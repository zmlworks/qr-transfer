package com.zmlworks.qrtransfer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * End-to-end simulation of one transfer with the real protocol classes on both sides and a synthesized acoustic
 * channel: sender {@link SlotScheduler} → screen → receiver camera (random frame loss, decode delay) →
 * {@link Protocol.Assembler} + {@link AckPolicy} → serialised tone player (output latency) → room (random ack loss,
 * random attenuation, background noise, foreign beeps) → sender microphone → {@link Tone.Listener} (Goertzel) → back
 * into the scheduler. Checks file integrity and that the sender never leaves a chunk as "acked" that the receiver does
 * not have (a false ack).
 * <p>0.4.0: compares the 0.3.1 sender (fixed even/odd lanes, {@link LaneScheduler031}) with the shared queue
 * ({@link SlotScheduler}), the 0.3.1 receiver ({@link AckPolicy031}, per-slot de-duplication) with the 0.4.0 one
 * (per-tone), and models codes that are hard to read in one rendering (mask) until the sender re-masks them.
 * All numbers are simulated; nothing here was measured on a phone.
 */
public class TransferSimulationTest {
    static final int R = Tone.RATE, MAX_MS = 400_000;

    static final class Result {
        long ms = -1;
        int frames, staleAcks, goodAcks, rounds, bytes;
        int requeues, remasks, falseAcks;
        boolean intact, noSkip = true;
    }

    /** The sender scheduler under test: 0.3.1 lanes or the 0.4.0 shared queue. */
    interface Sched {
        void start(long t); int slots(); int shown(int s); boolean isMirror(int s); boolean isAcked(int i);
        boolean onAck(int tone, long t); boolean tick(long t); void onDone(); boolean requeue(int s, long t);
        int rounds(); int staleAcks(); int goodAcks(); int requeues();
    }
    /** The receiver ack policy under test. */
    interface Policy { int onFrame(int index, long t); boolean shouldRepeatDone(long t); }

    static Sched shared(int total, int slots, boolean mirror) {
        SlotScheduler q = new SlotScheduler(total, slots, 250, true);
        q.setMirror(mirror);
        return new Sched() {
            public void start(long t) { q.start(t); }
            public int slots() { return q.slots; }
            public int shown(int s) { return q.shown(s); }
            public boolean isMirror(int s) { return q.isMirror(s); }
            public boolean isAcked(int i) { return q.isAcked(i); }
            public boolean onAck(int tone, long t) { return q.onAck(tone, t); }
            public boolean tick(long t) { return q.tick(t); }
            public void onDone() { q.onDone(); }
            public boolean requeue(int s, long t) { return q.requeue(s, t); }
            public int rounds() { return q.rounds(); }
            public int staleAcks() { return q.staleAcks(); }
            public int goodAcks() { return q.goodAcks(); }
            public int requeues() { return q.requeues(); }
        };
    }

    static Sched lanes(int total, int slots) {
        LaneScheduler031 q = new LaneScheduler031(total, slots, 250, true);
        return new Sched() {
            public void start(long t) { q.start(t); }
            public int slots() { return q.slots; }
            public int shown(int s) { return q.shown(s); }
            public boolean isMirror(int s) { return false; }
            public boolean isAcked(int i) { return q.isAcked(i); }
            public boolean onAck(int tone, long t) { return q.onAck(tone, t); }
            public boolean tick(long t) { return q.tick(t); }
            public void onDone() { q.onDone(); }
            public boolean requeue(int s, long t) { return false; }
            public int rounds() { return q.rounds(); }
            public int staleAcks() { return q.staleAcks(); }
            public int goodAcks() { return q.goodAcks(); }
            public int requeues() { return 0; }
        };
    }

    static Policy policy(boolean old) {
        if (old) { AckPolicy031 p = new AckPolicy031(); return new Policy() {
            public int onFrame(int i, long t) { return p.onFrame(i, t); }
            public boolean shouldRepeatDone(long t) { return p.shouldRepeatDone(t); } }; }
        AckPolicy p = new AckPolicy();
        return new Policy() {
            public int onFrame(int i, long t) { return p.onFrame(i, t); }
            public boolean shouldRepeatDone(long t) { return p.shouldRepeatDone(t); } };
    }

    static final class Cfg {
        int bytes = 20_000, chunk = 400, slots = 2;
        double pRead = 0.9;        // per camera frame, per code
        double pAckLoss = 0.1;     // a whole ack tone lost (masked, too quiet, …)
        double noise = 0.004;      // background noise rms (normalized)
        double foreignPerSec = 0;  // foreign beeps per second (old-version tones, random frequencies)
        double spuriousPerSec = 0; // spurious *protocol* tones (worst case: false acks)
        boolean oldSender, oldReceiver, mirror = true;
        double pHard = 0;          // probability that one rendering (chunk × mask) of a code is "hard" for this camera
        double pHardRead = 0.02;   // read probability per frame of a hard rendering
        double slotBRead = 1;      // factor on slot B's read probability (glare, focus at the edge, …)
        long remaskMs = 0;         // sender auto re-mask after this long without an ack (0 = off; the 0.3.1 sender has none)
        Cfg set(double pRead, double pAckLoss) { this.pRead = pRead; this.pAckLoss = pAckLoss; return this; }
        Cfg old() { oldSender = true; oldReceiver = true; return this; }
        Cfg hard(double pHard, long remaskMs) { this.pHard = pHard; this.remaskMs = remaskMs; return this; }
    }

    static Result run(Cfg c, long seed) {
        Random rnd = new Random(seed);
        byte[] data = new byte[c.bytes];
        rnd.nextBytes(data);
        List<String> frames = Protocol.frames("5EED" + String.format(Locale.ROOT, "%04X", seed & 0xffff),
                new Protocol.Content(true, "sim.bin", data), c.chunk, true);
        int total = frames.size();
        Sched sched = c.oldSender ? lanes(total, c.slots) : shared(total, c.slots, c.mirror);
        Protocol.Assembler asm = new Protocol.Assembler();
        Policy policy = policy(c.oldReceiver);
        long hardSeed = rnd.nextLong();
        int[] variant = new int[total];   // per-chunk rendering counter (auto re-mask: next mask, then next EC level)
        long[] variantAt = new long[2];
        Tone.Listener mic = new Tone.Listener(R);
        float[] audio = new float[R * (MAX_MS / 1000 + 2)];
        short[] block = new short[Tone.BLOCK];
        int blockMs = 1000 * Tone.BLOCK / R;   // 16
        Result res = new Result();
        res.frames = total;
        res.bytes = c.bytes;

        int slots = sched.slots();
        int[] onScreen = {-2, -2};
        long[] onScreenSince = new long[2];
        int[] lastShown = {-2, -2};
        // pending decodes {readyAtMs, chunkIndex}
        List<long[]> decodes = new ArrayList<>();
        long playerFreeAt = 0;
        boolean[] queued = new boolean[Tone.COUNT];
        List<long[]> toPlay = new ArrayList<>();   // {startMs, tone}
        boolean complete = false;
        Protocol.Content got = null;

        sched.start(0);
        for (int s = 0; s < slots; s++) lastShown[s] = sched.shown(s);
        int[] remaskCount = new int[2];
        StringBuilder log = new StringBuilder();
        for (long t = 0; t < MAX_MS; t++) {
            // screen: what the scheduler shows appears after ~one display refresh (16 ms)
            for (int s = 0; s < slots; s++) {
                int want = sched.shown(s);
                if (want != lastShown[s]) {
                    // the sender may only count chunk lastShown as acked when the receiver really has it
                    if (lastShown[s] >= 0 && sched.isAcked(lastShown[s]) && !asm.has(lastShown[s]) && !complete) {
                        res.noSkip = false;
                        res.falseAcks++;
                        if (System.getenv("SIMDEBUG") != null) System.out.println("FALSE ACK t=" + t + " slot " + s + " chunk " + lastShown[s] + " -> " + want + " log " + log);
                    }
                    lastShown[s] = want;
                    if (System.getenv("SIMDEBUG") != null) log.append(t).append(":show").append(s == 0 ? 'A' : 'B').append(want).append(sched.isMirror(s) ? "m" : "").append(' ');
                    variantAt[s] = t;
                    remaskCount[s] = 0;
                }
                // auto re-mask (0.5.0 sender): same payload, next mask; after 8 masks the chunk goes back to the queue
                if (c.remaskMs > 0 && want >= 0 && t - variantAt[s] >= c.remaskMs) {
                    variantAt[s] = t;
                    if (++remaskCount[s] >= 8 && sched.requeue(s, t)) { res.requeues++; continue; }
                    variant[want]++;
                    res.remasks++;
                }
            }
            if (t % 16 == 0) for (int s = 0; s < slots; s++) {
                if (onScreen[s] != sched.shown(s)) { onScreen[s] = sched.shown(s); onScreenSince[s] = t; }
            }
            // receiver camera at 30 fps; a code must have been on screen ≥ 40 ms (exposure + refresh)
            if (t % 33 == 0) for (int s = 0; s < slots; s++) {
                int idx = onScreen[s];
                if (idx < 0 || t - onScreenSince[s] < 40) continue;
                int v = variant[idx] + (sched.isMirror(s) ? 4 : 0);
                boolean hard = c.pHard > 0 && new Random(hardSeed ^ (idx * 1_000_003L + v * 7919L)).nextDouble() < c.pHard;
                double p = (hard ? c.pHardRead : c.pRead) * (s == 1 ? c.slotBRead : 1);
                if (rnd.nextDouble() > p) continue;
                decodes.add(new long[]{t + 50 + rnd.nextInt(70), idx});
            }
            for (int k = 0; k < decodes.size(); k++) {
                long[] d = decodes.get(k);
                if (d[0] > t) continue;
                decodes.remove(k--);
                String text = frames.get((int) d[1]);
                if (complete) {
                    if (policy.shouldRepeatDone(t)) enqueue(toPlay, queued, Tone.DONE, t);
                    continue;
                }
                Protocol.Assembler.Result r = asm.accept(text);
                if (asm.complete()) {
                    try { got = asm.assemble(); } catch (Exception e) { throw new AssertionError(e); }
                    complete = true;
                    enqueue(toPlay, queued, Tone.DONE, t);
                    policy.shouldRepeatDone(t);
                    continue;
                }
                int tone = policy.onFrame(asm.lastSeen(), t);
                if (tone >= 0) enqueue(toPlay, queued, tone, t);
                if (tone >= 0 && System.getenv("SIMDEBUG") != null) log.append(t).append(":rx").append(asm.lastSeen()).append("→").append(tone).append(' ');
                if (r == Protocol.Assembler.Result.IGNORED) throw new AssertionError("bad frame");
            }
            // tone player: FIFO, one at a time, output latency 80–160 ms. Like AckPlayer, the next tone leaves the queue
            // (and may be queued again) only when the previous one is nearly written out (0.4.0: the 0.3.x sim let
            // tones leave the queue early, which allowed several copies of one tone in flight)
            if (!toPlay.isEmpty()) {
                long[] p = toPlay.get(0);
                if (p[0] <= t && t >= playerFreeAt - 120) {
                    toPlay.remove(0);
                    int tone = (int) p[1];
                    queued[tone] = false;
                    long start = Math.max(t + 80 + rnd.nextInt(80), playerFreeAt);
                    int ms = tone == Tone.DONE ? Tone.DONE_MS : Tone.ACK_MS;
                    playerFreeAt = start + ms + Tone.GAP_MS;
                    // DONE at the 0.4.0 volume (Tone.DONE_GAIN of the ack level)
                    double amp = (0.02 + 0.08 * rnd.nextDouble()) * (tone == Tone.DONE ? Tone.DONE_GAIN : 1);
                    boolean lost = rnd.nextDouble() < c.pAckLoss;
                    if (!lost) addTone(audio, Tone.HZ[tone], start, ms, amp);
                    if (System.getenv("SIMDEBUG") != null) log.append(t).append(":play").append(tone).append('@').append(start).append(lost ? "lost" : "").append(' ');
                    // the player thread is busy until the tone ends: the next queued tone waits
                    for (long[] q : toPlay) q[0] = Math.max(q[0], playerFreeAt - 80);
                }
            }
            // the room
            if (c.foreignPerSec > 0 && rnd.nextDouble() < c.foreignPerSec / 1000) {
                double[] foreign = {1500, 2200, 2700, 1900, 2850, 1000 + 2500 * rnd.nextDouble()};
                addTone(audio, foreign[rnd.nextInt(foreign.length)], t, 60 + rnd.nextInt(400), 0.03 + 0.1 * rnd.nextDouble());
            }
            if (c.spuriousPerSec > 0 && rnd.nextDouble() < c.spuriousPerSec / 1000)
                addTone(audio, Tone.HZ[rnd.nextInt(4)], t, Tone.ACK_MS, 0.05);
            // sender microphone: one 16 ms block at a time
            if (t % blockMs == blockMs - 1) {
                int off = (int) ((t + 1 - blockMs) * R / 1000);
                for (int i = 0; i < Tone.BLOCK; i++) {
                    double v = audio[off + i] + rnd.nextGaussian() * c.noise;
                    block[i] = (short) Math.max(-32768, Math.min(32767, Math.round(v * 32767)));
                }
                int ev = mic.feed(block, 0, Tone.BLOCK);
                if (ev == Tone.DONE) {
                    sched.onDone();
                    res.ms = t;
                    break;
                } else if (ev >= 0) { boolean ok = sched.onAck(ev, t); if (System.getenv("SIMDEBUG") != null) { log.append(t).append(":hear").append(ev).append(ok ? "+" : "-").append(' '); } }
            }
            if (t % 50 == 0) sched.tick(t);
        }
        res.intact = got != null && got.isFile && "sim.bin".equals(got.name) && java.util.Arrays.equals(got.data, data);
        res.staleAcks = sched.staleAcks();
        res.goodAcks = sched.goodAcks();
        res.rounds = sched.rounds();
        res.requeues = sched.requeues();
        if (res.intact) assertArrayEquals(data, got.data);
        return res;
    }

    static void enqueue(List<long[]> q, boolean[] queued, int tone, long t) {
        if (queued[tone]) return;   // AckPlayer: a tone that is already waiting is not queued twice
        queued[tone] = true;
        q.add(new long[]{t, tone});
    }

    static void addTone(float[] audio, double hz, long startMs, int ms, double amp) {
        short[] s = Tone.synth(hz, ms, R, amp);
        int off = (int) (startMs * R / 1000);
        for (int i = 0; i < s.length && off + i < audio.length; i++) audio[off + i] += s[i] / 32767f;
    }

    static String row(String name, Cfg c, long seeds) { return row(name, c, seeds, true); }

    static double avgMs(Cfg c, long seeds) {
        long sum = 0;
        for (long seed = 1; seed <= seeds; seed++) {
            Result r = run(c, seed);
            assertTrue("seed " + seed + " did not finish", r.ms > 0);
            assertTrue("seed " + seed + " corrupt", r.intact);
            if (!c.oldSender) assertTrue("seed " + seed + " false ack", r.noSkip);
            sum += r.ms;
        }
        return sum / (double) seeds;
    }

    static String row(String name, Cfg c, long seeds, boolean noRounds) {
        long sum = 0, worst = 0;
        int stale = 0, rounds = 0, requeues = 0, remasks = 0, falseAcks = 0;
        for (long seed = 1; seed <= seeds; seed++) {
            Result r = run(c, seed);
            assertTrue(name + " seed " + seed + " did not finish", r.ms > 0);
            assertTrue(name + " seed " + seed + " corrupt", r.intact);
            // the 0.3.1 sender has no defence against late duplicates (counted, not asserted: that is the baseline)
            if (!c.oldSender) assertTrue(name + " seed " + seed + " false ack", r.noSkip);
            falseAcks += r.falseAcks;
            if (noRounds && !c.oldSender) assertEquals(name + " seed " + seed + " needed a verification pass", 0, r.rounds);
            sum += r.ms;
            worst = Math.max(worst, r.ms);
            stale += r.staleAcks;
            rounds += r.rounds;
            requeues += r.requeues;
            remasks += r.remasks;
        }
        double avg = sum / (double) seeds;
        return String.format(Locale.ROOT, "%-50s %6.1f s avg (worst %5.1f s) %6.0f B/s  stale %3d  false %d  verify %d  remask %3d  requeue %d",
                name, avg / 1000, worst / 1000.0, c.bytes / (avg / 1000), stale, falseAcks, rounds, remasks, requeues);
    }

    @Test public void randomFrameAndAckLossTransfersIntactWithoutSkips() {
        StringBuilder out = new StringBuilder("sim (20 000 B random file, 400 B chunks = 51 frames, real Goertzel on synthesized audio):\n");
        out.append(row("2 codes, good camera (read 90 %, ack loss 10 %)", new Cfg(), 6)).append('\n');
        out.append(row("2 codes, perfect (read 100 %, ack loss 0 %)", new Cfg().set(1.0, 0.0), 4)).append('\n');
        Cfg one = new Cfg();
        one.slots = 1;
        out.append(row("1 code,  good camera (read 90 %, ack loss 10 %)", one, 4)).append('\n');
        out.append(row("2 codes, poor (read 50 %, ack loss 30 %)", new Cfg().set(0.5, 0.3), 6)).append('\n');
        out.append(row("2 codes, bad (read 25 %, ack loss 50 %)", new Cfg().set(0.25, 0.5), 4)).append('\n');
        Cfg noisy = new Cfg().set(0.8, 0.2);
        noisy.noise = 0.012;
        noisy.foreignPerSec = 1.0;
        out.append(row("2 codes, noisy room + foreign beeps 1/s", noisy, 4)).append('\n');
        System.out.print(out);
    }

    @Test public void spuriousProtocolTonesNeverCorruptAndStillFinish() {
        // worst case: someone else's device emits our exact ack tones. A false ack can let a slot move on early; the
        // receiver then lacks that chunk, so DONE never comes and the verification pass resends (still ack-driven).
        Cfg c = new Cfg().set(0.8, 0.2);
        c.spuriousPerSec = 3;
        c.bytes = 8000;
        int rounds = 0;
        for (long seed = 1; seed <= 6; seed++) {
            Result r = run(c, seed);
            assertTrue("seed " + seed, r.ms > 0);
            assertTrue("seed " + seed, r.intact);
            rounds += r.rounds;
        }
        System.out.println("sim: 3 spurious protocol tones/s (exact ack frequencies) → verification passes needed: " + rounds + " in 6 runs");
    }

    /** 0.3.1 lanes vs 0.4.0 shared queue (simulated). Printed table goes into BUILD_REPORT.md. */
    @Test public void sharedQueueVersusLanes() {
        StringBuilder out = new StringBuilder("sim, 0.3.1 sender+receiver vs 0.5.0 sender + 0.4.0 receiver (20 000 B, 51 frames):\n");
        Cfg[][] pairs = {
            {new Cfg().old(), new Cfg()},
            {new Cfg().set(0.5, 0.3).old(), new Cfg().set(0.5, 0.3)},
        };
        String[] names = {"good camera (read 90 %, ack loss 10 %)", "poor (read 50 %, ack loss 30 %)"};
        for (int k = 0; k < pairs.length; k++) {
            out.append(row("0.3.1 lanes   " + names[k], pairs[k][0], 6)).append('\n');
            out.append(row("0.4.0 shared  " + names[k], pairs[k][1], 6)).append('\n');
        }
        // slot B much harder to read (the user's case was the reverse: one lane finished, the other worked alone)
        Cfg ob = new Cfg().old(); ob.slotBRead = 0.15;
        Cfg nb = new Cfg(); nb.slotBRead = 0.15;
        out.append(row("0.3.1 lanes   slot B read ×0.15", ob, 6)).append('\n');
        out.append(row("0.4.0 shared  slot B read ×0.15", nb, 6)).append('\n');
        // 10 % of renderings are hard (read 2 %/frame): without re-mask vs re-mask after 3 s
        Cfg oh = new Cfg().old().hard(0.10, 0);
        Cfg nh0 = new Cfg().hard(0.10, 0);
        Cfg nh3 = new Cfg().hard(0.10, 3000);
        out.append(row("0.3.1 lanes   10 % hard codes", oh, 6, false)).append('\n');
        out.append(row("0.4.0 shared  10 % hard codes, no re-mask", nh0, 6, false)).append('\n');
        out.append(row("0.4.0 shared  10 % hard codes, re-mask 3 s", nh3, 6, false)).append('\n');
        // 15 % of renderings completely unreadable: the 0.3.1 sender would wait forever on such a chunk (not run);
        // re-mask after 3 s (8 masks, then the chunk goes back to the queue) finishes
        Cfg nu = new Cfg().hard(0.15, 3000); nu.pHardRead = 0;
        out.append(row("0.4.0 shared  15 % unreadable codes, re-mask 3 s", nu, 6, false)).append('\n');
        // the new sender with the OLD (0.3.1) receiver: compatibility
        Cfg mix = new Cfg(); mix.oldReceiver = true;
        Cfg mixh = new Cfg().hard(0.10, 3000); mixh.oldReceiver = true;
        out.append(row("0.5.0 sender + 0.3.1 receiver", mix, 6)).append('\n');
        out.append(row("0.5.0 sender + 0.3.1 receiver, hard + re-mask", mixh, 6, false)).append('\n');
        System.out.print(out);
        // the shared queue must not be slower when one slot is bad, and re-masking must help with hard codes
        assertTrue(avgMs(nb, 6) < avgMs(ob, 6));
        assertTrue(avgMs(nh3, 6) < avgMs(oh, 6));
    }

    /** Old receiver + new sender: the scheduler must never accept a stale tone as an ack for a chunk the receiver lacks. */
    @Test public void newSenderWithOldReceiverHasNoFalseAcks() {
        for (double pRead : new double[]{0.3, 0.9, 1.0}) {
            Cfg c = new Cfg().set(pRead, 0.1).hard(0.15, 2500);
            c.oldReceiver = true;
            c.bytes = 12_000;
            for (long seed = 1; seed <= 8; seed++) {
                Result r = run(c, seed);
                assertTrue("pRead " + pRead + " seed " + seed + " did not finish", r.ms > 0);
                assertTrue("pRead " + pRead + " seed " + seed + " corrupt", r.intact);
                assertTrue("pRead " + pRead + " seed " + seed + " false ack", r.noSkip);
            }
        }
    }
}
