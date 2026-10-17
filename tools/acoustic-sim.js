// Simulated acoustic channel: receiver phone speaker -> room -> laptop microphone -> web sender's tone detector.
// Measures, per scenario, the ack detection rate, wrong-tone events (the dangerous kind: a wrong tone can advance a
// slot) and false events per minute on non-tone sound (fan noise, speech, clicks).
// Usage: node tools/acoustic-sim.js [sender.html] [--trials N] [--json out.json] [--ackms 80] [--sens 3] [--donegain 0.28]
//   --donegain: DONE amplitude relative to the acks (app 0.4.0 plays DONE at Tone.DONE_GAIN = 0.28, about −11 dB)
//   sender.html defaults to web/sender.html; pass an older copy (e.g. git show 9c19ad7:web/sender.html) to compare.
"use strict";
const fs = require("fs"), path = require("path"), vm = require("vm");
const args = process.argv.slice(2);
const opt = (name, def) => { const i = args.indexOf("--" + name); return i >= 0 ? args[i + 1] : def; };
const htmlPath = args[0] && !args[0].startsWith("--") ? args[0] : path.join(__dirname, "..", "web", "sender.html");
const TRIALS = Number(opt("trials", 6)), ACK_MS = opt("ackms", null), SENS = opt("sens", null);

function loadQT1(file) {
  const html = fs.readFileSync(file, "utf8");
  let src = html.match(/<script id="protocol">([\s\S]*?)<\/script>/)[1];
  // tuning experiments: OVR="NAME=value;NAME2=value" rewrites "NAME = <number>" constants in the detector
  for (const kv of (process.env.OVR || "").split(";").filter(Boolean)) {
    const [k, v] = kv.split("="); src = src.replace(new RegExp("\\b" + k + " = [0-9.\\-]+"), k + " = " + v);
  }
  const ctx = { TextEncoder, Uint8Array, Uint32Array, Float32Array, Float64Array, Math, Array, Infinity };
  vm.createContext(ctx);
  vm.runInContext(src + "\nthis.QT1 = QT1;", ctx);
  return ctx.QT1;
}

// ---------------- deterministic RNG ----------------
function rng(seed) {
  let s = seed >>> 0 || 1;
  const r = () => { s ^= s << 13; s >>>= 0; s ^= s >>> 17; s ^= s << 5; s >>>= 0; return s / 4294967296; };
  r.gauss = () => Math.sqrt(-2 * Math.log(r() + 1e-12)) * Math.cos(2 * Math.PI * r());
  r.uni = (a, b) => a + (b - a) * r();
  r.int = (a, b) => Math.floor(a + (b - a + 1) * r());
  return r;
}

