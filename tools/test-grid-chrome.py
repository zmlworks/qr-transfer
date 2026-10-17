#!/usr/bin/env python3
"""Headless Chrome (Playwright, system Chrome) checks of the 「一覧表示 (ページ)」 grid mode of web/sender.html (v0.4.0, + 0.5.0).
Decoding always uses the phone app's own 0.3.1 code on the JVM (FrameDecoder = ZXing multi/half/erase strategy,
Protocol.Assembler = order-free reassembly + CRC-32): tools/e2e/{GridSim,DecText,PixelCheck}.java.

1. sizing: at several window sizes and device pixel ratios the grid matches an independent recomputation (integer
   device px per module, codes and labels inside the window, maximal columns × rows); paging with → ← PageUp
   PageDown Space Shift+Space Home End and the buttons shows every chunk exactly once; resize keeps the first code.
2. crisp: each code on the screenshot is pixel-identical to its canvas bitmap (dpr 1, 1.25, 1.5, 2); the 0.3.1-style
   two-code view is measured for comparison (non-integer scaling).
3. click: cycling a code through masks 0–7 (and Shift+click EC levels) changes the symbol, the app's decoder reads
   the identical frame text from every variant, and the other codes are untouched.
4. receive: every page is screenshotted, a simulated camera (windows, bilinear rescale, blur, glare, noise) feeds the
   app's FrameDecoder + Assembler, which must reassemble the original bytes with a matching CRC-32 (two configs).
   0.5.0: the same page screenshots are also decoded with zxing-cpp 3.1.1 (the 0.4.0 app's default engine, via the
   Python binding of the same C++ core) for comparison.
5. tones: class tones only light the class lamps (no page change); (0.5.0) after the auto re-mask interval only the
   codes whose class was not heard are redrawn with the next mask (label m3↻); DONE (3050 Hz) shows 「受信完了」.
6. writes the documentation screenshot.
Usage: python3 tools/test-grid-chrome.py [screenshot.png]   (needs a prior ./gradlew compileDebugJavaWithJavac)
"""
import glob, hashlib, json, math, os, pathlib, subprocess, sys, tempfile
from playwright.sync_api import sync_playwright

ROOT = pathlib.Path(__file__).resolve().parent.parent
URL = (ROOT / "web" / "sender.html").as_uri()
SHOT = sys.argv[1] if len(sys.argv) > 1 else "/tmp/qr-sender-0.5.0-grid-screenshot.png"
CHROME = os.environ.get("CHROME", "/usr/bin/google-chrome")
import shutil
JAVA_HOME = os.environ.get("JAVA_HOME") or os.path.dirname(os.path.dirname(os.path.realpath(shutil.which("javac"))))
GH = os.environ.get("GRADLE_USER_HOME", os.path.expanduser("~/.gradle"))
JAR = glob.glob(GH + "/**/core-3.5.3.jar", recursive=True)[0]
CLS = str(ROOT / "app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes")
T = tempfile.mkdtemp(prefix="qrgrid-")
subprocess.run([JAVA_HOME + "/bin/javac", "-d", T, "-cp", JAR + ":" + CLS] + [str(ROOT / "tools/e2e" / f) for f in ("GridSim.java", "DecText.java", "PixelCheck.java", "CppBridge.java")], check=True)
os.environ.setdefault("ZXCPP_BRIDGE", str(ROOT / "tools/e2e/zxcpp_bridge.py"))
CP = ":".join([JAR, CLS, T])

def java(*args, props=()):
    r = subprocess.run([JAVA_HOME + "/bin/java"] + ["-D" + x for x in props] + ["-cp", CP] + list(args), capture_output=True, text=True)
    return r.returncode, r.stdout + r.stderr

TEXT_JS = """(n) => { var t = "電波を使わないデータ受け渡し — v0.4.0 grid test ✓\\n";
  var s = 12345; function rnd() { s = (s * 1103515245 + 12345) & 0x7fffffff; return s; }
  while (t.length < n) t += rnd().toString(36);
  document.getElementById("text").value = t; return t; }"""
