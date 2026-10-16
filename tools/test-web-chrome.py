#!/usr/bin/env python3
"""Headless Chrome (Playwright, system Chrome) checks of web/sender.html, v0.3.0.

1. fake mic (Chrome's fake audio device beeps): MIC-ACK mode, and the codes must NEVER advance without a matching
   ack — the 0.2.0 bug was a ~600 ms auto-advance.
2. mic refused (getUserMedia rejects NotAllowedError): LOOP mode, flagged on the page, codes cycle.
3. acoustic loop: getUserMedia replaced by a synthetic stream fed by an in-page simulated receiver (camera reads the
   shown chunks with random loss, alternating-bit acks played as real oscillator tones with random ack loss, re-acks,
   DONE). Must finish, never move past a chunk the receiver lacks, show each slot's chunks in order, and the received
   frames must reassemble (base45 → envelope → inflate → CRC-32) to the original text.
Usage: python3 tools/test-web-chrome.py [screenshot.png]      (pip install playwright; uses /usr/bin/google-chrome)
"""
import base64, json, os, pathlib, sys, time, zlib
from playwright.sync_api import sync_playwright

ROOT = pathlib.Path(__file__).resolve().parent.parent
URL = (ROOT / "web" / "sender.html").as_uri()
SHOT = sys.argv[1] if len(sys.argv) > 1 else "/tmp/qr-sender-0.3.0-screenshot.png"
CHROME = os.environ.get("CHROME", "/usr/bin/google-chrome")
B45 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:"

def b45dec(s):
    out = bytearray()
    for i in range(0, len(s), 3):
        c = [B45.index(ch) for ch in s[i:i + 3]]
        if len(c) == 3:
            n = c[0] + c[1] * 45 + c[2] * 2025; out += bytes([n >> 8, n & 255])
        else:
            out.append(c[0] + c[1] * 45)
    return bytes(out)

def assemble(frames):
    parts = {}
    for f in frames:
        _, fid, idx, total, crc, data = f.split(":", 5)
        parts[int(idx)] = b45dec(data)
    total = int(frames[0].split(":")[3]); crc = frames[0].split(":")[4]
    assert sorted(parts) == list(range(total)), "missing chunks"
    env = b"".join(parts[i] for i in range(total))
    flags, nl = env[1], (env[2] << 8) | env[3]
    payload = env[4 + nl:]
    if flags & 2: payload = zlib.decompress(payload, -15)
    assert "%08X" % (zlib.crc32(payload) & 0xffffffff) == crc, "CRC mismatch"
    return payload

TEXT_JS = """() => { var t = "電波を使わないデータ受け渡し — v0.3.0 headless test ✓\\n";
  for (var i = 0; i < 600; i++) t += Math.random().toString(36).slice(2);
  document.getElementById("text").value = t; document.getElementById("fps").value = "6"; document.getElementById("chunk").value = "200"; return t; }"""

# simulated receiver: camera (30 fps, 70 % read, ~100 ms decode), AckPolicy (dedupe, re-ack 700 ms), FIFO tone player
# (80 ms tones, 40 ms gap, 25 % of acks lost), DONE when complete (repeated every 2 s while codes are seen)
FAKE_MIC = """
(() => {
  window.__rx = { have: {}, frames: {}, acks: 0, lost: 0, done: false, violations: [] };
  navigator.mediaDevices.getUserMedia = async function () {
    var ctx = new AudioContext(), dst = ctx.createMediaStreamDestination(), freeAt = 0, rx = window.__rx;
    var last = [-1, -1], at = [-1e9, -1e9], doneAt = -1e9, queue = [];
    function play(tone) {
      if (queue.indexOf(tone) >= 0) return;
      queue.push(tone);
    }
    setInterval(function () {               // player thread
      if (!queue.length || ctx.currentTime < freeAt - 0.02) return;
      var tone = queue.shift(), dur = tone === 4 ? 0.4 : 0.08, t0 = Math.max(ctx.currentTime + 0.05, freeAt);
      freeAt = t0 + dur + 0.04;
      if (tone !== 4 && Math.random() < 0.25) { rx.lost++; return; }
      var o = ctx.createOscillator(), g = ctx.createGain();
      o.frequency.value = QT1.HZ[tone];
      g.gain.setValueAtTime(0, t0); g.gain.linearRampToValueAtTime(0.25, t0 + 0.01);
      g.gain.setValueAtTime(0.25, t0 + dur - 0.01); g.gain.linearRampToValueAtTime(0, t0 + dur);
      o.connect(g); g.connect(dst); o.start(t0); o.stop(t0 + dur + 0.01);
    }, 5);
    setInterval(function () {               // camera
      var st = window.__qtSender.state();
      if (!st.running && !rx.done) return;
      for (var s = 0; s < st.slots; s++) {
        var i = st.shown[s];
        if (i < 0 || Math.random() > 0.7) continue;
        (function (i, frame) { setTimeout(function () {
          var now = performance.now();
          if (rx.done) { if (now - doneAt >= 2000) { doneAt = now; play(4); } return; }
          rx.have[i] = true; rx.frames[i] = frame;
          if (Object.keys(rx.have).length === st.total) { rx.done = true; doneAt = now; play(4); return; }
          var sl = i & 1;
          if (i !== last[sl] || now - at[sl] >= 700) { last[sl] = i; at[sl] = now; rx.acks++; play(QT1.toneOf(i)); }
        }, 70 + Math.random() * 60); })(i, st.frames[i]);
      }
    }, 33);
    return dst.stream;
  };
})();
"""