// ---------------- DSP helpers ----------------
function fft(re, im, inv) {
  const n = re.length;
  for (let i = 1, j = 0; i < n; i++) {
    let bit = n >> 1;
    for (; j & bit; bit >>= 1) j ^= bit;
    j ^= bit;
    if (i < j) { let t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t; }
  }
  for (let len = 2; len <= n; len <<= 1) {
    const ang = 2 * Math.PI / len * (inv ? 1 : -1), wr = Math.cos(ang), wi = Math.sin(ang);
    for (let i = 0; i < n; i += len) {
      let cr = 1, ci = 0;
      for (let k = 0; k < len / 2; k++) {
        const a = i + k, b = a + len / 2;
        const xr = re[b] * cr - im[b] * ci, xi = re[b] * ci + im[b] * cr;
        re[b] = re[a] - xr; im[b] = im[a] - xi; re[a] += xr; im[a] += xi;
        const t = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = t;
      }
    }
  }
  if (inv) for (let i = 0; i < n; i++) { re[i] /= n; im[i] /= n; }
}
function convolve(x, h) {
  let n = 1; while (n < x.length + h.length) n <<= 1;
  const ar = new Float64Array(n), ai = new Float64Array(n), br = new Float64Array(n), bi = new Float64Array(n);
  ar.set(x); br.set(h);
  fft(ar, ai, false); fft(br, bi, false);
  for (let i = 0; i < n; i++) { const r = ar[i] * br[i] - ai[i] * bi[i]; ai[i] = ar[i] * bi[i] + ai[i] * br[i]; ar[i] = r; }
  fft(ar, ai, true);
  return Float64Array.from(ar.subarray(0, x.length));
}
// RBJ biquads
function biquad(type, f0, fs, q, gainDb) {
  const A = Math.pow(10, (gainDb || 0) / 40), w = 2 * Math.PI * f0 / fs, c = Math.cos(w), s = Math.sin(w), al = s / (2 * q);
  let b0, b1, b2, a0, a1, a2;
  if (type === "hp") { b0 = (1 + c) / 2; b1 = -(1 + c); b2 = (1 + c) / 2; a0 = 1 + al; a1 = -2 * c; a2 = 1 - al; }
  else if (type === "lp") { b0 = (1 - c) / 2; b1 = 1 - c; b2 = (1 - c) / 2; a0 = 1 + al; a1 = -2 * c; a2 = 1 - al; }
  else if (type === "bp") { b0 = al; b1 = 0; b2 = -al; a0 = 1 + al; a1 = -2 * c; a2 = 1 - al; }
  else { b0 = 1 + al * A; b1 = -2 * c; b2 = 1 - al * A; a0 = 1 + al / A; a1 = -2 * c; a2 = 1 - al / A; }   // peaking
  return { b0: b0 / a0, b1: b1 / a0, b2: b2 / a0, a1: a1 / a0, a2: a2 / a0 };
}
function filt(x, f) {
  const y = new Float64Array(x.length); let x1 = 0, x2 = 0, y1 = 0, y2 = 0;
  for (let i = 0; i < x.length; i++) {
    const v = f.b0 * x[i] + f.b1 * x1 + f.b2 * x2 - f.a1 * y1 - f.a2 * y2;
    x2 = x1; x1 = x[i]; y2 = y1; y1 = v; y[i] = v;
  }
  return y;
}
const rms = (x, a, b) => { a = a || 0; b = b || x.length; let e = 0; for (let i = a; i < b; i++) e += x[i] * x[i]; return Math.sqrt(e / Math.max(1, b - a)); };
const db = (v) => 20 * Math.log10(Math.max(v, 1e-12));
const scaleTo = (x, dbfs) => { const g = Math.pow(10, dbfs / 20) / Math.max(rms(x), 1e-12); return x.map((v) => v * g); };
function add(a, b) { const o = Float64Array.from(a); for (let i = 0; i < Math.min(a.length, b.length); i++) o[i] += b[i]; return o; }

