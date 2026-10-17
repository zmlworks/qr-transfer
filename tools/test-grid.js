// v0.4.0 grid sender, DOM-free parts under node:
//  1. QRSym.remask(m) == the bundled generator's own output for mask m (generator patched IN THIS TEST ONLY to expose
//     its internal makeImpl), all versions 1–40 at M and every 3rd version at L/Q/H, all 8 masks;
//  2. payload unchanged: every variant has the same version/EC level, only data modules + format info differ;
//  3. QTGrid.layout invariants at many window sizes / pixel ratios (integer module px, everything inside the area,
//     max possible columns/rows, pages × perPage ≥ total, every chunk on exactly one page);
//  4. writes a fixture of all 8 variants + JS penalties for tools/e2e/MaskParity.java (ZXing's encoder/penalty check).
// Usage: node tools/test-grid.js [fixture-out]
"use strict";
const fs = require("fs"), path = require("path"), vm = require("vm"), assert = require("assert");
const html = fs.readFileSync(path.join(__dirname, "..", "web", "sender.html"), "utf8");
const script = (id) => html.match(new RegExp('<script id="' + id + '">([\\s\\S]*?)</script>'))[1];
const ctx = { Math, Uint8Array }; vm.createContext(ctx);
vm.runInContext(script("qrlib"), ctx);
vm.runInContext(script("qrsym") + "\nthis.QRSym = QRSym; this.QTGrid = QTGrid;", ctx);
const { QRSym, QTGrid } = ctx;
const ref = {}; vm.createContext(ref);
vm.runInContext(script("qrlib").replace("_this.make = function() {", "_this.__mk = function(m) { makeImpl(false, m); };\n    _this.make = function() {") + "\nthis.qrcode = qrcode;", ref);
const B45 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:";
const CAP = { L: [25, 47, 77, 114, 154, 195, 224, 279, 335, 395, 468, 535, 619, 667, 758, 854, 938, 1046, 1153, 1249, 1352, 1460, 1588, 1704, 1853, 1990, 2132, 2223, 2369, 2520, 2677, 2840, 3009, 3183, 3351, 3537, 3729, 3927, 4087, 4296],
              M: [20, 38, 61, 90, 122, 154, 178, 221, 262, 311, 366, 419, 483, 528, 600, 656, 734, 816, 909, 970, 1035, 1134, 1248, 1326, 1451, 1542, 1637, 1732, 1839, 1994, 2113, 2238, 2369, 2506, 2632, 2780, 2894, 3054, 3220, 3391],
              Q: [16, 29, 47, 67, 87, 108, 125, 157, 189, 221, 259, 296, 352, 376, 426, 470, 531, 574, 644, 702, 742, 823, 890, 963, 1041, 1094, 1172, 1263, 1322, 1429, 1499, 1618, 1700, 1787, 1867, 1966, 2071, 2181, 2298, 2420],
              H: [10, 20, 35, 50, 64, 84, 93, 122, 143, 174, 200, 227, 259, 283, 321, 365, 408, 452, 493, 557, 587, 640, 672, 744, 779, 864, 910, 958, 1016, 1080, 1150, 1226, 1307, 1394, 1431, 1530, 1591, 1658, 1774, 1852] };  // ISO 18004 table 7, alphanumeric
const text = (len, seed) => { let t = ""; for (let i = 0; i < len; i++) t += B45[(i * 7 + seed * 13 + (i >> 3)) % 45]; return t; };

let n1 = 0;
for (const ecl of ["L", "M", "Q", "H"]) for (let v = 1; v <= 40; v += (ecl === "M" ? 1 : 3)) {
  const t = text(CAP[ecl][v - 1], v), b = QRSym.base(t, ecl);
  assert.strictEqual(b.version, v, `capacity table: ${ecl} v${v}`);
  const q = ref.qrcode(0, ecl); q.addData(t, "Alphanumeric"); q.make();
  const fn = QRSym.functionMap(v);
  for (let m = 0; m < 8; m++) {
    q.__mk(m);
    const mine = QRSym.remask(b, m);
    for (let r = 0; r < b.n; r++) for (let c = 0; c < b.n; c++)
      assert.strictEqual(mine[r * b.n + c], q.isDark(r, c) ? 1 : 0, `remask ${ecl} v${v} m${m} at ${r},${c}`);
    // only data modules and the format information may differ between masks
    const m0 = QRSym.remask(b, 0);
    for (let k = 0; k < b.n * b.n; k++) if (!fn[k]) continue; else {
      const r = Math.floor(k / b.n), c = k % b.n, fmt = (r === 8 && (c <= 8 || c >= b.n - 8)) || (c === 8 && (r <= 8 || r >= b.n - 8));
      if (!fmt) assert.strictEqual(mine[k], m0[k], `function module changed ${ecl} v${v} m${m} ${r},${c}`);
    }
    n1++;
  }
}
console.log(`ok - remask == generator output for ${n1} symbol/mask combinations (versions 1–40)`);

// layout
let n2 = 0;
for (const [wc, hc] of [[1920, 1080], [1366, 768], [1280, 720], [2560, 1440], [800, 600], [390, 844], [3840, 2160], [300, 200]])
  for (const dpr of [1, 1.25, 1.5, 2, 3]) for (const msize of [2, 3, 4, 6, 12]) for (const quiet of [2, 4]) for (const N of [41, 81, 101]) {
    const W = Math.floor(wc * dpr), H = Math.floor((hc - 64) * dpr), total = 137;
    const L = QTGrid.layout({ W, H, dpr, msize, quiet, N, total, labelCss: 14 });
    assert(Number.isInteger(L.mpx) && L.mpx >= 1);
    if (!L.shrunk) assert.strictEqual(L.mpx, Math.max(1, Math.round(msize * dpr)));
    assert.strictEqual(L.side, (N + 2 * quiet) * L.mpx);
    if (L.side <= W && L.cellH <= H) {
      assert(L.ox + L.cols * L.side <= W && L.oy + L.rows * L.cellH <= H, "inside");
      assert((L.cols + 1) * L.side > W && (L.rows + 1) * L.cellH > H, "maximal");
    } else assert(L.mpx === 1 && L.cols === 1 && L.rows === 1);
    const seen = new Array(total).fill(0);
    for (let p = 0; p < L.pages; p++) QTGrid.pageChunks(L, total, p).forEach((i) => seen[i]++);
    assert(seen.every((x) => x === 1), "every chunk exactly once");
    assert(QTGrid.pageChunks(L, total, L.pages - 1).length > 0);
    n2++;
  }
console.log(`ok - layout invariants at ${n2} window/dpr/module/quiet/version combinations`);

// fixture for ZXing parity (tools/e2e/MaskParity.java)
const out = process.argv[2];
if (out) {
  const lines = [];
  for (const ecl of ["L", "M", "Q", "H"]) for (const len of [20, 77, 300, 452, 632, 1100]) {
    const t = "QT1:0A1B2C3D:" + len + ":999:DEADBEEF:" + text(Math.max(1, len - 30), len);
    const v = QRSym.variants(t, ecl);
    lines.push([ecl, t, v.best, v.penalty.join(","), v.mats.map((m) => Array.from(m).join("")).join(",")].join("\t"));
  }
  fs.writeFileSync(out, lines.join("\n") + "\n");
  console.log("ok - wrote", lines.length, "parity cases to", out);
}
console.log("grid tests passed");
