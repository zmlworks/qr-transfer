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
  // tone detector sanity
  const sine = (hz, n, rate) => Float32Array.from({ length: n }, (_, i) => 0.3 * Math.sin(2 * Math.PI * hz * i / rate));
  assert(QT1.toneRatio(sine(1900, 512, 48000), 1900, 48000) > 0.9);
  assert(QT1.toneRatio(sine(1900, 512, 48000), 2850, 48000) < 0.05);

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
