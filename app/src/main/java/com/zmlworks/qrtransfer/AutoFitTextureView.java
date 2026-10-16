package com.zmlworks.qrtransfer;

import android.content.Context;
import android.util.AttributeSet;
import android.view.TextureView;

/** TextureView that keeps the camera's aspect ratio inside the space it is given. */
public final class AutoFitTextureView extends TextureView {
    private int rw, rh;

    public AutoFitTextureView(Context c, AttributeSet a) { super(c, a); }

    void setAspect(int w, int h) { rw = w; rh = h; requestLayout(); }

    @Override protected void onMeasure(int ws, int hs) {
        super.onMeasure(ws, hs);
        int w = MeasureSpec.getSize(ws), h = MeasureSpec.getSize(hs);
        if (rw == 0 || rh == 0 || w == 0 || h == 0) { setMeasuredDimension(w, h); return; }
        if (w * rh < h * rw) setMeasuredDimension(w, w * rh / rw); else setMeasuredDimension(h * rw / rh, h);
    }
}
