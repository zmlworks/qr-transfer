package com.zmlworks.qrtransfer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/** One square per chunk: received = highlight, missing = dim, last received = bright. */
public final class ChunkGridView extends View {
    private boolean[] have = new boolean[0];
    private int last = -1;
    private final Paint p = new Paint();

    public ChunkGridView(Context c, AttributeSet a) { super(c, a); }

    void set(boolean[] have, int last) { this.have = have; this.last = last; invalidate(); }

    @Override protected void onDraw(Canvas cv) {
        int n = have.length, w = getWidth(), h = getHeight();
        if (n == 0 || w == 0 || h == 0) return;
        // largest square cell size s with floor(w/s) * floor(h/s) >= n
        int s = (int) Math.max(1, Math.floor(Math.sqrt((double) w * h / n)));
        while (s > 1 && (w / s) * (h / s) < n) s--;
        int cols = Math.max(1, w / s), gap = s >= 6 ? 1 : 0;
        for (int i = 0; i < n; i++) {
            int x = (i % cols) * s, y = (i / cols) * s;
            p.setColor(i == last ? Ui.TEXT : have[i] ? Ui.HI : Ui.GRID);
            cv.drawRect(x, y, x + s - gap, y + s - gap, p);
        }
    }
}