// ---------------- sources ----------------
function toneBurst(out, at, hz, ms, fs, amp, warmMs) {
  const n = Math.round(fs * ms / 1000), f = Math.round(fs / 100), w = Math.round(fs * (warmMs || 0) / 1000);
  for (let i = 0; i < n && at + i < out.length; i++) {
    let env = i < f ? 0.5 - 0.5 * Math.cos(Math.PI * i / f) : i > n - f ? 0.5 - 0.5 * Math.cos(Math.PI * (n - i) / f) : 1;
    if (i < w) env *= Math.pow(10, -30 * (1 - i / w) / 20);      // speaker amplifier waking up: first ms ramp from -30 dB
    out[at + i] += amp * env * Math.sin(2 * Math.PI * hz * i / fs);
  }
}
/** pink-ish fan noise with a low-frequency hump, blade-pass lines and a motor whine */
function fanNoise(n, fs, r) {
  const y = new Float64Array(n); let b0 = 0, b1 = 0, b2 = 0;
  for (let i = 0; i < n; i++) {   // Paul Kellet's economical pink filter
    const w = r.gauss(); b0 = 0.99765 * b0 + w * 0.0990460; b1 = 0.96300 * b1 + w * 0.2965164; b2 = 0.57000 * b2 + w * 1.0526913;
    y[i] = b0 + b1 + b2 + w * 0.1848;
  }
  let z = add(filt(y, biquad("pk", 120, fs, 0.7, 10)), new Float64Array(n));
  const bp = r.uni(90, 160), wh = r.uni(400, 950);
  for (let i = 0; i < n; i++) z[i] += 0.6 * Math.sin(2 * Math.PI * bp * i / fs) + 0.3 * Math.sin(2 * Math.PI * 2 * bp * i / fs) + 0.08 * Math.sin(2 * Math.PI * wh * i / fs);
  return z;
}
/** speech-like: glottal pulses (gliding f0) through moving formants, syllable envelope, fricatives */
function speech(n, fs, r) {
  const y = new Float64Array(n);
  let phase = 0, f0 = r.uni(100, 220), target = f0, seg = 0, F = [600, 1500, 2600], env = 0, syl = 0;
  const vowels = [[730, 1090, 2440], [270, 2290, 3010], [300, 870, 2240], [530, 1840, 2480], [570, 840, 2410], [440, 1020, 2240], [660, 1720, 2410]];
  const exc = new Float64Array(n), fric = new Float64Array(n), envA = new Float64Array(n);
  for (let i = 0; i < n; i++) {
    if (--seg <= 0) { seg = Math.round(fs * r.uni(0.08, 0.25)); target = r.uni(85, 260); }
    f0 += (target - f0) * 0.0005 + r.gauss() * 0.05;
    phase += f0 / fs;
    if (phase >= 1) { phase -= 1; exc[i] = 1; }
    if (--syl <= 0) syl = Math.round(fs * r.uni(0.12, 0.3));
    env = Math.sin(Math.PI * (syl / (fs * 0.3))) ** 2; envA[i] = env;
    fric[i] = r() < 0.25 ? r.gauss() * 0.3 : 0;
  }
  // piecewise formants: re-filter 150 ms segments
  const segLen = Math.round(fs * 0.15);
  for (let a = 0; a < n; a += segLen) {
    F = vowels[r.int(0, vowels.length - 1)];
    let part = exc.slice(a, Math.min(n, a + segLen + 400));
    let v = new Float64Array(part.length);
    for (const [k, fr] of F.entries()) { const bw = [80, 120, 160][k]; const g = [1, 0.6, 0.35][k]; const o = filt(part, biquad("bp", fr, fs, fr / bw)); for (let i = 0; i < v.length; i++) v[i] += g * o[i] * 30; }
    for (let i = 0; i < segLen && a + i < n; i++) y[a + i] += v[i] * envA[a + i];
  }
  const f2 = filt(fric, biquad("hp", 3000, fs, 0.7));
  for (let i = 0; i < n; i++) y[i] += f2[i] * envA[i] * 0.5;
  return y;
}
function clicks(n, fs, r) { const y = new Float64Array(n); for (let t = 0; t < n; t += Math.round(fs * r.uni(0.08, 0.4))) for (let i = 0; i < 200 && t + i < n; i++) y[t + i] += r.gauss() * Math.exp(-i / 30); return y; }