def page_with(browser, init=None, perms=None):
    ctx = browser.new_context(viewport={"width": 1400, "height": 900}, permissions=perms or [])
    if init: ctx.add_init_script(init)
    p = ctx.new_page()
    p.on("pageerror", lambda e: (_ for _ in ()).throw(AssertionError("page error: %s" % e)))
    p.goto(URL)
    return ctx, p

def state(p): return p.evaluate("() => window.__qtSender.state()")
def log(p): return p.evaluate("() => window.__qtSender.log")

with sync_playwright() as pw:
    common = ["--autoplay-policy=no-user-gesture-required", "--no-sandbox"]
    # ---- 1. Chrome's fake microphone: ack mode, no advance at all ----
    b = pw.chromium.launch(executable_path=CHROME, headless=True,
                           args=common + ["--use-fake-ui-for-media-stream", "--use-fake-device-for-media-stream"])
    ctx, p = page_with(b, perms=["microphone"])
    p.evaluate(TEXT_JS); p.click("#start")
    p.wait_for_function("() => window.__qtSender.state().running")
    time.sleep(6)
    st = state(p)
    assert st["mode"] == "ack", st["mode"]
    assert st["shown"] == [0, 1], ("advanced without ack!", st["shown"], log(p))
    assert st["good"] == 0, st
    assert "MIC-ACK" in p.inner_text("#mode")
    assert "A0" in p.inner_text("#slots") and "B0" in p.inner_text("#slots")
    shows = [e for e in log(p) if "show" in e]
    assert len(shows) == 2, shows
    print("ok - fake mic: MIC-ACK mode, held chunks 1/2 for 6 s without acks (%d stale/foreign tones ignored)" % st["stale"])
    ctx.close(); b.close()

    b = pw.chromium.launch(executable_path=CHROME, headless=True, args=common)
    # ---- 2. microphone refused: loop mode, clearly flagged ----
    ctx, p = page_with(b, init="navigator.mediaDevices.getUserMedia = async () => { throw new DOMException('denied', 'NotAllowedError'); };")
    p.evaluate(TEXT_JS); p.click("#start")
    p.wait_for_function("() => window.__qtSender.state().running")
    time.sleep(2)
    st = state(p)
    assert st["mode"] == "loop", st
    m = p.inner_text("#mode")
    assert "LOOP" in m and "NotAllowedError" in m, m
    assert len([e for e in log(p) if "show" in e]) > 8, "loop did not cycle"
    print("ok - mic refused: LOOP mode shown (%s)" % m.split("(")[-1].rstrip(")"))
    ctx.close()

    # ---- 3. acoustic loop with a simulated receiver ----
    ctx, p = page_with(b, init=FAKE_MIC)
    text = p.evaluate(TEXT_JS); p.click("#start")
    p.wait_for_function("() => window.__qtSender.state().running")
    t0 = time.time()
    time.sleep(2.2)
    p.screenshot(path=SHOT)
    p.wait_for_function("() => /receiver finished/.test(document.getElementById('status').textContent)", timeout=120000)
    dt = time.time() - t0
    rx = p.evaluate("() => window.__rx")
    lg = log(p)
    total = len(rx["frames"])
    # no skips: per slot, displayed chunks go strictly c, c+2, ... and every advance happened after the receiver had c
    for s in (0, 1):
        seq = [e["show"] for e in lg if e.get("slot") == s]
        want = list(range(s, total, 2))
        assert [x for x in seq if x >= 0] == want, ("slot", s, seq)
    frames = [rx["frames"][str(i)] for i in range(total)]
    got = assemble(frames).decode("utf-8")
    assert got == text, "content mismatch"
    heard = [e for e in lg if "heard" in e]
    print("ok - acoustic loop: %d chunks, %.1f s (%.0f B/s), acks played %d (lost %d), heard %d matching + %d stale; "
          "content + CRC verified, no skipped chunk" % (total, dt, len(text.encode()) / dt, rx["acks"], rx["lost"],
          sum(1 for e in heard if e["ok"]), sum(1 for e in heard if not e["ok"])))
    ctx.close(); b.close()
print("screenshot:", SHOT)
print("web sender chrome tests passed")
