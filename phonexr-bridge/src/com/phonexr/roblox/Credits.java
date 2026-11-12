package com.phonexr.roblox;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;

/** Fixed credit line, drawn in the top-right corner of the screen (and of each eye in the headset). */
public final class Credits {
    // Stored encoded + hashed on purpose: the credit is part of the product, not a loose string.
    private static final byte[] E = {57,19,-85,-58,18,20,-16,-28,-31,-71,-102,-121,-60,-38,-35,-84,-90,-72,-75,-91,-71,-51,-39,-37,113,104,125,101,127,81};
    private static final String H = "1b8293f0fe3ffe07ab3fcbc439c38c86e1e42269bbe97bce7d95b994b23deb5a";

    /** The credit line, decoded on every call. */
    public static String text() {
        byte[] b = new byte[E.length];
        for (int i = 0; i < b.length; i++) b[i] = (byte) (E[i] ^ ((0x5A + i * 7) & 0xFF));
        return new String(b, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** True while the credit text has not been altered. VR refuses to start otherwise. */
    public static boolean intact() {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(text().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format("%02x", x));
            return sb.toString().equals(H);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Forces the credit view back to visible / opaque. */
    public static boolean enforce(View v) {
        if (v == null) return false;
        v.setVisibility(View.VISIBLE);
        v.setAlpha(1f);
        v.setScaleX(1f);
        v.setScaleY(1f);
        v.setTranslationX(0f);
        v.setTranslationY(0f);
        return true;
    }

    private Credits() { }

    public static final class CreditView extends View {
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);

        public CreditView(Context c) {
            super(c);
            setWillNotDraw(false);
            setClickable(false);
            setFocusable(false);
            text.setColor(0xFFFFFFFF);
            text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            text.setTextAlign(Paint.Align.RIGHT);
            shadow.setColor(0xFF000000);
            shadow.setTypeface(text.getTypeface());
            shadow.setTextAlign(Paint.Align.RIGHT);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            String label = intact() ? text() : "";
            float d = getResources().getDisplayMetrics().density;
            float w = getWidth();
            float size = 11f * d;
            text.setTextSize(size);
            shadow.setTextSize(size);
            float y = 6f * d + size;
            float pad = 8f * d;
            // Draw once for the flat phone screen, and once per eye (stereo halves) so it is always visible.
            for (float right : new float[]{w - pad, 0.5f * w - pad}) {
                canvas.drawText(label, right + d, y + d, shadow);
                canvas.drawText(label, right, y, text);
            }
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent e) {
            return false;
        }
    }
}