// ---------------- channel ----------------
function speakerEq(x, fs, r, p) {
  let y = filt(x, biquad("hp", r.uni(450, 900), fs, r.uni(0.9, 1.6)));     // small speaker: no bass, resonant cut-off
  y = filt(y, biquad("pk", r.uni(1800, 3600), fs, r.uni(2, 4), r.uni(3, 10)));  // housing resonance
  y = filt(y, biquad("pk", r.uni(1100, 3300), fs, r.uni(2, 5), -r.uni(3, p.dip || 9)));  // irregular response: a dip
  y = filt(y, biquad("lp", r.uni(4500, 7000), fs, 0.8));
  if (p.drive) { const d = p.drive, t = Math.tanh(d); y = y.map((v) => Math.tanh(d * v) / t); }   // max-volume clipping
  return y;
}
function rir(fs, r, rt60, drrDb) {
  const n = Math.round(fs * rt60 * 1.1), h = new Float64Array(n), d0 = Math.round(fs * 0.0015);
  h[d0] = 1;
  for (let k = 0; k < 5; k++) h[d0 + Math.round(fs * r.uni(0.002, 0.015))] += r.uni(0.25, 0.6) * (r() < 0.5 ? -1 : 1);
  const tail = new Float64Array(n); let e = 0;
  for (let i = d0 + Math.round(fs * 0.005); i < n; i++) { tail[i] = r.gauss() * Math.exp(-6.9 * (i - d0) / (fs * rt60)); e += tail[i] * tail[i]; }
  const g = Math.sqrt(Math.pow(10, -drrDb / 10) / e);   // direct energy 1 → reverberant energy 10^(-DRR/10)
  for (let i = 0; i < n; i++) h[i] += g * tail[i];
  return h;
}
function agc(x, fs, targetDb, maxGainDb) {
  const y = new Float64Array(x.length), tgt = Math.pow(10, targetDb / 20), gmax = Math.pow(10, maxGainDb / 20);
  let envl = 1e-4, g = 1; const att = Math.exp(-1 / (fs * 0.005)), rel = Math.exp(-1 / (fs * 0.4));
  for (let i = 0; i < x.length; i++) {
    const a = Math.abs(x[i]); envl = a > envl ? att * envl + (1 - att) * a : rel * envl + (1 - rel) * a;
    const want = Math.min(gmax, tgt / Math.max(envl * 1.41, 1e-6)); g += (want - g) * (want < g ? 0.002 : 0.00005);
    y[i] = x[i] * g;
  }
  return y;
}
function quantize(x) { return Float32Array.from(x, (v) => Math.max(-32768, Math.min(32767, Math.round(v * 32768))) / 32768); }

/** One trial: a sequence of acks/DONE through the channel. Returns {sig, marks:[{at,end,tone}]} */
const DONE_GAIN = Number(opt("donegain", 1));
function trial(QT1, p, seed) {
  const r = rng(seed), fs = p.fs, ackMs = p.ackMs, gapMin = p.gapMs || 40;
  const N = p.acks || 30, marks = [];
  let t = Math.round(fs * 0.6);
  const src = new Float64Array(Math.round(fs * (1 + N * (ackMs + 700) / 1000 + 1.5)));
  // protocol-shaped sequence: each slot alternates its bit (A0 A1 A0 …, B0 B1 …); 15 % are re-acks of the same chunk,
  // which the receiver only repeats after 700 ms. Two tones a short gap apart are the two slots of one camera frame.
  const bit = [0, 0], lastEnd = [-1e9, -1e9];
  let short = false, prevSlot = -1;
  for (let k = 0; k < N; k++) {
    let tone, slot = -1;
    if (k === N - 1) tone = 4;
    else {
      slot = short ? 1 - prevSlot : r.int(0, 1);
      if (r() >= 0.15) bit[slot] ^= 1;
      else t = Math.max(t, lastEnd[slot] + Math.round(fs * 0.62));      // re-ack: same tone, ≥ 700 ms after
      tone = slot * 2 + bit[slot];
    }
    const ms = tone === 4 ? 400 : ackMs;
    const warm = p.warmup ? r.uni(0, p.warmup) : 0;
    toneBurst(src, t, QT1.HZ[tone], ms, fs, tone === 4 ? 0.7 * DONE_GAIN : 0.7, warm);
    marks.push({ at: t, end: t + Math.round(fs * ms / 1000), tone });
    if (slot >= 0) lastEnd[slot] = t + Math.round(fs * ms / 1000);
    short = !short && r() < 0.35; prevSlot = slot;
    t += Math.round(fs * (ms + (short ? gapMin : r.uni(150, 450))) / 1000);
  }
  let x = speakerEq(src, fs, r, p);
  x = convolve(x, rir(fs, r, p.rt60, p.drr));
  // level: steady part of the first tone at the mic = p.level dBFS
  const m0 = marks[0], g = Math.pow(10, p.level / 20) / Math.max(rms(x, m0.at + Math.round(fs * 0.02), m0.end - Math.round(fs * 0.01)), 1e-12);
  x = x.map((v) => v * g);
  if (p.noise != null) x = add(x, scaleTo(fanNoise(x.length, fs, r), p.noise));
  if (p.speech != null) x = add(x, scaleTo(speech(x.length, fs, r), p.speech));
  if (p.agc) x = agc(x, fs, -24, 30);
  x = x.map((v) => v + 0.002);   // DC offset (common on laptop mics without a high-pass)
  return { sig: quantize(x), marks };
}

