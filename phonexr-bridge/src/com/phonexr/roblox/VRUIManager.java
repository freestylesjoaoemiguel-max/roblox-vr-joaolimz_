package com.phonexr.roblox;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Vibrator;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;
import com.roblox.engine.jni.NativeGLInterface;
import com.roblox.engine.jni.NativeInputInterface;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public final class VRUIManager {
    private static final int WIDTH = 1024;
    private static final int HEIGHT = 512;

    private final Context context;
    private final Vibrator vibrator;
    private final Bitmap bitmap;
    private final Canvas canvas;
    private final ByteBuffer buffer;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tempRect = new RectF();

    private boolean isMenuMode = true;
    private boolean isKeyboardVisible = false;
    private boolean shiftActive = false;
    private String currentText = "";
    private View targetInputView;

    private static final class ButtonItem {
        final int id;
        final String label;
        final String upperLabel;
        final RectF bounds;

        ButtonItem(int id, String label, float l, float t, float r, float b) {
            this.id = id;
            this.label = label;
            this.upperLabel = label.length() == 1 ? label.toUpperCase() : label;
            this.bounds = new RectF(l, t, r, b);
        }
    }

    private final List<ButtonItem> keyboardKeys = new ArrayList<>();
    private final List<ButtonItem> settingsButtons = new ArrayList<>();
    private boolean isSettingsVisible;
    private static final int SET_HEADSET = 201, SET_SKELETON = 202, SET_CLOSE = 203, SET_6DOF = 204,
            SET_JUMP = 205, SET_SWAP = 206, SET_GESTURES = 207, SET_ULTRAWIDE = 208;

    // ---- Material You theme (one accent seed → tonal roles), Quest-style settings panel ----
    private static final int ACCENT = 0xFF8AB4F8;
    private static final int MD_PRIMARY = tone(ACCENT, 0.80f);
    private static final int MD_ON_PRIMARY = tone(ACCENT, 0.20f);
    private static final int MD_PRIMARY_CONTAINER = tone(ACCENT, 0.30f);
    private static final int MD_ON_PRIMARY_CONTAINER = tone(ACCENT, 0.90f);
    private static final int MD_SURFACE = 0xF014151A;
    private static final int MD_ON_SURFACE = 0xFFE4E2E6;
    private static final int MD_ON_SURFACE_VAR = 0xFFC4C6D0;
    private static final int MD_OUTLINE = 0x40FFFFFF;
    private Typeface iconFont = Typeface.DEFAULT, iconFontFilled = Typeface.DEFAULT;
    private final Paint md = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mdIcon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface mdMedium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    // Material Symbols Rounded code points (subset bundled in assets).
    private static final String IC_HEADSET = "", IC_HAND = "", IC_6DOF = "",
            IC_JUMP = "", IC_SWAP = "", IC_CLOSE = "", IC_SETTINGS = "";
    /** World-locked panels are shown this many times smaller than the eye view (compositor uUiZoom). */
    private static final float PANEL_ZOOM = 1.6f;
    private float panelZoom = PANEL_ZOOM;

    /** Per hand (0 left, 1 right): hovered key and whether its trigger/pinch is held. */
    private final ButtonItem[] hovered = new ButtonItem[2];
    private final boolean[] pressDown = new boolean[2];
    private ButtonItem flashKey;
    private long flashUntil;
    private long lastClickTime = 0;
    private boolean dirty = true;
    private int loadingStep = -1;
    /** Head angles when the keyboard opened: the panel stays there in the world. */
    private float anchorYaw, anchorPitch;

    // Keyboard panel geometry (texture pixels; the texture spans the whole eye view).
    private static final float KEY_UNIT = 66f, KEY_H = 60f, KEY_GAP = 10f;
    private static final float PANEL_W = 10 * KEY_UNIT + 9 * KEY_GAP + 56f;
    private static final float PANEL_L = (WIDTH - PANEL_W) / 2f, PANEL_R = PANEL_L + PANEL_W;
    private static final float PANEL_T = 26f, FIELD_T = PANEL_T + 22f, FIELD_B = FIELD_T + 56f;
    private static final float KEYS_T = FIELD_B + 16f;
    private static final float PANEL_B = KEYS_T + 5 * KEY_H + 4 * KEY_GAP + 24f;
    private static final int KEY_DEL = 100, KEY_SHIFT = 101, KEY_DONE = 103;
    private static final long CLICK_COOLDOWN_MS = 140;

    public VRUIManager(Context context) {
        this.context = context;
        this.vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        this.bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        this.canvas = new Canvas(bitmap);
        this.buffer = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4);

        initKeyboardKeys();
        try {
            iconFont = Typeface.createFromAsset(context.getAssets(), "MaterialSymbolsRounded.ttf");
            iconFontFilled = new Typeface.Builder(context.getAssets(), "MaterialSymbolsRounded.ttf")
                    .setFontVariationSettings("'FILL' 1").build();
        } catch (Throwable ignored) { }
        mdIcon.setTypeface(iconFont);
        mdIcon.setTextAlign(Paint.Align.CENTER);
        // Quest-style rows: one card per setting, centred, with room for icon + M3 switch.
        float rowL = WIDTH / 2f - 330f, rowR = WIDTH / 2f + 330f, top = 92f, h = 44f, gap = 6f;
        int[] ids = {SET_HEADSET, SET_ULTRAWIDE, SET_GESTURES, SET_SWAP, SET_SKELETON, SET_6DOF, SET_JUMP};
        for (int i = 0; i < ids.length; i++) {
            float y = top + i * (h + gap);
            settingsButtons.add(new ButtonItem(ids[i], "", rowL, y, rowR, y + h));
        }
        settingsButtons.add(new ButtonItem(SET_CLOSE, "", WIDTH / 2f - 90, top + ids.length * (h + gap) + 6,
                WIDTH / 2f + 90, top + ids.length * (h + gap) + 54));
    }


    private void initKeyboardKeys() {
        keyboardKeys.clear();
        addRow(0, new String[]{"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"}, null);
        addRow(1, new String[]{"q", "w", "e", "r", "t", "y", "u", "i", "o", "p"}, null);
        addRow(2, new String[]{"a", "s", "d", "f", "g", "h", "j", "k", "l"}, null);
        addRow(3, new String[]{"SHIFT", "z", "x", "c", "v", "b", "n", "m", "DEL"},
                new float[]{1.5f, 1, 1, 1, 1, 1, 1, 1, 1.5f});
        addRow(4, new String[]{"@", "SPACE", ".", "DONE"}, new float[]{1, 5, 1, 2.5f});
    }

    /** One centred row; widths are in key units (a wide key also covers the gaps it spans). */
    private void addRow(int row, String[] labels, float[] units) {
        float total = 0;
        for (int i = 0; i < labels.length; i++) total += units == null ? 1 : units[i];
        float rowW = total * KEY_UNIT + (labels.length - 1) * KEY_GAP;
        float x = (WIDTH - rowW) / 2f;
        float y = KEYS_T + row * (KEY_H + KEY_GAP);
        for (int i = 0; i < labels.length; i++) {
            float u = units == null ? 1 : units[i];
            float w = u * KEY_UNIT + (u > 1 ? (float) Math.floor(u - 1) * KEY_GAP : 0);
            String label = labels[i];
            int id = label.equals("DEL") ? KEY_DEL : label.equals("SHIFT") ? KEY_SHIFT
                    : label.equals("DONE") ? KEY_DONE : label.equals("SPACE") ? 32 : label.charAt(0);
            keyboardKeys.add(new ButtonItem(id, label, x, y, x + w, y + KEY_H));
            x += w + KEY_GAP;
        }
    }

    public synchronized void setMenuMode(boolean menuMode) {
        this.isMenuMode = menuMode;
        this.dirty = true;
    }

    /** Open a panel once in front of where the user is looking; it then stays fixed in the world. */
    private void anchorPanel() {
        anchorPanel(PANEL_ZOOM);
    }

    private void anchorPanel(float zoom) {
        panelZoom = zoom;
        try { PhoneXRBridge.setUiZoom(zoom); } catch (Throwable ignored) { }
        PhoneXRBridge bridge = PhoneXRBridge.getInstance();
        anchorYaw = bridge != null ? bridge.getHeadYaw() : 0f;
        anchorPitch = bridge != null ? bridge.getHeadPitch() : 0f;
        try { PhoneXRBridge.setUiAnchor(anchorYaw, anchorPitch, true); } catch (Throwable ignored) { }
    }

    /** VR settings panel (hold Minus, or hold a palm-to-face pinch). Changes apply live. */
    public synchronized void toggleSettings() {
        if (isKeyboardVisible) return;
        isSettingsVisible = !isSettingsVisible;
        if (isSettingsVisible) anchorPanel();
        else try { PhoneXRBridge.setUiAnchor(0f, 0f, false); } catch (Throwable ignored) { }
        hovered[0] = hovered[1] = null;
        pressDown[0] = pressDown[1] = true;
        dirty = true;
    }

    public synchronized boolean isSettingsVisible() {
        return isSettingsVisible;
    }

    public synchronized void showKeyboard(View inputView, String initialText) {
        this.targetInputView = inputView;
        this.currentText = initialText != null ? initialText : "";
        isSettingsVisible = false;
        if (!isKeyboardVisible) anchorPanel();
        this.isKeyboardVisible = true;
        hovered[0] = hovered[1] = null;
        // A trigger or pinch already held when the keyboard opens must not type.
        pressDown[0] = pressDown[1] = true;
        this.dirty = true;
    }

    public synchronized void hideKeyboard() {
        if (isKeyboardVisible) {
            try { PhoneXRBridge.setUiAnchor(0f, 0f, false); } catch (Throwable ignored) { }
        }
        this.isKeyboardVisible = false;
        hovered[0] = hovered[1] = null;
        this.dirty = true;
    }

    public synchronized boolean isKeyboardVisible() {
        return isKeyboardVisible;
    }

    public synchronized boolean isPointerUiActive() {
        return isKeyboardVisible || isSettingsVisible;
    }

    public synchronized void onRay(float rayX, float rayY, boolean triggerDown, boolean active,
                                   int handSide, PhoneXRBridge bridge) {
        List<ButtonItem> targets = isKeyboardVisible ? keyboardKeys
                : isSettingsVisible ? settingsButtons : null;
        if (targets == null || handSide < 0 || handSide > 1) return;

        float uiX = rayX, uiY = rayY;
        if (bridge != null) {
            // Rays are head-relative; the panel is world-fixed and shown panelZoom times
            // smaller. Same mapping as the compositor shader (screen y points down).
            float yawShift = (float) Math.tan(clampAngle(wrap(bridge.getHeadYaw() - anchorYaw))) * 0.5f;
            float pitchShift = (float) Math.tan(clampAngle(bridge.getHeadPitch() - anchorPitch)) * 0.5f;
            uiX = (rayX - 0.5f + yawShift) * panelZoom + 0.5f;
            uiY = (rayY - 0.5f - pitchShift) * panelZoom + 0.5f;
        }
        uiX *= WIDTH;
        uiY *= HEIGHT;

        ButtonItem found = null;
        if (active) {
            for (int i = 0, n = targets.size(); i < n; i++) {
                ButtonItem item = targets.get(i);
                if (item.bounds.contains(uiX, uiY)) { found = item; break; }
            }
        }
        if (found != hovered[handSide]) {
            hovered[handSide] = found;
            dirty = true;
        }

        // Edge-triggered: one key per press. Holding or sliding onto another key never types.
        boolean wasDown = pressDown[handSide];
        pressDown[handSide] = triggerDown;
        if (!triggerDown || wasDown || found == null) return;
        long now = System.currentTimeMillis();
        if (now - lastClickTime < CLICK_COOLDOWN_MS) return;
        lastClickTime = now;
        flashKey = found;
        flashUntil = now + 120;
        performClick(found, bridge);
    }

    private static float wrap(float angle) {
        if (angle > Math.PI) angle -= (float) (2 * Math.PI);
        else if (angle < -Math.PI) angle += (float) (2 * Math.PI);
        return angle;
    }

    private static float clampAngle(float angle) {
        return Math.max(-1.3f, Math.min(1.3f, angle));
    }

    private void performClick(ButtonItem item, PhoneXRBridge bridge) {
        vibrate(30);
        if (isKeyboardVisible) {
            handleKeyboardClick(item, bridge);
        } else if (isSettingsVisible) {
            handleSettingsClick(item);
        }
        dirty = true;
    }

    private void handleSettingsClick(ButtonItem item) {
        android.content.SharedPreferences prefs = context.getSharedPreferences("roblox_vr", Context.MODE_PRIVATE);
        if (item.id == SET_HEADSET) {
            // Next headset; the bridge listens for the change and applies it without a restart.
            int next = (PhoneXRBridge.headsetIndex(prefs) + 1) % PhoneXRBridge.HEADSET_NAMES.length;
            PhoneXRBridge.chooseHeadset(prefs, next);
        } else if (item.id == SET_SKELETON) {
            prefs.edit().putBoolean("pref_hand_skeleton", !prefs.getBoolean("pref_hand_skeleton", false)).apply();
        } else if (item.id == SET_6DOF) {
            prefs.edit().putBoolean("pref_6dof", !prefs.getBoolean("pref_6dof", false)).apply();
        } else if (item.id == SET_JUMP) {
            prefs.edit().putBoolean("pref_body_jump", !prefs.getBoolean("pref_body_jump", true)).apply();
        } else if (item.id == SET_ULTRAWIDE) {
            prefs.edit().putBoolean("pref_ultrawide", !prefs.getBoolean("pref_ultrawide", false)).apply();
        } else if (item.id == SET_GESTURES) {
            prefs.edit().putBoolean("pref_hand_gestures", !prefs.getBoolean("pref_hand_gestures", true)).apply();
        } else if (item.id == SET_SWAP) {
            prefs.edit().putBoolean("pref_swap_sticks", !prefs.getBoolean("pref_swap_sticks", false)).apply();
        } else if (item.id == SET_CLOSE) {
            toggleSettings();
        }
    }


    private void handleKeyboardClick(ButtonItem item, PhoneXRBridge bridge) {
        if (item.id == KEY_DEL) {
            if (currentText.length() > 0) {
                currentText = currentText.substring(0, currentText.length() - 1);
                syncTextToRoblox();
            }
        } else if (item.id == KEY_SHIFT) {
            shiftActive = !shiftActive;
        } else if (item.id == KEY_DONE) {
            commitAndClose();
        } else if (item.id == 32) { // SPACE
            currentText += " ";
            syncTextToRoblox();
        } else if (item.id >= 32 && item.id < 127) {
            char c = (char) item.id;
            if (Character.isLetter(c)) {
                c = shiftActive ? Character.toUpperCase(c) : Character.toLowerCase(c);
            }
            currentText += c;
            // One-shot shift, like a phone keyboard.
            if (shiftActive && Character.isLetter(c)) shiftActive = false;
            syncTextToRoblox();
        }
    }

    private void syncTextToRoblox() {
        try {
            NativeGLInterface.syncTextboxTextAndCursorPosition2(currentText, currentText.length());
            if (targetInputView instanceof TextView) {
                targetInputView.post(() -> ((TextView) targetInputView).setText(currentText));
            }
        } catch (Throwable ignored) { }
    }

    private void commitAndClose() {
        try {
            NativeGLInterface.syncTextboxTextAndCursorPosition2(currentText, currentText.length());
            NativeGLInterface.nativePassText(0L, currentText, true, 0);
            if (targetInputView != null) {
                targetInputView.post(() -> {
                    if (targetInputView instanceof TextView) {
                        ((TextView) targetInputView).setText(currentText);
                    }
                    targetInputView.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
                    targetInputView.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
                });
            }
        } catch (Throwable ignored) { }
        hideKeyboard();
    }

    private void vibrate(long ms) {
        if (vibrator != null) {
            try { vibrator.vibrate(ms); } catch (Throwable ignored) { }
        }
    }

    public synchronized void updateAndUpload() {
        // The loading dots advance a few times a second; otherwise redraw only on change.
        if (isMenuMode && !isKeyboardVisible && !isSettingsVisible) {
            int step = (int) (System.currentTimeMillis() / 400L % 4L);
            if (step != loadingStep) {
                loadingStep = step;
                dirty = true;
            }
        }
        if (flashKey != null && System.currentTimeMillis() > flashUntil) {
            flashKey = null;
            dirty = true;
        }
        if (!dirty) return;
        drawFrame();
        buffer.position(0);
        bitmap.copyPixelsToBuffer(buffer);
        PhoneXRBridge.setUiBuffer(WIDTH, HEIGHT, buffer, isMenuMode || isKeyboardVisible || isSettingsVisible);
        dirty = false;
    }

    private void drawFrame() {
        canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR);

        if (isKeyboardVisible) {
            drawKeyboard();
        } else if (isSettingsVisible) {
            drawSettings();
        } else if (isMenuMode) {
            drawMenu();
        }
    }

    private void drawMenu() {
        // Loading screen: plain text on black, no Roblox logo.
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setColor(0xFFFFFFFF);
        textPaint.setTextSize(54f);
        textPaint.setFakeBoldText(true);
        String dots = "...".substring(0, Math.max(0, Math.min(3, loadingStep)));
        canvas.drawText("Loading" + dots, WIDTH / 2f, HEIGHT / 2f + 18f, textPaint);
    }


    private void drawSettings() {
        android.content.SharedPreferences prefs = context.getSharedPreferences("roblox_vr", Context.MODE_PRIVATE);
        // Quest-style glass panel.
        tempRect.set(WIDTH / 2f - 360, 14, WIDTH / 2f + 360, 506);
        md.setStyle(Paint.Style.FILL);
        md.setColor(MD_SURFACE);
        canvas.drawRoundRect(tempRect, 34, 34, md);
        md.setStyle(Paint.Style.STROKE);
        md.setStrokeWidth(1.5f);
        md.setColor(MD_OUTLINE);
        canvas.drawRoundRect(tempRect, 34, 34, md);

        // Title with a settings icon.
        drawMdIcon(IC_SETTINGS, WIDTH / 2f - 118, 54, 30, MD_PRIMARY, false);
        textPaint.setTypeface(mdMedium);
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setFakeBoldText(false);
        textPaint.setTextSize(32f);
        textPaint.setColor(MD_ON_SURFACE);
        canvas.drawText("Настройки VR", WIDTH / 2f - 96, 64, textPaint);

        for (int i = 0, n = settingsButtons.size(); i < n; i++) {
            ButtonItem b = settingsButtons.get(i);
            boolean hov = b == hovered[0] || b == hovered[1];
            if (b.id == SET_CLOSE) {
                // Tonal "Готово" pill button.
                int bg = hov ? MD_PRIMARY : MD_PRIMARY_CONTAINER;
                if (b == flashKey) bg = tone(ACCENT, 0.70f);
                md.setStyle(Paint.Style.FILL);
                md.setColor(bg);
                canvas.drawRoundRect(b.bounds, b.bounds.height() / 2f, b.bounds.height() / 2f, md);
                int fg = hov ? MD_ON_PRIMARY : MD_ON_PRIMARY_CONTAINER;
                drawMdIcon(IC_CLOSE, b.bounds.centerX() - 46, b.bounds.centerY(), 22, fg, false);
                textPaint.setTextAlign(Paint.Align.LEFT);
                textPaint.setTextSize(24f);
                textPaint.setColor(fg);
                canvas.drawText("Готово", b.bounds.centerX() - 28, b.bounds.centerY() + 8, textPaint);
                continue;
            }

            boolean toggle = b.id != SET_HEADSET;
            boolean on = b.id == SET_SKELETON ? prefs.getBoolean("pref_hand_skeleton", false)
                    : b.id == SET_6DOF ? prefs.getBoolean("pref_6dof", false)
                    : b.id == SET_SWAP ? prefs.getBoolean("pref_swap_sticks", false)
                    : b.id == SET_GESTURES ? prefs.getBoolean("pref_hand_gestures", true)
                    : b.id == SET_ULTRAWIDE ? prefs.getBoolean("pref_ultrawide", false)
                    : b.id == SET_JUMP && prefs.getBoolean("pref_body_jump", true);
            String icon = b.id == SET_HEADSET ? IC_HEADSET : b.id == SET_SWAP ? IC_SWAP
                    : b.id == SET_GESTURES ? IC_HAND
                    : b.id == SET_ULTRAWIDE ? IC_HEADSET
                    : b.id == SET_SKELETON ? IC_HAND : b.id == SET_6DOF ? IC_6DOF : IC_JUMP;
            String title = b.id == SET_HEADSET ? "VR-шлем" : b.id == SET_SWAP ? "Поменять стики"
                    : b.id == SET_GESTURES ? "Жесты рук"
                    : b.id == SET_ULTRAWIDE ? "Сверхширокий экран"
                    : b.id == SET_SKELETON ? "Скелет рук" : b.id == SET_6DOF ? "6DoF (ARCore)" : "Прыжок телом";

            // Row card.
            md.setStyle(Paint.Style.FILL);
            md.setColor(hov ? 0x22FFFFFF : 0x12FFFFFF);
            canvas.drawRoundRect(b.bounds, 18, 18, md);
            if (b == flashKey) {
                md.setColor(0x33FFFFFF);
                canvas.drawRoundRect(b.bounds, 18, 18, md);
            }
            drawMdIcon(icon, b.bounds.left + 36, b.bounds.centerY(), 24, MD_PRIMARY, false);
            textPaint.setTextAlign(Paint.Align.LEFT);
            textPaint.setTextSize(23f);
            textPaint.setColor(MD_ON_SURFACE);
            canvas.drawText(title, b.bounds.left + 66, b.bounds.centerY() + 8, textPaint);

            if (b.id == SET_HEADSET) {
                String value = PhoneXRBridge.HEADSET_NAMES[PhoneXRBridge.headsetIndex(prefs)];
                textPaint.setTextSize(20f);
                float w = textPaint.measureText(value) + 34;
                RectF chip = new RectF(b.bounds.right - 24 - w, b.bounds.centerY() - 20,
                        b.bounds.right - 24, b.bounds.centerY() + 20);
                md.setColor(MD_PRIMARY_CONTAINER);
                canvas.drawRoundRect(chip, 20, 20, md);
                textPaint.setTextAlign(Paint.Align.CENTER);
                textPaint.setColor(MD_ON_PRIMARY_CONTAINER);
                canvas.drawText(value, chip.centerX(), chip.centerY() + 7, textPaint);
            } else {
                // Material 3 switch.
                RectF track = new RectF(b.bounds.right - 90, b.bounds.centerY() - 17,
                        b.bounds.right - 30, b.bounds.centerY() + 17);
                md.setStyle(Paint.Style.FILL);
                md.setColor(on ? MD_PRIMARY : 0x22FFFFFF);
                canvas.drawRoundRect(track, 17, 17, md);
                if (!on) {
                    md.setStyle(Paint.Style.STROKE);
                    md.setStrokeWidth(2f);
                    md.setColor(0x80FFFFFF);
                    canvas.drawRoundRect(track, 17, 17, md);
                }
                md.setStyle(Paint.Style.FILL);
                md.setColor(on ? MD_ON_PRIMARY : 0xB3FFFFFF);
                canvas.drawCircle(on ? track.right - 17 : track.left + 17, track.centerY(), on ? 13 : 9, md);
            }
        }
    }

    private void drawMdIcon(String glyph, float cx, float cy, float size, int color, boolean filled) {
        mdIcon.setTypeface(filled ? iconFontFilled : iconFont);
        mdIcon.setTextSize(size);
        mdIcon.setColor(color);
        canvas.drawText(glyph, cx, cy + size * 0.36f, mdIcon);
    }

    /** Tone of the accent seed: 0 black … 0.5 ≈ seed … 1 white (stand-in for HCT tones). */
    private static int tone(int seed, float t) {
        float[] hsv = new float[3];
        Color.colorToHSV(seed, hsv);
        hsv[1] *= 1f - Math.abs(t - 0.5f) * 0.9f;
        hsv[2] = 1f;
        int pure = Color.HSVToColor(hsv);
        return t < 0.5f ? blend(0xFF000000, pure, t / 0.5f) : blend(pure, 0xFFFFFFFF, (t - 0.5f) / 0.5f * 0.95f);
    }

    private static int blend(int a, int b, float t) {
        int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return 0xFF000000 | ((int) (ar + (br - ar) * t) << 16)
                | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }

    private void drawKeyboard() {
        tempRect.set(PANEL_L, PANEL_T, PANEL_R, PANEL_B);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xF0121620);
        canvas.drawRoundRect(tempRect, 24, 24, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f);
        paint.setColor(0xFF2E384D);
        canvas.drawRoundRect(tempRect, 24, 24, paint);

        // Input field
        tempRect.set(PANEL_L + 28, FIELD_T, PANEL_R - 28, FIELD_B);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF1E2433);
        canvas.drawRoundRect(tempRect, 14, 14, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.5f);
        paint.setColor(0xFF435375);
        canvas.drawRoundRect(tempRect, 14, 14, paint);

        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setTextSize(26f);
        textPaint.setFakeBoldText(false);
        float maxTextW = tempRect.width() - 40;
        if (currentText.isEmpty()) {
            textPaint.setColor(0xFF75859E);
            canvas.drawText("Point, then pinch or press the trigger", tempRect.left + 20, FIELD_B - 19, textPaint);
        } else {
            textPaint.setColor(0xFFFFFFFF);
            // Show the end of long text so the cursor stays visible.
            int start = 0;
            while (start < currentText.length() - 1
                    && textPaint.measureText(currentText, start, currentText.length()) + 12 > maxTextW) start++;
            canvas.drawText(currentText, start, currentText.length(), tempRect.left + 20, FIELD_B - 19, textPaint);
            float cx = tempRect.left + 22 + textPaint.measureText(currentText, start, currentText.length());
            canvas.drawText("|", cx, FIELD_B - 19, textPaint);
        }

        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);
        for (int i = 0, n = keyboardKeys.size(); i < n; i++) {
            ButtonItem key = keyboardKeys.get(i);
            boolean hov = key == hovered[0] || key == hovered[1];
            boolean flash = key == flashKey;
            int bg = hov ? 0xFF3D4C69 : 0xFF242C3D;
            if (key.id == KEY_DONE) bg = hov ? 0xFF2275FF : 0xFF195CD1;
            if (key.id == KEY_SHIFT && shiftActive) bg = 0xFF2B5BBA;
            if (flash) bg = 0xFF5B8CFF;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(bg);
            canvas.drawRoundRect(key.bounds, 12, 12, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(hov ? 3f : 1f);
            paint.setColor(hov ? 0xFF8AB0FF : 0xFF38445C);
            canvas.drawRoundRect(key.bounds, 12, 12, paint);

            String label = shiftActive ? key.upperLabel : key.label;
            textPaint.setTextSize(label.length() > 1 ? 19f : 28f);
            textPaint.setColor(0xFFFFFFFF);
            canvas.drawText(label, key.bounds.centerX(), key.bounds.centerY() + (label.length() > 1 ? 7 : 10), textPaint);
        }
    }
}
