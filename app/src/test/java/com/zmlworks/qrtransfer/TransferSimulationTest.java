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
 * into the scheduler. Checks file integrity, that the sender never moves past a chunk the receiver does not have, and
 * that every slot shows its chunks strictly in order (no skips).
 */
public class TransferSimulationTest {
    static final int R = Tone.RATE, MAX_MS = 400_000;

    static final class Result {
        long ms = -1;
        int frames, staleAcks, goodAcks, rounds, bytes;
        boolean intact, noSkip = true;
    }

    static final class Cfg {
        int bytes = 20_000, chunk = 400, slots = 2;
        double pRead = 0.9;        // per camera frame, per code
        double pAckLoss = 0.1;     // a whole ack tone lost (masked, too quiet, …)
        double noise = 0.004;      // background noise rms (normalized)
        double foreignPerSec = 0;  // foreign beeps per second (old-version tones, random frequencies)
        double spuriousPerSec = 0; // spurious *protocol* tones (worst case: false acks)
        Cfg set(double pRead, double pAckLoss) { this.pRead = pRead; this.pAckLoss = pAckLoss; return this; }
    }

    static Result run(Cfg c, long seed) {
        Random rnd = new Random(seed);
        byte[] data = new byte[c.bytes];
        rnd.nextBytes(data);
        List<String> frames = Protocol.frames("5EED" + String.format(Locale.ROOT, "%04X", seed & 0xffff),
                new Protocol.Content(true, "sim.bin", data), c.chunk, true);
        int total = frames.size();
        SlotScheduler sched = new SlotScheduler(total, c.slots, 250, true);
        Protocol.Assembler asm = new Protocol.Assembler();
        AckPolicy policy = new AckPolicy();
        Tone.Listener mic = new Tone.Listener(R);
        float[] audio = new float[R * (MAX_MS / 1000 + 2)];
        short[] block = new short[Tone.BLOCK];
        int blockMs = 1000 * Tone.BLOCK / R;   // 16
        Result res = new Result();
        res.frames = total;
        res.bytes = c.bytes;

        int slots = sched.slots;
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
        for (long t = 0; t < MAX_MS; t++) {
            // screen: what the scheduler shows appears after ~one display refresh (16 ms)
            for (int s = 0; s < slots; s++) {
                int want = sched.shown(s);
                if (want != lastShown[s]) {
                    // the sender may only leave chunk lastShown when the receiver really has it
                    if (lastShown[s] >= 0 && !asm.has(lastShown[s]) && !(complete)) res.noSkip = false;
                    if (want >= 0 && lastShown[s] >= 0 && sched.rounds() == 0 && want != lastShown[s] + slots) res.noSkip = false;
                    lastShown[s] = want;
                }
            }
            if (t % 16 == 0) for (int s = 0; s < slots; s++) {
                if (onScreen[s] != sched.shown(s)) { onScreen[s] = sched.shown(s); onScreenSince[s] = t; }
            }
            // receiver camera at 30 fps; a code must have been on screen ≥ 40 ms (exposure + refresh)
            if (t % 33 == 0) for (int s = 0; s < slots; s++) {
                int idx = onScreen[s];
                if (idx < 0 || t - onScreenSince[s] < 40 || rnd.nextDouble() > c.pRead) continue;
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
                if (r == Protocol.Assembler.Result.IGNORED) throw new AssertionError("bad frame");
            }
            // tone player: FIFO, one at a time, output latency 80–160 ms
            if (!toPlay.isEmpty()) {
                long[] p = toPlay.get(0);
                if (p[0] <= t) {
                    toPlay.remove(0);
                    int tone = (int) p[1];
                    queued[tone] = false;
                    long start = Math.max(t + 80 + rnd.nextInt(80), playerFreeAt);
                    int ms = tone == Tone.DONE ? Tone.DONE_MS : Tone.ACK_MS;
                    playerFreeAt = start + ms + Tone.GAP_MS;
                    if (rnd.nextDouble() >= c.pAckLoss) addTone(audio, Tone.HZ[tone], start, ms, 0.02 + 0.08 * rnd.nextDouble());
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
                } else if (ev >= 0) sched.onAck(ev, t);
            }
            if (t % 50 == 0) sched.tick(t);
        }
        res.intact = got != null && got.isFile && "sim.bin".equals(got.name) && java.util.Arrays.equals(got.data, data);
        res.staleAcks = sched.staleAcks();
        res.goodAcks = sched.goodAcks();
        res.rounds = sched.rounds();
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

    static String row(String name, Cfg c, long seeds) {
        long sum = 0, worst = 0;
        int stale = 0;
        for (long seed = 1; seed <= seeds; seed++) {
            Result r = run(c, seed);
            assertTrue(name + " seed " + seed + " did not finish", r.ms > 0);
            assertTrue(name + " seed " + seed + " corrupt", r.intact);
            assertTrue(name + " seed " + seed + " skipped a chunk", r.noSkip);
            assertEquals(name + " seed " + seed + " needed a verification pass", 0, r.rounds);
            sum += r.ms;
            worst = Math.max(worst, r.ms);
            stale += r.staleAcks;
        }
        double avg = sum / (double) seeds;
        return String.format(Locale.ROOT, "%-44s %6.1f s avg (worst %5.1f s)  %6.0f B/s  stale acks ignored %d",
                name, avg / 1000, worst / 1000.0, c.bytes / (avg / 1000), stale);
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
}