function listen(QT1, sig, fs) {
  const l = SENS != null && QT1.Listener.length >= 2 ? new QT1.Listener(fs, { sensitivity: Number(SENS) }) : new QT1.Listener(fs), ev = [];
  for (let i = 0; i < sig.length; i += 2048) {
    const e = l.feed(sig.subarray(i, i + 2048));
    for (const tone of e) ev.push({ tone, at: i + 2048 });
  }
  return ev;
}

function score(ev, marks, fs) {
  let hit = 0, wrong = 0, extra = 0, extraDup = 0, doneHit = 0; const used = new Set();
  for (const e of ev) {
    // the event must come during or up to 250 ms after a burst of the same tone
    const m = marks.findIndex((m, k) => !used.has(k) && e.at >= m.at && e.at <= m.end + fs * 0.25 && m.tone === e.tone);
    if (m >= 0) { used.add(m); hit++; if (marks[m].tone === 4) doneHit++; continue; }
    const any = marks.some((m) => e.at >= m.at && e.at <= m.end + fs * 0.25);
    const dup = marks.some((m) => m.tone === e.tone && e.at > m.end && e.at <= m.end + fs * 0.4);
    if (dup) { extraDup++; continue; }
    if (any) { wrong++; if (process.env.SIMDEBUG) console.log("    wrong", e, marks.filter((m) => e.at >= m.at - fs * 0.3 && e.at <= m.end + fs * 0.3)); } else extra++;
  }
  return { hit, wrong, extra, dup: extraDup, total: marks.length, doneHit, doneTotal: marks.filter((m) => m.tone === 4).length };
}

const SCEN = [
  // name, params. level = tone level at the mic (dBFS, steady part); noise/speech = broadband dBFS
  ["clean −35 dBFS, RT60 0.3", { level: -35, noise: -75, rt60: 0.3, drr: 6 }],
  ["typical laptop: −45 dBFS, fan −58, RT60 0.4, mild clip", { level: -45, noise: -58, rt60: 0.4, drr: 3, drive: 1.5 }],
  ["quiet mic gain: −55 dBFS, noise −72", { level: -55, noise: -72, rt60: 0.4, drr: 3 }],
  ["very quiet: −62 dBFS, noise −78", { level: -62, noise: -78, rt60: 0.4, drr: 3 }],
  ["loud fan: −45 dBFS, fan −47", { level: -45, noise: -47, rt60: 0.4, drr: 3 }],
  ["reverberant: RT60 0.6, DRR −3 dB, −45 dBFS", { level: -45, noise: -62, rt60: 0.6, drr: -3 }],
  ["phone at max: hard clip (drive 4), −40 dBFS", { level: -40, noise: -60, rt60: 0.4, drr: 3, drive: 4 }],
  ["speaker warm-up (first 0–40 ms lost), −45 dBFS", { level: -45, noise: -60, rt60: 0.4, drr: 3, warmup: 40 }],
  ["OS AGC still on, −50 dBFS", { level: -50, noise: -60, rt60: 0.4, drr: 3, agc: true }],
  ["talking nearby (speech −40 dBFS), tones −42", { level: -42, noise: -65, speech: -40, rt60: 0.4, drr: 3 }],
  ["44.1 kHz, −48 dBFS, fan −58", { level: -48, noise: -58, rt60: 0.4, drr: 3, fs: 44100 }],
];
const FP = [
  ["fan noise −45 dBFS", (fs, r) => scaleTo(fanNoise(fs * 60, fs, r), -45)],
  ["speech −35 dBFS", (fs, r) => scaleTo(speech(fs * 60, fs, r), -35)],
  ["speech −25 dBFS + fan −50", (fs, r) => add(scaleTo(speech(fs * 60, fs, r), -25), scaleTo(fanNoise(fs * 60, fs, r), -50))],
  ["keyboard clicks −30 dBFS + fan −60", (fs, r) => add(scaleTo(clicks(fs * 60, fs, r), -30), scaleTo(fanNoise(fs * 60, fs, r), -60))],
  ["white noise −30 dBFS", (fs, r) => { const y = new Float64Array(fs * 60); for (let i = 0; i < y.length; i++) y[i] = r.gauss(); return scaleTo(y, -30); }],
];