SNAP_JS = """() => { var st = __qtSender.state(), g = st.grid;
  var cs = Array.prototype.slice.call(document.querySelectorAll('#gridarea canvas')).filter(function (c) { return c.style.display !== 'none'; }).map(function (c) {
    var r = c.getBoundingClientRect(); return { l: r.left, t: r.top, w: r.width, h: r.height, cw: c.width, ch: c.height, mpx: +c.dataset.mpx, i: +c.dataset.chunk, mask: +c.dataset.mask, ecl: c.dataset.ecl }; });
  var lb = Array.prototype.slice.call(document.querySelectorAll('#gridarea .glabel')).filter(function (c) { return c.style.display !== 'none'; }).map(function (c) {
    var r = c.getBoundingClientRect(); return { l: r.left, t: r.top, w: r.width, h: r.height, text: c.textContent }; });
  return { g: g, cs: cs, lb: lb, barTop: document.getElementById('gridbar').getBoundingClientRect().top, iw: innerWidth, ih: innerHeight,
           dpr: devicePixelRatio, total: st.total, frames: st.frames, hint: document.getElementById('ghint').textContent,
           page: document.getElementById('gpage').textContent }; }"""

def jsround(x): return math.floor(x + 0.5)

def start(p, chars=30000, msize=4, quiet=4, chunk="400", mic=False, ecc="M"):
    t = p.evaluate(TEXT_JS, chars)
    p.evaluate("""(o) => { document.getElementById('msize').value = o.msize; document.getElementById('quiet').value = String(o.quiet);
        document.getElementById('chunk').value = o.chunk; document.getElementById('gridmic').checked = o.mic; document.getElementById('ecc').value = o.ecc; }""",
               {"msize": msize, "quiet": quiet, "chunk": chunk, "mic": mic, "ecc": ecc})
    p.click("#start"); p.wait_for_function("() => __qtSender.state().grid && __qtSender.state().grid.drawn.length > 0")
    return t

