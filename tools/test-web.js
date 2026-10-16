// Runs the protocol + QR code from web/sender.html under node and writes JVM test fixtures,
// so the Android unit tests prove that frames made by the browser sender decode on the phone.
// Usage: node tools/test-web.js   (node 18+)
"use strict";
const fs = require("fs"), path = require("path"), vm = require("vm"), assert = require("assert"), crypto = require("crypto");
const html = fs.readFileSync(path.join(__dirname, "..", "web", "sender.html"), "utf8");
const script = (id) => html.match(new RegExp('<script id="' + id + '">([\\s\\S]*?)</script>'))[1];
const ctx = { TextEncoder, CompressionStream, Response, crypto: crypto.webcrypto, Uint8Array, Uint32Array, Math };
vm.createContext(ctx);
vm.runInContext(script("qrlib"), ctx);
vm.runInContext(script("protocol") + "\nthis.QT1 = QT1;", ctx);
const QT1 = ctx.QT1;

(async () => {
  // CRC-32 check value and base45 RFC 9285 vectors
  assert.strictEqual(QT1.hex8(QT1.crc32(new TextEncoder().encode("123456789"))), "CBF43926");
  const b45 = (s) => { const b = new TextEncoder().encode(s); return QT1.base45(b, 0, b.length); };
  assert.strictEqual(b45("AB"), "BB8");
  assert.strictEqual(b45("Hello!!"), "%69 VD92EX0");
  assert.strictEqual(b45("base-45"), "UJCLQE7W581");
  // tone detector: three tones, sequences, noise, chunked input at a typical PC rate (48 kHz)
  const R = 48000;
  const sine = (hz, n, rate, amp) => Float32Array.from({ length: n }, (_, i) => (amp || 0.3) * Math.sin(2 * Math.PI * hz * i / rate));
  assert(QT1.toneRatio(sine(QT1.A_HZ, 768, R), QT1.A_HZ, R) > 0.9);
  assert(QT1.toneRatio(sine(QT1.A_HZ, 768, R), QT1.B_HZ, R) < 0.02);
  assert(QT1.toneRatio(sine(QT1.B_HZ, 768, R), QT1.DONE_HZ, R) < 0.02);
  const tone = (hz, ms, amp) => { const n = Math.round(R * ms / 1000), f = R / 100;
    return Float32Array.from({ length: n }, (_, i) => { const env = i < f ? 0.5 - 0.5 * Math.cos(Math.PI * i / f) : i > n - f ? 0.5 - 0.5 * Math.cos(Math.PI * (n - i) / f) : 1;
      return amp * env * Math.sin(2 * Math.PI * hz * i / R); }); };
  const gap = (ms) => new Float32Array(Math.round(R * ms / 1000));
  const cat = (...p) => { const o = new Float32Array(p.reduce((a, x) => a + x.length, 0)); let k = 0; p.forEach((x) => { o.set(x, k); k += x.length; }); return o; };
  let seed = 7; const rnd = () => (seed = (seed * 1103515245 + 12345) % 2147483648) / 2147483648;
  const gauss = () => Math.sqrt(-2 * Math.log(rnd() + 1e-12)) * Math.cos(2 * Math.PI * rnd());
  const noisy = (x, amp) => x.map((v) => v + amp * gauss());
  const listen = (sig) => { const l = new QT1.Listener(R), ev = []; for (let i = 0; i < sig.length; i += 2048) ev.push(...l.feed(sig.subarray(i, i + 2048))); return ev; };
  let sig = cat(gap(100), tone(QT1.A_HZ, 100, 0.05), gap(40), tone(QT1.B_HZ, 100, 0.05), gap(150), tone(QT1.B_HZ, 100, 0.05), gap(90),
    tone(QT1.A_HZ, 100, 0.05), gap(40), tone(QT1.DONE_HZ, 450, 0.05), gap(100));
  assert.deepStrictEqual(listen(noisy(sig, 0.01)), ["A", "B", "B", "A", "DONE"]);
  assert.deepStrictEqual(listen(noisy(new Float32Array(R * 2), 0.2)), []);                    // loud noise only
  assert.deepStrictEqual(listen(cat(gap(50), tone(1900, 200, 0.3), gap(50), tone(2850, 500, 0.3))), []);   // 0.1.0 tones
  assert.deepStrictEqual(listen(cat(gap(50), tone(QT1.DONE_HZ, 60, 0.3), gap(50))), []);       // too short for DONE
  console.log("ok - JS tone listener (A/B/DONE, noise, chunked)");

  // scheduler: random event script; the Java test replays it on SlotScheduler and must see the same states
  let script = "";
  for (const [total, slots, ack] of [[9, 2, true], [10, 1, true], [7, 2, false], [1, 2, true], [2, 2, true]]) {
    const S = QT1.Scheduler(total, slots, 250, ack);
    let t = 0;
    S.start(t);
    script += `#sched ${total} ${slots} ${ack ? 1 : 0}\n`;
    const state = () => `${S.shown(0)} ${S.shown(1)} ${S.ackedCount()} ${S.rounds()} ${S.timeoutMs()} ${S.nextDeadline(t)}`;
    script += `start ${t} -> ${state()}\n`;
    for (let k = 0; k < 400; k++) {
      t += Math.floor(rnd() * 400);
      const r = rnd();
      if (r < 0.55) { const tone = rnd() < 0.5 ? 0 : 1; const c = S.onAck(tone, t); script += `ack ${tone} ${t} ${c ? 1 : 0} -> ${state()}\n`; }
      else if (r < 0.98) { const c = S.tick(t); script += `tick ${t} ${c ? 1 : 0} -> ${state()}\n`; }
      else { const s = Math.floor(rnd() * 2), d = rnd() < 0.5 ? -1 : 1; S.step(s, d, t); script += `step ${s} ${d} ${t} -> ${state()}\n`; }
    }
  }
  fs.writeFileSync(path.join(__dirname, "..", "app", "src", "test", "resources", "web_sched.txt"), script);
  console.log("ok - JS scheduler script written");

  const cases = [];
  const jp = "電波を使わないデータ受け渡し。".repeat(40) + "\nEnd ✓";
  cases.push({ name: "jp-text-deflate", isFile: false, fname: "", data: new TextEncoder().encode(jp), chunk: 400 });
  cases.push({ name: "random-binary-file", isFile: true, fname: "テスト.bin", data: new Uint8Array(crypto.randomBytes(3001)), chunk: 200 });
  const rep = new Uint8Array(5000); for (let i = 0; i < rep.length; i++) rep[i] = i % 7;
  cases.push({ name: "compressible-file", isFile: true, fname: "a.dat", data: rep, chunk: 700 });
  cases.push({ name: "tiny", isFile: false, fname: "", data: new TextEncoder().encode("hi"), chunk: 400 });
  let out = "";
  for (const c of cases) {
    const fr = await QT1.frames("0BADF00D", c.isFile, c.fname, c.data, c.chunk, true);
    fr.forEach((f) => assert(/^[0-9A-Z $%*+\-.\/:]+$/.test(f), "non-alphanumeric char in frame"));
    out += `#case ${c.name} ${c.isFile ? 1 : 0} ${Buffer.from(c.fname).toString("base64") || "-"} ${Buffer.from(c.data).toString("base64")}\n`;
    out += fr.join("\n") + "\n";
    console.log(`ok - ${c.name}: ${c.data.length} B -> ${fr.length} frame(s)`);
  }
  fs.writeFileSync(path.join(__dirname, "..", "app", "src", "test", "resources", "web_frames.txt"), out);

  // a QR symbol from the bundled JS generator, as a 0/1 matrix for ZXing to decode in the JVM test
  const frame = (await QT1.frames("CAFEBABE", false, "", new TextEncoder().encode("QR from the browser ✓ ".repeat(12)), 400, false))[0];
  const qr = ctx.qrcode(0, "M"); qr.addData(frame, "Alphanumeric"); qr.make();
  const n = qr.getModuleCount(); let m = frame + "\n";
  for (let r = 0; r < n; r++) { let line = ""; for (let c = 0; c < n; c++) line += qr.isDark(r, c) ? "1" : "0"; m += line + "\n"; }
  fs.writeFileSync(path.join(__dirname, "..", "app", "src", "test", "resources", "web_qr_matrix.txt"), m);
  console.log(`ok - JS QR matrix ${n}x${n} (version ${(n - 17) / 4}) written`);
  console.log("web sender protocol tests passed");
})().catch((e) => { console.error(e); process.exit(1); });
