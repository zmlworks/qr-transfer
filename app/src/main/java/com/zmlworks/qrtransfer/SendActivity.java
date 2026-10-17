package com.zmlworks.qrtransfer;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.WriterException;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Sender: text or file -> QT1 frames -> animated QR codes, two at a time (slot A: even chunks, slot B: odd chunks).
 * With "advance on tones" (microphone, the default) each slot advances ONLY when the receiver's alternating-bit ack for
 * exactly the shown chunk is heard (no timeout; stale acks are ignored) and the transfer stops on the DONE tone.
 * 0.3.1: there is no automatic fallback to the loop (auto-advance) any more — if the microphone is refused, cannot be
 * opened or stops, the codes stay put and the screen says why; the loop is only used when the user picks it (option
 * off, or the "loop" button). The active mode, per-slot state, the live per-tone levels and the last detected tone are
 * shown under the codes. Pacing logic: {@link SlotScheduler}.
 */
public final class SendActivity extends Activity implements AckListener.Callback {
    private static final int REQ_PICK = 1, REQ_MIC = 2;
    static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int[] CHUNKS = {200, 400, 700, 1000};
    private static final int[] FPS = {2, 4, 6, 10};
    private static final ErrorCorrectionLevel[] ECC = {ErrorCorrectionLevel.L, ErrorCorrectionLevel.M, ErrorCorrectionLevel.Q};