function run(file) {
  const QT1 = loadQT1(file), out = { file, scenarios: [], fp: [] };
  const ackMs = ACK_MS ? Number(ACK_MS) : 80;
  const only = opt("only", null), fpOnly = opt("fponly", null);
  for (const [si, [name, p0]] of SCEN.entries()) {
    if (fpOnly || (only && !only.split(",").includes(String(si)))) continue;
    const p = Object.assign({ fs: 48000, ackMs, gapMs: ackMs >= 120 ? 60 : 40 }, p0);
    let s = { hit: 0, wrong: 0, extra: 0, dup: 0, total: 0, doneHit: 0, doneTotal: 0 };
    for (let k = 0; k < TRIALS; k++) {
      const { sig, marks } = trial(QT1, p, 1000 + k * 7919 + name.length);
      const r = score(listen(QT1, sig, p.fs), marks, p.fs);
      for (const f in s) s[f] += r[f];
    }
    out.scenarios.push({ name, detect: s.hit / s.total, wrong: s.wrong, extra: s.extra, dup: s.dup, total: s.total, done: s.doneHit + "/" + s.doneTotal });
  }
  for (const [fi, [name, gen]] of FP.entries()) {
    if (only && !fpOnly) continue;
    if (fpOnly && fpOnly !== "all" && !fpOnly.split(",").includes(String(fi))) continue;
    let n = 0, ev = [];
    for (let k = 0; k < Math.max(1, Math.round(TRIALS / 3)); k++) {
      const fs = 48000, sig = quantize(gen(fs, rng(5000 + k * 31 + name.length)));
      ev = ev.concat(listen(QT1, sig, fs)); n++;
    }
    out.fp.push({ name, perMin: ev.length / n, tones: ev.map((e) => QT1.NAMES[e.tone]).join(" ") });
  }
  return out;
}

const res = run(htmlPath);
console.log("detector: " + path.basename(htmlPath) + (ACK_MS ? "  (ack " + ACK_MS + " ms)" : "  (ack 80 ms)") + (SENS ? "  sensitivity " + SENS : "") + (DONE_GAIN !== 1 ? "  DONE gain " + DONE_GAIN : ""));
for (const s of res.scenarios) console.log(`  ${(100 * s.detect).toFixed(0).padStart(4)} %  wrong ${String(s.wrong).padStart(2)}  dup ${String(s.dup).padStart(2)}  stray ${String(s.extra).padStart(2)}  / ${s.total}  DONE ${s.done}   ${s.name}`);
for (const f of res.fp) console.log(`  FP ${f.perMin.toFixed(1).padStart(5)} /min   ${f.name} ${f.tones ? "[" + f.tones.slice(0, 60) + "]" : ""}`);
if (opt("json")) fs.writeFileSync(opt("json"), JSON.stringify(res, null, 1));
module.exports = { trial, listen, score, loadQT1, rng };