def check_layout(s, msize, quiet):
    g, dpr = s["g"], s["dpr"]
    barH = s["ih"] - s["barTop"]
    W, H = math.floor(s["iw"] * dpr), math.floor((s["ih"] - barH) * dpr)
    mpx = max(1, jsround(msize * dpr)); labelH = math.ceil(14 * dpr); N = g["N"]
    side = (N + 2 * quiet) * mpx
    while mpx > 1 and (side > W or side + labelH > H): mpx -= 1; side = (N + 2 * quiet) * mpx
    cols, rows = max(1, W // side), max(1, H // (side + labelH))
    assert (g["W"], g["H"]) == (W, H), (g["W"], g["H"], W, H)
    assert (g["mpx"], g["side"], g["cols"], g["rows"], g["perPage"]) == (mpx, side, cols, rows, cols * rows), (g, mpx, side, cols, rows)
    assert g["pages"] == math.ceil(s["total"] / (cols * rows))
    assert len(s["cs"]) == min(cols * rows, s["total"] - g["page"] * cols * rows)
    boxes = []
    for c in s["cs"]:
        n = (c["cw"] // c["mpx"]) - 2 * quiet
        assert c["cw"] == (n + 2 * quiet) * c["mpx"] and c["cw"] == c["ch"], c
        assert abs(c["w"] * dpr - c["cw"]) < 0.02 and abs(c["h"] * dpr - c["ch"]) < 0.02, ("css size ≠ device size", c, dpr)
        assert abs(c["l"] * dpr - round(c["l"] * dpr)) < 0.03 and abs(c["t"] * dpr - round(c["t"] * dpr)) < 0.03, ("not on device pixel grid", c, dpr)
        assert c["l"] >= -0.01 and c["t"] >= -0.01 and c["l"] + c["w"] <= s["iw"] + 0.01 and c["t"] + c["h"] <= s["barTop"] + 0.01, ("outside", c)
        boxes.append(c)
    for a in range(len(boxes)):
        for b in range(a + 1, len(boxes)):
            A, B = boxes[a], boxes[b]
            assert A["l"] + A["w"] <= B["l"] + 0.01 or B["l"] + B["w"] <= A["l"] + 0.01 or A["t"] + A["h"] <= B["t"] + 0.01 or B["t"] + B["h"] <= A["t"] + 0.01, "overlap"
    for c, l in zip(s["cs"], s["lb"]):   # label under the code's quiet zone, never inside it
        assert l["t"] >= c["t"] + c["h"] - 0.01 and l["t"] + l["h"] <= s["barTop"] + 0.01, (c, l)
        assert l["text"].startswith("#%d " % (c["i"] + 1)), l["text"]
    return g

results = {}
with sync_playwright() as pw:
    b = pw.chromium.launch(executable_path=CHROME, headless=True, args=["--no-sandbox", "--autoplay-policy=no-user-gesture-required",
                                                                         "--use-fake-ui-for-media-stream", "--use-fake-device-for-media-stream"])
    def newpage(w, h, dpr, init=None):
        ctx = b.new_context(viewport={"width": w, "height": h}, device_scale_factor=dpr, permissions=["microphone"])
        if init: ctx.add_init_script(init)
        p = ctx.new_page(); errs = []
        p.on("pageerror", lambda e: errs.append(str(e)))
        p.goto(URL); p.errs = errs
        return ctx, p

    # ---- 1. sizing + paging ----
    for (w, h, dpr, msize, quiet) in [(1920, 1080, 1, 4, 4), (1366, 768, 1, 4, 4), (1280, 720, 1.5, 4, 4), (1440, 900, 2, 3, 2),
                                      (1280, 800, 1.25, 3, 4), (2560, 1440, 1, 6, 4), (800, 600, 1, 4, 3), (390, 844, 3, 4, 4)]:
        ctx, p = newpage(w, h, dpr)
        start(p, msize=msize, quiet=quiet)
        s = p.evaluate(SNAP_JS); g = check_layout(s, msize, quiet)
        total, seen = s["total"], []
        seen.append(g["shown"])
        for _ in range(g["pages"] - 1):
            p.keyboard.press("ArrowRight"); s = p.evaluate(SNAP_JS); check_layout(s, msize, quiet); seen.append(s["g"]["shown"])
        flat = [i for pg in seen for i in pg]
        assert sorted(flat) == list(range(total)) and len(flat) == total, "every chunk exactly once"
        assert s["page"] == "%d/%d" % (g["pages"], g["pages"]) and ("最終ページ" in s["hint"] or g["pages"] == 1), s["hint"]
        p.keyboard.press("ArrowRight"); assert p.evaluate(SNAP_JS)["g"]["page"] == g["pages"] - 1, "no wrap past the last page"
        if g["pages"] >= 3:
            p.keyboard.press("PageUp"); assert p.evaluate(SNAP_JS)["g"]["page"] == g["pages"] - 2
            p.keyboard.press("Home"); assert p.evaluate(SNAP_JS)["g"]["page"] == 0
            p.keyboard.press("Space"); assert p.evaluate(SNAP_JS)["g"]["page"] == 1
            p.keyboard.press("Shift+Space"); assert p.evaluate(SNAP_JS)["g"]["page"] == 0
            p.keyboard.press("PageDown"); assert p.evaluate(SNAP_JS)["g"]["page"] == 1
            p.click("#gnext"); assert p.evaluate(SNAP_JS)["g"]["page"] == 2
            p.click("#gprev"); p.keyboard.press("ArrowLeft"); assert p.evaluate(SNAP_JS)["g"]["page"] == 0
            p.keyboard.press("End"); assert p.evaluate(SNAP_JS)["g"]["page"] == g["pages"] - 1
            p.fill("#gjump", "3"); p.press("#gjump", "Enter"); assert 2 in p.evaluate(SNAP_JS)["g"]["shown"], "jump to #3"
        print("ok - %dx%d @%sx, %s px/module, quiet %d: %d×%d = %d codes/page (v%d, %d device px/module), %d chunks on %d pages, each once; keys/buttons/jump ok"
              % (w, h, dpr, msize, quiet, g["cols"], g["rows"], g["perPage"], (g["N"] - 17) // 4, g["mpx"], total, g["pages"]))
        assert not p.errs, p.errs
        ctx.close()
    # resize keeps the first visible code on screen
    ctx, p = newpage(1920, 1080, 1); start(p)
    p.keyboard.press("ArrowRight"); p.keyboard.press("ArrowRight")
    before = p.evaluate(SNAP_JS)["g"]; first = before["shown"][0]
    p.set_viewport_size({"width": 1280, "height": 720}); p.wait_for_timeout(300)
    s = p.evaluate(SNAP_JS); check_layout(s, 4, 4)
    assert first in s["g"]["shown"] and s["g"]["perPage"] != before["perPage"], (first, s["g"]["shown"])
    print("ok - resize 1920×1080 → 1280×720: %d → %d codes/page, chunk #%d still on screen (page %d)" % (before["perPage"], s["g"]["perPage"], first + 1, s["g"]["page"] + 1))
    # same content + chunk size again → same transfer ID (the receiver keeps its progress)
    id1 = s["frames"][0][4:12]; p.click("#gstop"); p.click("#start"); p.wait_for_function("() => __qtSender.state().grid")
    assert p.evaluate(SNAP_JS)["frames"][0][4:12] == id1
    print("ok - stop + start with the same content keeps transfer ID", id1)
    ctx.close()

    # ---- 2. crisp pixels: screenshot == canvas bitmap ----
    for dpr in (1, 1.25, 1.5, 2):
        ctx, p = newpage(1280, 800, dpr); start(p, chars=12000)
        s = p.evaluate(SNAP_JS)
        shot = os.path.join(T, "crisp-%s.png" % dpr); p.screenshot(path=shot)
        args = []
        for k, c in enumerate(s["cs"]):
            ref = os.path.join(T, "ref-%s-%d.png" % (dpr, k))
            data = p.evaluate("(k) => document.querySelectorAll('#gridarea canvas')[k].toDataURL('image/png')", k)
            import base64; open(ref, "wb").write(base64.b64decode(data.split(",")[1]))
            args += [ref, str(round(c["l"] * dpr)), str(round(c["t"] * dpr))]
        rc, out = java("PixelCheck", "match", shot, *args)
        assert rc == 0, out
        c0 = s["cs"][0]
        _, st = java("PixelCheck", "stats", shot, str(round(c0["l"] * dpr)), str(round(c0["t"] * dpr)), str(c0["cw"]), str(c0["ch"]))
        print("ok - dpr %s: %s; first code %s" % (dpr, out.strip().splitlines()[-1], st.strip()))
        results["crisp-%s" % dpr] = st.strip()
        # for comparison: 0.3.1-style two-code view at the same dpr (canvas scaled by CSS to 100 % / 80vh)
        p.click("#gstop"); p.check("#vtwo"); p.check("#loop"); p.click("#start"); p.wait_for_timeout(400)
        r = p.evaluate("() => { var c = document.getElementById('qrA'), r = c.getBoundingClientRect(); return { l: r.left, t: r.top, w: r.width, h: r.height, cw: c.width }; }")
        shot2 = os.path.join(T, "classic-%s.png" % dpr); p.screenshot(path=shot2)
        x, y, wd = math.ceil(r["l"] * dpr), math.ceil(r["t"] * dpr), math.floor(r["w"] * dpr) - 2
        _, st2 = java("PixelCheck", "stats", shot2, str(x), str(y), str(wd), str(wd))
        print("   (two-code view, unchanged from 0.3.1: canvas %d px shown at %.2f device px → scale %.3f; %s)" % (r["cw"], r["w"] * dpr, r["w"] * dpr / r["cw"], st2.strip()))
        results["classic-%s" % dpr] = "scale %.3f; %s" % (r["w"] * dpr / r["cw"], st2.strip())
        ctx.close()

    # ---- 3. click → same payload, different symbol ----
    ctx, p = newpage(1920, 1080, 1); start(p)
    s = p.evaluate(SNAP_JS); k = 3; c = s["cs"][k]; i = c["i"]; frame = s["frames"][i]
    loc = p.locator("#gridarea canvas").nth(k)
    others = [p.locator("#gridarea canvas").nth(j).evaluate("c => c.toDataURL()") for j in range(len(s["cs"])) if j != k]
    imgs, masks = [], []
    for step in range(8):
        f = os.path.join(T, "click-%d.png" % step); loc.screenshot(path=f); imgs.append(f)
        cur = p.evaluate(SNAP_JS)["cs"][k]; masks.append((cur["mask"], cur["ecl"]))
        loc.click(); p.wait_for_timeout(50)
    after = p.evaluate(SNAP_JS)
    assert after["cs"][k]["mask"] == masks[0][0] and after["lb"][k]["text"].startswith("#%d  m%d*" % (i + 1, masks[0][0])), after["lb"][k]
    assert len(set(m for m, _ in masks)) == 8, masks
    for e in ("Q", "H", "L"):
        loc.click(modifiers=["Shift"]); p.wait_for_timeout(50)
        f = os.path.join(T, "click-ecl-%s.png" % e); loc.screenshot(path=f); imgs.append(f)
        cur = p.evaluate(SNAP_JS)["cs"][k]; masks.append((cur["mask"], cur["ecl"]))
    assert [x[1] for x in masks[8:]] == ["Q", "H", "L"], masks
    rc, out = java("DecText", *imgs)
    texts = {}
    for line in out.strip().splitlines():
        fn, t = line.split("\t", 1); texts.setdefault(fn, set()).add(t)
    assert all(texts.get(f) == {frame} for f in imgs), {os.path.basename(f): (texts.get(f) or {"<none>"}) for f in imgs if texts.get(f) != {frame}}
    hashes = {hashlib.sha256(open(f, "rb").read()).hexdigest() for f in imgs}
    assert len(hashes) == len(imgs), "every variant is a different image"
    assert others == [p.locator("#gridarea canvas").nth(j).evaluate("c => c.toDataURL()") for j in range(len(s["cs"])) if j != k], "other codes unchanged"
    print("ok - click: chunk #%d drawn with masks %s and EC levels Q/H/L: %d different symbols, the app's decoder reads the identical frame text from all; other codes untouched"
          % (i + 1, [m for m, _ in masks[:8]], len(imgs)))
    ctx.close()

    # ---- 4. simulated receive of all pages through the app's decoder + assembler ----
    def sim(pages, frac="0.5", engine="java"):
        rc, out = java("GridSim", "1" if frac != "full" else "0", frac if frac != "full" else "1", *pages, props=("engine=" + engine,))
        read = set()
        for l in out.splitlines():
            if "read=[" in l: read |= {int(x) for x in l.split("read=[")[1].rstrip("]").split(",") if x.strip()}
        return rc, out, read
    def receive(w, h, dpr, msize, quiet, chunk, chars, name):
        ctx, p = newpage(w, h, dpr); text = start(p, chars=chars, msize=msize, quiet=quiet, chunk=chunk)
        g = p.evaluate(SNAP_JS)["g"]; pages = []; total = len(p.evaluate(SNAP_JS)["frames"])
        for pg in range(g["pages"]):
            f = os.path.join(T, "%s-p%02d.png" % (name, pg)); p.screenshot(path=f); pages.append(f)
            p.keyboard.press("ArrowRight")
        want = hashlib.sha256(text.encode("utf-8")).hexdigest()
        rc, out, read = sim(pages)
        missing = sorted(set(range(1, total + 1)) - read)
        notes = []
        # what a user does: the receiver lists the missing numbers → "#" jump → redraw (click) until it is read
        extra = []
        for m in missing:
            p.fill("#gjump", str(m)); p.press("#gjump", "Enter")
            s = p.evaluate(SNAP_JS); k = s["g"]["shown"].index(m - 1); loc = p.locator("#gridarea canvas").nth(k)
            f = os.path.join(T, "%s-retry-%d-same.png" % (name, m)); p.screenshot(path=f)
            same = m in sim([f])[2]
            got = None
            for c in range(1, 8):
                loc.click(); f = os.path.join(T, "%s-retry-%d-click%d.png" % (name, m, c)); p.screenshot(path=f)
                if m in sim([f])[2]: got = (c, p.evaluate(SNAP_JS)["cs"][k]["mask"]); extra.append(f); break
            assert got, "chunk #%d unreadable with every mask" % m
            notes.append("#%d (mask m%d) missed; same symbol again: %s; read after %d click(s) (mask m%d)" % (m, s["cs"][k]["mask"], "read" if same else "not read", got[0], got[1]))
        rc, out, read = sim(pages + extra)
        assert rc == 0 and "ASSEMBLED OK" in out and want in out, out
        _, full, fread = sim(pages, "full")
        # 0.5.0: the same first pass with zxing-cpp (0.4.0 app default), and zxing-cpp + 2× upscale
        crc, cpp, cread = sim(pages, engine="cpp")
        cms = [l for l in cpp.splitlines() if l.startswith("decode ms")]
        if not cms:   # needs a python with zxing-cpp 3.1.1 (env ZXCPP_PY), see tools/e2e/zxcpp_bridge.py
            print("   (zxing-cpp comparison skipped: bridge not available)")
        else:
            print("   zxing-cpp first pass (simulated camera, same frames): read %d/%d  %s" % (len(cread), total, cms[0]))
            results.setdefault("cpp", {})[name] = {"read": len(cread), "total": total, "java_read": total - len(missing), "timing": cms}
        summary = [l for l in out.splitlines() if l.startswith("frames=") or l.startswith("ASSEMBLED")]
        fulls = [l for l in full.splitlines() if l.startswith("frames=")]
        print("ok - receive %s: %d chunks on %d pages (%d/page); first pass read %d/%d%s; %s | whole-page frames (no camera crop): %s, %d chunks"
              % (name, total, g["pages"], g["perPage"], total - len(missing), total, (" — " + "; ".join(notes)) if notes else "", " ".join(summary), " ".join(fulls), len(fread)))
        results["receive-" + name] = {"chunks": total, "pages": g["pages"], "perPage": g["perPage"], "first_pass_missing": missing, "retries": notes, "sim": summary, "full": fulls, "full_read": len(fread)}
        ctx.close()
    receive(1920, 1080, 1, 4, 4, "400", 60000, "1920x1080-400B")
    receive(1440, 900, 2, 3, 2, "200", 20000, "1440x900@2x-200B-quiet2")

    # ---- 5. tones: classes only light lamps; DONE → 受信完了 ----
    FAKE = """(() => { navigator.mediaDevices.getUserMedia = async function () {
        var ctx = new AudioContext(), dst = ctx.createMediaStreamDestination();
        window.__beep = function (hz, ms) { var o = ctx.createOscillator(), g = ctx.createGain(), t0 = ctx.currentTime + 0.05, d = ms / 1000;
          o.frequency.value = hz; g.gain.setValueAtTime(0, t0); g.gain.linearRampToValueAtTime(0.05, t0 + 0.01);
          g.gain.setValueAtTime(0.05, t0 + d - 0.01); g.gain.linearRampToValueAtTime(0, t0 + d); o.connect(g); g.connect(dst); o.start(t0); o.stop(t0 + d + 0.02); };
        var tr = dst.stream.getAudioTracks()[0]; tr.getSettings = function () { return { echoCancellation: false, noiseSuppression: false, autoGainControl: false }; };
        return dst.stream; }; })();"""
    ctx, p = newpage(1920, 1080, 1, init=FAKE); start(p, mic=True)
    p.wait_for_function("() => __qtSender.state().mic"); p.wait_for_timeout(800)
    page0 = p.evaluate(SNAP_JS)["g"]["page"]
    p.evaluate("() => __beep(1350, 120)"); p.wait_for_timeout(700)
    p.evaluate("() => __beep(2400, 120)"); p.wait_for_timeout(700)
    s = p.evaluate(SNAP_JS)
    assert s["g"]["heard"][0] and s["g"]["heard"][3] and not s["g"]["heard"][1] and s["g"]["page"] == page0 and not s["g"]["done"], s["g"]["heard"]
    assert "まだ確認音が来ていない組" in s["hint"], s["hint"]
    # 0.5.0 auto re-mask: after the interval (3 s) only codes of the classes not heard on this page change mask
    before = {c["i"]: (c["mask"], c["ecl"]) for c in s["cs"]}
    p.wait_for_function("() => __qtSender.state().grid.remasks > 0", timeout=8000)
    s = p.evaluate(SNAP_JS)
    for c, l in zip(s["cs"], s["lb"]):
        heard = s["g"]["heard"][c["i"] % 4 if False else ((c["i"] & 1) * 2 + ((c["i"] >> 1) & 1))]
        auto = s["g"]["masks"][str(c["i"])]["auto"]
        if heard: assert auto == 0 and before[c["i"]] == (c["mask"], c["ecl"]), ("heard class re-masked", c, l)
        else: assert auto >= 1 and before[c["i"]] != (c["mask"], c["ecl"]) and "↻" in l["text"], ("unheard class not re-masked", c, l)
    n_auto = sum(1 for c in s["cs"] if s["g"]["masks"][str(c["i"])]["auto"])
    assert s["g"]["page"] == page0, "auto page advance must stay off"
    p.screenshot(path=SHOT)
    p.evaluate("() => __beep(3050, 400)"); p.wait_for_function("() => __qtSender.state().grid.done", timeout=5000)
    assert p.is_visible("#gdone")
    print("ok - tones: A0/B1 only lit their class lamps and the 'no ack yet' hint (page unchanged, nothing marked read); "
          "auto re-mask redrew only the %d codes of the unheard classes A1/B0 (m↻), page unchanged; DONE 3050 Hz → 「受信完了」" % n_auto)
    ctx.close()
    b.close()
print("screenshot:", SHOT)
json.dump(results, open(os.path.join(T, "results.json"), "w"), indent=1, ensure_ascii=False)
print("results:", os.path.join(T, "results.json"))
print("grid browser tests passed")