    private View setup, show;
    private EditText text;
    private TextView fileInfo, info, ackState;
    private Spinner chunk, fps, ecc;
    private CheckBox ackBox, twoBox;
    private LinearLayout qrs;
    private ImageView[] qr;
    private Button pause, toLoop;
    private byte[] fileData;
    private String fileName;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private HandlerThread ht;
    private Handler worker;
    // --- state below is touched only on the worker thread ---
    private List<String> frames;
    private SlotScheduler sched;
    private final int[] drawn = {-2, -2};
    private boolean ackMode, sending;
    private volatile boolean paused;
    private long periodMs;
    private ErrorCorrectionLevel ecl;
    private AckListener listener;
    private String loopReason;   // why loop mode is active (null in ack mode)
    private volatile String micProblem;   // ack mode without a working microphone (null when fine)
    private int lastHeard = -1, lastHeardStale;
    private long lastHeardAt;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_send);
        Ui.edgeToEdge(this, findViewById(R.id.root));
        setup = findViewById(R.id.setup);
        show = findViewById(R.id.show);
        text = findViewById(R.id.text);
        fileInfo = findViewById(R.id.file_info);
        info = findViewById(R.id.info);
        ackState = findViewById(R.id.ack_state);
        chunk = findViewById(R.id.chunk);
        fps = findViewById(R.id.fps);
        ecc = findViewById(R.id.ecc);
        ackBox = findViewById(R.id.ack);
        twoBox = findViewById(R.id.two);
        qrs = findViewById(R.id.qrs);
        qr = new ImageView[]{findViewById(R.id.qr_a), findViewById(R.id.qr_b)};
        layoutCodes(getResources().getConfiguration());
        pause = findViewById(R.id.pause);
        toLoop = findViewById(R.id.to_loop);
        toLoop.setOnClickListener(v -> switchToLoop());
        ackBox.setOnCheckedChangeListener((v, on) -> fps.setEnabled(!on));
        spinner(chunk, R.array.chunks, 1);
        spinner(fps, R.array.fps, 1);
        spinner(ecc, R.array.ecc, 1);
        fps.setEnabled(!ackBox.isChecked());
        findViewById(R.id.pick).setOnClickListener(v -> startActivityForResult(
                new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_PICK));
        findViewById(R.id.clear_file).setOnClickListener(v -> setFile(null, null));
        findViewById(R.id.start).setOnClickListener(v -> begin());
        findViewById(R.id.stop).setOnClickListener(v -> stopSending());
        pause.setOnClickListener(v -> { paused = !paused; pause.setText(paused ? "▶" : "⏸"); worker.post(this::schedule); });
        findViewById(R.id.prev).setOnClickListener(v -> step(-1));
        // (prev/next move both codes while paused)
        findViewById(R.id.next).setOnClickListener(v -> step(1));
        ht = new HandlerThread("qr-sender");
        ht.start();
        worker = new Handler(ht.getLooper());
        handleShare(getIntent());
    }

    private void spinner(Spinner s, int arr, int sel) {
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, getResources().getStringArray(arr));
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(a);
        s.setSelection(sel);
    }

    private void handleShare(Intent i) {
        if (i == null || !Intent.ACTION_SEND.equals(i.getAction())) return;
        Uri u = i.getParcelableExtra(Intent.EXTRA_STREAM);
        if (u != null) loadFile(u);
        else {
            CharSequence t = i.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (t != null) text.setText(t);
        }
    }

    // ---------------- input ----------------
    private void setFile(String name, byte[] data) {
        fileName = name;
        fileData = data;
        fileInfo.setText(data == null ? getString(R.string.no_file) : getString(R.string.file_info, name, data.length));
        text.setEnabled(data == null);
    }

    private void loadFile(Uri u) {
        String name = "file";
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) name = c.getString(0);
        } catch (RuntimeException ignored) { }
        try (InputStream in = getContentResolver().openInputStream(u)) {
            if (in == null) throw new IOException("null stream");
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) {
                bo.write(buf, 0, n);
                if (bo.size() > MAX_BYTES) { toast(getString(R.string.too_large, MAX_BYTES / 1024)); return; }
            }
            setFile(name, bo.toByteArray());
        } catch (IOException | SecurityException e) { toast(e.toString()); }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK && res == RESULT_OK && data != null && data.getData() != null) loadFile(data.getData());
    }

    // ---------------- start / stop ----------------
    private void begin() {
        if (ackBox.isChecked() && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        startSending(ackBox.isChecked(), null);
    }

    @Override public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        if (req != REQ_MIC) return;
        boolean ok = r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED;
        if (!ok) toast(getString(R.string.mic_denied));
        // still MIC-ACK mode (never an automatic auto-advance): the codes wait, the screen offers the loop button
        startSending(true, ok ? null : getString(R.string.loop_reason_denied));
    }

    /** @param problem why the microphone cannot be used (permission refused), or null */
    private void startSending(boolean withAck, String problem) {
        final Protocol.Content c = fileData != null ? new Protocol.Content(true, fileName, fileData)
                : new Protocol.Content(false, "", text.getText().toString().getBytes(StandardCharsets.UTF_8));
        if (c.data.length == 0) { toast(getString(R.string.nothing)); return; }
        final int chunkBytes = CHUNKS[chunk.getSelectedItemPosition()];
        periodMs = 1000 / FPS[fps.getSelectedItemPosition()];
        ecl = ECC[ecc.getSelectedItemPosition()];
        setup.setVisibility(View.GONE);
        show.setVisibility(View.VISIBLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 1f;   // bright screen = easier scanning
        getWindow().setAttributes(lp);
        info.setText(R.string.preparing);
        paused = false;
        pause.setText("⏸");
        final int slots = twoBox.isChecked() ? 2 : 1;
        qr[1].setVisibility(View.GONE);
        worker.post(() -> {
            frames = Protocol.frames(Protocol.newId(), c, chunkBytes, true);
            sending = true;
            ackMode = withAck;
            loopReason = withAck ? null : getString(R.string.loop_reason_off);
            micProblem = problem;
            lastHeard = -1;
            if (withAck && problem == null) {
                listener = new AckListener(this, this);
                if (!listener.start()) {
                    listener = null;
                    micProblem = getString(R.string.loop_reason_failed);
                    ui.post(() -> toast(getString(R.string.mic_failed)));
                }
            }
            ui.post(() -> toLoop.setVisibility(withAck ? View.VISIBLE : View.GONE));
            sched = new SlotScheduler(frames.size(), slots, periodMs, ackMode);
            drawn[0] = drawn[1] = -2;
            sched.start(SystemClock.uptimeMillis());
            render();
            schedule();
            ui.post(() -> { ui.removeCallbacks(statusLoop); ui.post(statusLoop); });
        });
    }

    /** live mode / per-slot / detected-tone indicator, refreshed 10×/s on the UI thread */
    private final Runnable statusLoop = new Runnable() {
        @Override public void run() {
            if (show.getVisibility() != View.VISIBLE) return;
            worker.post(() -> {
                final String s = statusText();
                ui.post(() -> ackState.setText(s));
            });
            ui.postDelayed(this, 100);
        }
    };

    /** worker thread */
    private String statusText() {
        if (!sending || sched == null) return "";
        long now = SystemClock.uptimeMillis();
        StringBuilder b = new StringBuilder();
        if (!ackMode) {
            b.append(getString(R.string.mode_loop, sched.periodMs));
            if (loopReason != null) b.append(" — ").append(loopReason);
            return b.toString();
        }
        String problem = micProblem;
        if (problem != null) b.append(getString(R.string.mode_ack_nomic, problem));
        else b.append(getString(R.string.mode_ack));
        for (int s = 0; s < sched.slots; s++) {
            b.append('\n').append(sched.slots == 2 ? (s == 0 ? "A: " : "B: ") : "");
            int i = sched.shown(s);
            if (i < 0) { b.append(getString(R.string.slot_idle)); continue; }
            b.append(getString(R.string.slot_wait, i + 1, Tone.NAMES[sched.expectedTone(s)],
                    sched.waitingMs(s, now) / 1000.0));
        }
        AckListener l = listener;
        int live = l != null ? l.liveTone : -1;
        double rms = l != null ? l.liveRms : 0;
        b.append('\n').append(String.format(Locale.ROOT, "🎤 %s  %d dBFS", live >= 0 ? "♪" + Tone.NAMES[live] : "·",
                Math.round(20 * Math.log10(Math.max(rms, 1e-5)))));
        if (l != null) {   // per-tone level / noise floor, the same numbers as the web sender's bars
            b.append('\n');
            for (int k = 0; k < Tone.COUNT; k++)
                b.append(String.format(Locale.ROOT, "%s%s %d/%d ", k == live ? "▶" : "", Tone.NAMES[k], Math.round(l.level[k]), Math.round(l.floor[k])));
            b.append("dB ").append(l.source);
        }
        if (lastHeard >= 0 && now - lastHeardAt < 3000)
            b.append("   ").append(getString(lastHeardStale == 1 ? R.string.heard_stale : R.string.heard_ok, Tone.NAMES[lastHeard]));
        b.append(String.format(Locale.ROOT, "   ✓%d  ✗%d", sched.goodAcks(), sched.staleAcks()));
        if (problem != null) b.append('\n').append(getString(R.string.nomic_hint));
        else if (sched.silent(now)) b.append('\n').append(getString(R.string.silence_hint));
        return b.toString();
    }

    private void stopSending() {
        worker.removeCallbacksAndMessages(null);
        worker.post(() -> {
            sending = false;
            if (listener != null) { listener.stop(); listener = null; }
        });
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(lp);
        show.setVisibility(View.GONE);
        setup.setVisibility(View.VISIBLE);
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        if (show.getVisibility() == View.VISIBLE) stopSending(); else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        stopSending();
        ht.quitSafely();
        super.onDestroy();
    }

    // ---------------- frame loop (worker thread) ----------------
    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        layoutCodes(c);
    }

    /** portrait: codes stacked (top = A); landscape: side by side (left = A) */
    private void layoutCodes(Configuration c) {
        boolean land = c.orientation == Configuration.ORIENTATION_LANDSCAPE;
        qrs.setOrientation(land ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        for (ImageView v : qr) {
            v.setLayoutParams(land ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1)
                    : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        }
    }

    private Bitmap blank;

    /** Draw whatever the scheduler shows now (only slots that changed are re-rendered). */
    private void render() {
        if (!sending || sched == null) return;
        final Bitmap[] bmp = new Bitmap[2];
        final boolean[] changed = new boolean[2];
        int version = 0;
        for (int s = 0; s < sched.slots; s++) {
            int i = sched.shown(s);
            if (i == drawn[s]) continue;
            drawn[s] = i;
            changed[s] = true;
            if (i < 0) {   // idle slot (all its chunks confirmed): plain white, nothing to read
                if (blank == null) { blank = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888); blank.eraseColor(0xFFFFFFFF); }
                bmp[s] = blank;
                continue;
            }
            try { bmp[s] = QrRender.render(frames.get(i), ecl); } catch (WriterException e) {
                ui.post(() -> { toast(e.toString()); stopSending(); });
                return;
            }
            version = QrRender.version(bmp[s]);
        }
        StringBuilder line = new StringBuilder();
        for (int s = 0; s < sched.slots; s++) {
            int i = sched.shown(s);
            line.append(s == 0 ? (sched.slots == 2 ? "A " : "") : "   B ").append(i < 0 ? "✓" : String.valueOf(i + 1));
        }
        line.append(" / ").append(frames.size());
        if (version > 0) line.append("  ·  v").append(version);
        line.append("  ·  ").append(frames.get(0).substring(4, 12));
        if (ackMode) line.append(String.format(Locale.ROOT, "  ·  ✓%d", sched.ackedCount()))
                .append(sched.latencyMs() >= 0 ? "  " + sched.latencyMs() + "ms" : "");
        if (sched.rounds() > 0) line.append("  ·  R").append(sched.rounds() + 1);
        final String text = line.toString();
        final int slots = sched.slots;
        ui.post(() -> {
            for (int s = 0; s < 2; s++) {
                if (s >= slots) { qr[s].setVisibility(View.GONE); continue; }
                qr[s].setVisibility(View.VISIBLE);
                if (!changed[s]) continue;
                BitmapDrawable d = new BitmapDrawable(getResources(), bmp[s]);
                d.setFilterBitmap(false);   // crisp modules when scaled up
                d.setAntiAlias(false);
                qr[s].setImageDrawable(d);
            }
            info.setText(text);
        });
    }

    private final Runnable tick = () -> {
        if (paused || !sending || sched == null) return;
        if (sched.tick(SystemClock.uptimeMillis())) render();
        schedule();
    };

    private void schedule() {
        worker.removeCallbacks(tick);
        if (paused || !sending || sched == null) return;
        long now = SystemClock.uptimeMillis();
        worker.postDelayed(tick, Math.max(10, sched.nextDeadline(now) - now));
    }

    private void step(int d) {
        paused = true;
        pause.setText("▶");
        worker.post(() -> {
            worker.removeCallbacks(tick);
            if (sched == null) return;
            long now = SystemClock.uptimeMillis();
            for (int s = 0; s < sched.slots; s++) sched.step(s, d, now);
            render();
        });
    }

    // AckListener callbacks (listener thread) -> worker thread
    @Override public void onAck(int tone) {
        final long t = SystemClock.uptimeMillis();
        worker.post(() -> {
            if (!sending || sched == null || paused) return;
            boolean ok = sched.onAck(tone, t);
            lastHeard = tone;
            lastHeardStale = ok ? 0 : 1;
            lastHeardAt = t;
            if (ok) render();
            schedule();
        });
    }

    /** the microphone died mid-transfer: stay in MIC-ACK mode (codes wait, nothing skipped) and say so */
    @Override public void onMicLost() {
        worker.post(() -> {
            if (!sending || sched == null || !ackMode || sched.finished()) return;
            listener = null;
            micProblem = getString(R.string.loop_reason_lost);
        });
        ui.post(() -> toast(getString(R.string.mic_failed)));
    }

    /** the user pressed "loop": auto-advance from now on (the only way into the loop besides the option) */
    private void switchToLoop() {
        toLoop.setVisibility(View.GONE);
        worker.post(() -> {
            if (!sending || sched == null || !ackMode) return;
            if (listener != null) { listener.stop(); listener = null; }
            ackMode = false;
            micProblem = null;
            loopReason = getString(R.string.loop_reason_user);
            sched = new SlotScheduler(frames.size(), sched.slots, periodMs, false);
            drawn[0] = drawn[1] = -2;
            sched.start(SystemClock.uptimeMillis());
            render();
            schedule();
        });
    }

    @Override public void onDone() {
        worker.post(() -> { if (sched != null) sched.onDone(); });
        ui.post(() -> {
            if (show.getVisibility() != View.VISIBLE) return;
            stopSending();
            toast(getString(R.string.receiver_done));
        });
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
}
