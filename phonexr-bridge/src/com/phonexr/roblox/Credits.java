package com.phonexr.roblox;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;

/** Fixed credit line, drawn in the top-right corner of the screen (and of each eye in the headset). */
public final class Credits {
    public static final String TEXT = "créditos : joaolimz_ - samrat";

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
            float d = getResources().getDisplayMetrics().density;
            float w = getWidth();
            float size = 11f * d;
            text.setTextSize(size);
            shadow.setTextSize(size);
            float y = 6f * d + size;
            float pad = 8f * d;
            // Draw once for the flat phone screen, and once per eye (stereo halves) so it is always visible.
            for (float right : new float[]{w - pad, 0.5f * w - pad}) {
                canvas.drawText(TEXT, right + d, y + d, shadow);
                canvas.drawText(TEXT, right, y, text);
            }
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent e) {
            return false;
        }
    }
}
