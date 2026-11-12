package com.phonexr.roblox;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.graphics.ImageFormat;
import android.graphics.drawable.GradientDrawable;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.graphics.Color;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.roblox.engine.jni.NativeGLInterface;
import com.roblox.engine.jni.NativeInputInterface;

import java.nio.ByteBuffer;

public final class PhoneXRBridge implements SensorEventListener {
    private static final String TAG = "PhoneXR-Roblox";
    private static final int DEVICE_ID = 42425;

    private static PhoneXRBridge instance;

    private final Activity activity;
    private final SensorManager sensors;
    private final Sensor rotationSensor;
    private final Sensor gyroscopeSensor;
    private final Sensor accelerometerSensor;
    private final float[] quaternion = new float[4];

    private volatile boolean running;
    private volatile boolean resumed;
    private volatile boolean gameReady;
    /** false = normal Roblox phone UI; true = Roblox VR + Cardboard. */
    private volatile boolean vrActive;
    private int vrRetries;

    private ControllerManager controllerManager;
    private Passthrough passthrough;
    private VRUIManager vrUiManager;

    private boolean cameraHands = false;
    /** Experimental: no controller at all - hand gestures click, a fist walks. */
    private boolean noController;
    private boolean touchLayout = true;
    private int controllerType = 0;
    private boolean swapControllers = false;
    private boolean headTrackingEnabled = true;
    private boolean gyroHeadEnabled = true;
    private boolean passthroughEnabled = false;
    private boolean vrKeyboardEnabled = true;
    private boolean handsVisible = true;
    private volatile boolean handSkeleton;
    private volatile boolean sixDof;
    private volatile boolean bodyJump = true;
    private volatile boolean cardboardHeadEnabled = true;
    /** ARCore 6DoF (optional); while it runs it owns the camera and feeds hand tracking. */
    private ArCoreHead arCore;
    private boolean arInstallPrompted;
    /** Settings apply live: the VR settings panel writes prefs, this re-reads them. */
    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener =
            (prefs, key) -> applyPreferences();

    /** 0 = loading screen, 1 = Roblox home (camera background), 2 = in a game. */
    public static native void setSceneMode(int mode);
    /** false = plain flat Roblox (phone UI), true = Cardboard stereo. */
    public static native void setStereoEnabled(boolean on);
    static final int SCENE_LOADING = 0, SCENE_HOME = 1, SCENE_GAME = 2;
    private volatile int sceneMode = SCENE_LOADING;
    public static native void setHeadPose(float yaw, float pitch);
    /** Head rotation (Roblox space, row-major, columns Right/Up/Back) plus yaw/pitch for the menu. */
    public static native void setHeadRotation(float[] matrix, float yaw, float pitch);
    /** Streams head and hand poses into Roblox VRService; false if this build is unsupported. */
    public static native boolean setRobloxPoseEnabled(boolean enabled);
    public static native boolean setVrMode(boolean on);
    public static native void setGazeMode(boolean on);
    public static native void setRays(float lx, float ly, boolean lActive,
                                     float rx, float ry, boolean rActive,
                                     boolean handsVisible);
    public static native void setHands(float lx, float ly, float la, float ls, boolean lActive,
                                       float rx, float ry, float ra, float rs, boolean rActive,
                                       boolean visible);
    public static native void setUiBuffer(int width, int height, ByteBuffer buffer, boolean visible);
    /** Keeps the UI panel fixed in the world at these head angles (locked) or head-following. */
    public static native void setUiAnchor(float yaw, float pitch, boolean locked);
    public static native void setSkeletonEnabled(boolean enabled);
    /** How many times smaller than the eye view the world-fixed UI panel is shown. */
    public static native void setUiZoom(float zoom);
    /** Ultrawide screen: the Roblox frame at its true proportions instead of squeezed per eye. */
    public static native void setUltrawide(boolean enabled);
    /** Google Cardboard SDK head tracker (native): on/off, viewport 0 = landscape left, 1 = right. */
    public static native boolean setCardboardTracking(boolean enabled, int viewport);
    public static native void cardboardRecenter();
    public static native void cardboardTurn(float radians);
    /** Latest Cardboard head: 9 matrix values (Roblox space) + yaw + pitch. */
    public static native boolean getCardboardHead(float[] out);

    /** Lens barrel distortion k1, k2 and per-eye image shift of the selected headset. */
    public static native void setLensProfile(float k1, float k2, float eyeShift);

    /** Phone VR headsets (from HeadsetSettings.kt): id, name, lens distance mm, field of view. */
    static final String[] HEADSET_IDS = {
            "cardboard2", "cardboard1", "vrbox", "shinecon", "bobovr", "homido",
            "merge", "daydream", "gearvr", "mivr", "other"};
    static final String[] HEADSET_NAMES = {
            "Google Cardboard (2015)", "Google Cardboard (2014)", "VR Box", "VR Shinecon",
            "BOBOVR Z4 / Z5", "Homido", "Merge VR", "Google Daydream View", "Samsung Gear VR",
            "Xiaomi Mi VR Play", "Другой шлем"};
    static final int[] HEADSET_LENSES_MM = {64, 60, 63, 62, 62, 64, 63, 64, 62, 62, 64};
    static final float[] HEADSET_FOV_DEG = {60f, 40f, 60f, 60f, 65f, 60f, 60f, 60f, 60f, 60f, 60f};

    /** Chosen headset (pref_headset_id), the 2015 Cardboard before any choice. */
    static int headsetIndex(SharedPreferences prefs) {
        String id = prefs.getString("pref_headset_id", HEADSET_IDS[0]);
        for (int i = 0; i < HEADSET_IDS.length; i++) if (HEADSET_IDS[i].equals(id)) return i;
        return 0;
    }

    static void chooseHeadset(SharedPreferences prefs, int index) {
        prefs.edit().putString("pref_headset_id", HEADSET_IDS[index % HEADSET_IDS.length]).apply();
    }
    /** 6DoF head position in UserCFrame units (studs), zero for 3DoF. */
    public static native void setHeadPosition(float x, float y, float z);
    /** 21 screen-space landmarks per hand (42 floats), or null when that hand is not seen. */
    public static native void setSkeleton(float[] left, float[] right);
    public static native void setCameraBuffer(int width, int height, ByteBuffer buffer, boolean active);

    private long lastEnableAttempt;
    private long lastGyroUpdate;
    private float smoothYaw;
    private float smoothPitch;
    private float appliedYaw;
    private float appliedPitch;
    private long lastHeadLog;


    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable uiUpdateLoop;
    private int keepRenderingTicks, gearCheckTicks;
    private TextView settingsGear;

    private PhoneXRBridge(Activity activity) {
        this.activity = activity;
        sensors = (SensorManager) activity.getSystemService(Context.SENSOR_SERVICE);
        Sensor rot = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        rotationSensor = rot != null ? rot
                : (sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR));
        gyroscopeSensor = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        accelerometerSensor = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
    }

    public static synchronized void start(Activity activity) {
        if (instance != null) {
            instance.stopInternal();
        }
        instance = new PhoneXRBridge(activity);
        instance.startInternal();
    }

    public static synchronized void resume() {
        if (instance != null) instance.resumeInternal();
    }

    public static synchronized void pause() {
        if (instance != null) instance.pauseInternal();
    }

    public static synchronized void stop() {
        if (instance != null) {
            instance.stopInternal();
            instance = null;
        }
    }

    /** Replaces the flat Roblox splash with a stereo "Loading" screen. */
    public static void showSplash(Activity activity) {
        try {
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            FrameLayout cover = new FrameLayout(activity);
            cover.setBackgroundColor(Color.BLACK);
            android.widget.LinearLayout eyes = new android.widget.LinearLayout(activity);
            eyes.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            for (int i = 0; i < 2; i++) {
                FrameLayout eye = new FrameLayout(activity);
                TextView text = new TextView(activity);
                text.setText(tr("Загрузка", "Loading", "Carregando"));
                text.setTextColor(Color.WHITE);
                text.setTextSize(22f);
                text.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                text.setGravity(Gravity.CENTER);
                eye.addView(text, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                TextView by = new TextView(activity);
                by.setText("By Samrat :)  ·  Alpha 26.3.1");
                by.setTextColor(0xCCFFFFFF);
                by.setTextSize(14f);
                by.setGravity(Gravity.CENTER);
                FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
                bp.bottomMargin = (int) (24 * activity.getResources().getDisplayMetrics().density);
                eye.addView(by, bp);
                eyes.addView(eye, new android.widget.LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            }
            cover.addView(eyes, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            cover.setClickable(true);
            activity.addContentView(cover, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        } catch (Throwable error) {
            Log.w(TAG, "Loading splash failed", error);
        }
    }

    /** Real-world walking seen by ARCore (stick x right, y forward, -1..1): the character walks too. */
    public static void onPhysicalWalk(float x, float y) {
        PhoneXRBridge b = instance;
        if (b != null && b.controllerManager != null) b.controllerManager.setBodyWalk(x, y);
    }

    public static synchronized PhoneXRBridge getInstance() {
        return instance;
    }

    public VRUIManager getVrUiManager() {
        return vrUiManager;
    }

    public static void onShowKeyboard(View view) {
        if (instance != null && instance.vrKeyboardEnabled && instance.vrUiManager != null) {
            String initial = "";
            if (view instanceof TextView) {
                CharSequence text = ((TextView) view).getText();
                if (text != null) initial = text.toString();
            }
            instance.vrUiManager.showKeyboard(view, initial);
        }
    }

    public static void onHideKeyboard() {
        if (instance != null && instance.vrUiManager != null) {
            instance.vrUiManager.hideKeyboard();
        }
    }

    public static synchronized void gameLoaded(long id) {
        Log.i(TAG, "gameLoadedCallback id=" + id);
        if (instance != null) {
            instance.gameReady = true;
            // The Roblox VR home is a DataModel without a place id; games carry theirs.
            instance.setScene(id == 0 ? SCENE_HOME : SCENE_GAME);
            instance.activity.runOnUiThread(() -> {
                if (instance != null && instance.passthrough != null)
                    instance.passthrough.setPreviewVisible(false);
                if (instance != null) {
                    if (instance.vrUiManager != null) instance.vrUiManager.setMenuMode(false);
                    if (instance.controllerManager != null) instance.controllerManager.refreshUiMode();
                }
            });
            Log.i(TAG, "Roblox game loaded; enabling VR Virtual Input");
            if (instance.vrActive) {
                instance.enableRobloxVr();
                instance.setRobloxPose(true);
            }
        }
    }

    public static synchronized void gameLeft() {
        // Leaving a game returns to the Roblox home, which keeps head and hands.
        if (instance != null) instance.setScene(SCENE_HOME);
    }

    /**
     * Roblox reads FFlag overrides from files/exe/ClientSettings/ClientAppSettings.json.
     * A dark VR home lobby lets the compositor swap the scene for the phone camera.
     */
    private void writeClientFlags() {
        try {
            java.io.File file = new java.io.File(activity.getFilesDir(), "exe/ClientSettings/ClientAppSettings.json");
            org.json.JSONObject flags = new org.json.JSONObject();
            if (file.exists()) {
                byte[] data = new byte[(int) file.length()];
                java.io.FileInputStream in = new java.io.FileInputStream(file);
                int read = 0;
                while (read < data.length) {
                    int n = in.read(data, read, data.length - read);
                    if (n < 0) break;
                    read += n;
                }
                in.close();
                try { flags = new org.json.JSONObject(new String(data, 0, read, "UTF-8")); } catch (Throwable ignored) { }
            }
            // The forced "Dark" lobby replaced Roblox's plain light VR home with a dark, moving
            // environment a while after start; keep Roblox's default (light) instead.
            flags.remove("FStringDefaultVREnvironmentSetting");
            java.io.File dir = file.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            java.io.FileOutputStream out = new java.io.FileOutputStream(file);
            out.write(flags.toString().getBytes("UTF-8"));
            out.close();
        } catch (Throwable error) {
            Log.w(TAG, "Could not write Roblox client flags", error);
        }
    }

    private static String tr(String ru, String en, String pt) {
        String l = java.util.Locale.getDefault().getLanguage();
        return l.equals("ru") ? ru : l.equals("pt") ? pt : en;
    }

    /** Phone-UI phase: flat Roblox + a bar. The user logs in / picks a game, then starts VR. */
    private View clickOverlay;
    private View reticle;
    private View credits;

    /** A small dot in the centre of each eye: where the gaze pointer clicks in the home menu. */
    private final class Reticle extends View {
        private boolean drawLogged;
        private final android.graphics.Paint dot = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint ring = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        Reticle(Context c) {
            super(c);
            dot.setColor(0xFFFFFFFF);
            ring.setColor(0xCC000000);
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            float d = getResources().getDisplayMetrics().density;
            float w = getWidth(), h = getHeight();
            if (!drawLogged) { drawLogged = true; Log.i(TAG, "reticle onDraw " + w + "x" + h); }
            for (float cx : new float[]{0.2785f * w, 0.7215f * w}) {
                canvas.drawCircle(cx, h / 2f, 6f * d, ring);
                canvas.drawCircle(cx, h / 2f, 4f * d, dot);
            }
        }
    }

    /** Cardboard-style button: touching the screen anywhere is a click (trigger). */
    private void addClickOverlay() {
        activity.runOnUiThread(() -> {
            if (clickOverlay != null) return;
            View v = new View(activity);
            v.setOnTouchListener((view, e) -> {
                int a = e.getActionMasked();
                if (a == android.view.MotionEvent.ACTION_DOWN) { if (controllerManager != null) controllerManager.screenClick(true); }
                else if (a == android.view.MotionEvent.ACTION_UP || a == android.view.MotionEvent.ACTION_CANCEL) {
                    if (controllerManager != null) controllerManager.screenClick(false);
                }
                return true;
            });
            clickOverlay = v;
            activity.addContentView(v, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            reticle = new Reticle(activity);
            reticle.setVisibility(View.GONE);
            reticle.setElevation(1000f);
            reticle.setWillNotDraw(false);
            activity.addContentView(reticle, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            credits = new Credits.CreditView(activity);
            credits.setElevation(1001f);
            activity.addContentView(credits, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            if (settingsGear != null) settingsGear.bringToFront();
        });
    }

    private void startVr() {
        if (!Credits.intact() || credits == null || credits.getParent() == null) {
            Log.e(TAG, "credits missing or altered: VR will not start");
            return;
        }
        boolean ok = false;
        try { ok = setVrMode(true); } catch (Throwable t) { Log.w(TAG, "setVrMode failed", t); }
        Log.i(TAG, "VR mode " + (ok ? "on" : "not ready yet"));
        if (!ok && vrRetries++ < 40) mainHandler.postDelayed(this::startVr, 500); // libroblox may still be loading
        vrActive = true;
        setStereoEnabled(true);
        addClickOverlay();
        if (gameReady) {
            lastEnableAttempt = 0;
            enableRobloxVr();
            setRobloxPose(true);
        }
    }

    private void showIntro() {
        try {
            vrActive = false;
            setStereoEnabled(false);
            final Activity a = activity;
            a.runOnUiThread(() -> {
                float d = a.getResources().getDisplayMetrics().density;
                android.widget.LinearLayout bar = new android.widget.LinearLayout(a);
                bar.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                bar.setGravity(Gravity.CENTER_VERTICAL);
                bar.setBackgroundColor(0xE0000000);
                bar.setPadding((int) (12 * d), (int) (6 * d), (int) (12 * d), (int) (6 * d));
                TextView msg = new TextView(a);
                msg.setText(tr("Вытащите телефон из VR-шлема: войдите и выберите игру в меню. Потом вставьте его в шлем и нажмите «Старт VR».",
                        "Take the phone out of the VR headset: log in and pick a game in the menu. Then put it back in and tap “Start VR”.",
                        "Tire o celular do óculos VR: faça login e escolha um jogo no menu. Depois coloque-o de volta e toque em “Iniciar VR”."));
                msg.setTextColor(Color.WHITE);
                msg.setTextSize(12f);
                bar.addView(msg, new android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                android.widget.Button go = new android.widget.Button(a);
                final String label = tr("Старт VR", "Start VR", "Iniciar VR");
                go.setText(label);
                bar.addView(go);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
                a.addContentView(bar, lp);
                go.setOnClickListener(v -> {
                    go.setEnabled(false);
                    final int[] left = {10};
                    final Runnable[] tick = new Runnable[1];
                    tick[0] = () -> {
                        if (left[0] <= 0) {
                            startVr();
                            ((ViewGroup) bar.getParent()).removeView(bar);
                            return;
                        }
                        msg.setText(tr("Вставьте телефон в шлем… ", "Put the phone in the headset… ", "Coloque o celular no óculos… ") + left[0]);
                        go.setText(String.valueOf(left[0]--));
                        bar.postDelayed(tick[0], 1000);
                    };
                    tick[0].run();
                });
            });
        } catch (Throwable t) {
            Log.w(TAG, "intro failed", t);
            try { startVr(); } catch (Throwable ignored) { }
        }
    }

    private void setScene(int mode) {
        sceneMode = mode;
        try {
            setSceneMode(mode);
        } catch (Throwable ignored) { }
        Log.i(TAG, "Scene " + (mode == SCENE_LOADING ? "loading" : mode == SCENE_HOME ? "home" : "game"));
    }

    int getSceneMode() {
        return sceneMode;
    }

    private void startInternal() {
        running = true;
        activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        // In a headset nobody touches the screen: keep it on while the app is open.
        activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        activity.getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        writeClientFlags();
        vrUiManager = new VRUIManager(activity);
        vrUiManager.setMenuMode(!gameReady);

        loadPreferences();
        activity.getSharedPreferences("roblox_vr", Context.MODE_PRIVATE)
                .registerOnSharedPreferenceChangeListener(prefsListener);

        controllerManager = new ControllerManager(activity);
        controllerManager.setBridge(this);
        controllerManager.setTouchLayout(touchLayout);
        controllerManager.setControllerProfile(controllerType, swapControllers);
        controllerManager.setHandsVisible(handsVisible);
        controllerManager.start();
        try { setSkeletonEnabled(handSkeleton); } catch (Throwable ignored) { }
        applyLensProfile();

        passthrough = new Passthrough();
        if (needsCamera()) {
            passthrough.start();
            passthrough.setPreviewVisible(false);
        }

        setScene(gameReady ? sceneMode : SCENE_LOADING);
        if (activity.getSharedPreferences("roblox_vr", Context.MODE_PRIVATE).getBoolean("pref_intro", false)) showIntro();
        else startVr();

        resumeInternal();

        uiUpdateLoop = new Runnable() {
            @Override
            public void run() {
                if (running && vrUiManager != null) {
                    // Roblox keeps resetting the screen to portrait: hold landscape for the headset.
                    if (vrActive && activity.getRequestedOrientation() != ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
                        activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                    if (controllerManager != null) controllerManager.tickGaze();
                    if (reticle != null) {
                        boolean show = vrActive && sceneMode == SCENE_HOME && controllerManager != null && controllerManager.isGaze();
                        if ((reticle.getVisibility() == View.VISIBLE) != show) {
                            reticle.setVisibility(show ? View.VISIBLE : View.GONE);
                            Log.i(TAG, "reticle " + (show ? "shown" : "hidden"));
                        }
                    }
                    vrUiManager.updateAndUpload();
                    // Roblox sets its content view after start() and adds views later, which
                    // dropped or covered the gear: re-attach it and keep it on top (~1 s).
                    pollCardboardHead();
                    if (++gearCheckTicks % 60 == 1) {
                        ensureSettingsButton();
                        // 6DoF: ARCore's availability check is asynchronous and its session can
                        // fail while camera2 is still closing, so keep (re)trying while it is on.
                        if (sixDof && resumed && (arCore == null || !arCore.isRunning())) updateCameraSource();
                    }
                    if (sceneMode == SCENE_HOME && resumed && controllerManager != null
                            && ++keepRenderingTicks % 2 == 0) controllerManager.keepRendering();
                    mainHandler.postDelayed(this, 16);
                }
            }
        };
        mainHandler.post(uiUpdateLoop);
    }

    /** Gear on the right edge of the phone screen: tap it (before the headset) for VR settings. */
    /** Roblox re-creates its views after we start: keep our touch layer and reticle attached and on top. */
    private void ensureOverlays() {
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) return;
        for (View v : new View[]{clickOverlay, reticle, credits}) {
            if (v == null) continue;
            if (v == credits) Credits.enforce(v);
            if (v.getParent() != content) {
                if (v.getParent() != null) ((ViewGroup) v.getParent()).removeView(v);
                activity.addContentView(v, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            } else {
                v.bringToFront();
            }
        }
    }

    private void ensureSettingsButton() {
        ensureOverlays();
        if (settingsGear == null) { addSettingsButton(); return; }
        ViewGroup parent = (ViewGroup) settingsGear.getParent();
        if (parent == null || parent != activity.findViewById(android.R.id.content)) {
            if (parent != null) parent.removeView(settingsGear);
            attachGear();
        } else if (parent.getChildAt(parent.getChildCount() - 1) != settingsGear) {
            settingsGear.bringToFront();
        }
    }

    private void attachGear() {
        int size = (int) (52 * activity.getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(size, size,
                Gravity.END | Gravity.CENTER_VERTICAL);
        params.rightMargin = size / 4;
        activity.addContentView(settingsGear, params);
        settingsGear.setElevation(1000f);
    }

    private void addSettingsButton() {
        try {
            TextView gear = new TextView(activity);
            gear.setText("\u2699");
            gear.setTextSize(26f);
            gear.setTextColor(Color.WHITE);
            gear.setGravity(Gravity.CENTER);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(0x99202634);
            gear.setBackground(bg);
            gear.setAlpha(0.8f);
            gear.setOnClickListener(v -> showSettingsDialog());
            settingsGear = gear;
            attachGear();
        } catch (Throwable error) {
            Log.w(TAG, "Settings button failed", error);
        }
    }

    // Material You accent for the on-screen settings sheet (matches the VR panel's accent).
    private static final int MD_ACCENT = 0xFF8AB4F8;

    private void showSettingsDialog() {
        final SharedPreferences prefs = activity.getSharedPreferences("roblox_vr", Context.MODE_PRIVATE);
        float d = activity.getResources().getDisplayMetrics().density;
        android.widget.LinearLayout root = new android.widget.LinearLayout(activity);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (24 * d);
        root.setPadding(pad, (int) (20 * d), pad, (int) (12 * d));
        GradientDrawable panel = new GradientDrawable();
        panel.setColor(0xFF1A1B20);
        panel.setCornerRadius(28 * d);
        root.setBackground(panel);

        TextView title = new TextView(activity);
        title.setText("Настройки VR · Alpha 26.3.1");
        title.setTextColor(0xFFE4E2E6);
        title.setTextSize(21f);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        title.setPadding(0, 0, 0, (int) (14 * d));
        root.addView(title);

        final android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(activity)
                .setView(wrapScroll(root, d)).create();

        // Headset chooser row (cycles on tap).
        final TextView headsetValue = addChoiceRow(root, d, "🥽", "VR-шлем",
                HEADSET_NAMES[headsetIndex(prefs)]);
        headsetValue.getRootView(); // no-op keeps lint quiet
        ((View) headsetValue.getParent()).setOnClickListener(v -> {
            int next = (headsetIndex(prefs) + 1) % HEADSET_NAMES.length;
            chooseHeadset(prefs, next);
            headsetValue.setText(HEADSET_NAMES[next]);
        });

        addSwitchRow(root, d, prefs, "🖥", "Сверхширокий экран", "pref_ultrawide", false);
        // Experimental, marked with a red outline.
        final android.widget.LinearLayout noCtl = addSwitchRow(root, d, prefs, "🖐", "Без контроллера (эксперимент)", "pref_no_controller", false);
        GradientDrawable redBg = new GradientDrawable();
        redBg.setColor(0x14FFFFFF);
        redBg.setCornerRadius(18 * d);
        redBg.setStroke((int) (2 * d), 0xFFE53935);
        noCtl.setBackground(redBg);
        addSwitchRow(root, d, prefs, "🔁", "Поменять стики", "pref_swap_sticks", false);
        addSwitchRow(root, d, prefs, "👋", "Скелет рук", "pref_hand_skeleton", false);
        // Controller kind; camera hand tracking is available for every kind (off by default).
        final String[] kinds = {"Joy-Con / Pro Controller", "Xbox", "PlayStation (DualShock / DualSense)"};
        final android.widget.LinearLayout handsRow = addSwitchRow(root, d, prefs, "✋", "Трекинг рук камерой", "camera_hands2", false);
        final TextView kindValue = addChoiceRow(root, d, "🎮", "Контроллер", kinds[prefs.getInt("controller_kind", 0) % 3]);
        root.removeView(handsRow); // keep the chooser above the hands switch
        root.addView(handsRow, root.indexOfChild((View) kindValue.getParent()) + 1);
        handsRow.setVisibility(View.VISIBLE);
        ((View) kindValue.getParent()).setOnClickListener(v -> {
            int next = (prefs.getInt("controller_kind", 0) + 1) % 3;
            prefs.edit().putInt("controller_kind", next).apply();
            kindValue.setText(kinds[next]);
        });
        addSwitchRow(root, d, prefs, "🧊", "6DoF (ARCore)", "pref_6dof", false);
        addSwitchRow(root, d, prefs, "🏃", "Прыжок телом", "pref_body_jump", true);

        TextView done = new TextView(activity);
        done.setText("Готово");
        done.setTextColor(0xFF10233F);
        done.setTextSize(16f);
        done.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        done.setGravity(Gravity.CENTER);
        done.setPadding(0, (int) (13 * d), 0, (int) (13 * d));
        GradientDrawable donebg = new GradientDrawable();
        donebg.setColor(MD_ACCENT);
        donebg.setCornerRadius(24 * d);
        done.setBackground(donebg);
        android.widget.LinearLayout.LayoutParams dl = new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dl.topMargin = (int) (12 * d);
        done.setLayoutParams(dl);
        done.setOnClickListener(v -> dialog.dismiss());
        root.addView(done);

        if (dialog.getWindow() != null)
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
        dialog.show();
    }

    private View wrapScroll(View content, float d) {
        android.widget.ScrollView scroll = new android.widget.ScrollView(activity);
        scroll.setPadding((int) (12 * d), 0, (int) (12 * d), 0);
        scroll.addView(content);
        return scroll;
    }

    /** One row: emoji icon, label, and a Material-style switch bound to a boolean pref. */
    private android.widget.LinearLayout addSwitchRow(android.widget.LinearLayout root, float d, SharedPreferences prefs,
                              String icon, String label, String key, boolean def) {
        android.widget.LinearLayout row = rowBase(d, icon, label);
        android.widget.Switch sw = new android.widget.Switch(activity);
        sw.setChecked(prefs.getBoolean(key, def));
        int[][] states = {{android.R.attr.state_checked}, {}};
        sw.setThumbTintList(new android.content.res.ColorStateList(states, new int[]{MD_ACCENT, 0xFFBEC2CC}));
        sw.setTrackTintList(new android.content.res.ColorStateList(states, new int[]{0x808AB4F8, 0x40FFFFFF}));
        sw.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean(key, on).apply());
        row.addView(sw);
        row.setOnClickListener(v -> sw.toggle());
        root.addView(row);
        return row;
    }

    private TextView addChoiceRow(android.widget.LinearLayout root, float d, String icon, String label, String value) {
        android.widget.LinearLayout row = rowBase(d, icon, label);
        TextView chip = new TextView(activity);
        chip.setText(value);
        chip.setTextColor(0xFFD6E3FF);
        chip.setTextSize(14f);
        chip.setPadding((int) (14 * d), (int) (6 * d), (int) (14 * d), (int) (6 * d));
        GradientDrawable chipBg = new GradientDrawable();
        chipBg.setColor(0xFF2A3B57);
        chipBg.setCornerRadius(16 * d);
        chip.setBackground(chipBg);
        row.addView(chip);
        root.addView(row);
        return chip;
    }

    private android.widget.LinearLayout rowBase(float d, String icon, String label) {
        android.widget.LinearLayout row = new android.widget.LinearLayout(activity);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int) (14 * d), (int) (12 * d), (int) (14 * d), (int) (12 * d));
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (8 * d);
        row.setLayoutParams(lp);
        GradientDrawable rb = new GradientDrawable();
        rb.setColor(0x14FFFFFF);
        rb.setCornerRadius(18 * d);
        row.setBackground(rb);
        TextView ic = new TextView(activity);
        ic.setText(icon);
        ic.setTextSize(18f);
        ic.setPadding(0, 0, (int) (12 * d), 0);
        row.addView(ic);
        TextView tv = new TextView(activity);
        tv.setText(label);
        tv.setTextColor(0xFFE4E2E6);
        tv.setTextSize(16f);
        android.widget.LinearLayout.LayoutParams tl = new android.widget.LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tv.setLayoutParams(tl);
        row.addView(tv);
        return row;
    }

    private void loadPreferences() {
        SharedPreferences preferences = activity.getSharedPreferences("roblox_vr", Context.MODE_PRIVATE);
        int kind = preferences.getInt("controller_kind", 0);
        noController = preferences.getBoolean("pref_no_controller", false);
        cameraHands = noController || preferences.getBoolean("camera_hands2", false); // off by default
        touchLayout = preferences.getBoolean("touch_layout", true);
        // Roblox Android gamepad types: 0 generic, 1 PS4, 2 PS5, 3 Xbox. There is no
        // Touch type, so the Joy-Con buttons go out as a generic VR-style gamepad.
        int padKind = preferences.getInt("controller_kind", 0);
        controllerType = padKind == 1 ? 3 : padKind == 2 ? 1 : 0; // Roblox: 0 generic, 1 PS4, 3 Xbox
        swapControllers = preferences.getBoolean("swap_controllers", false);
        headTrackingEnabled = preferences.getBoolean("pref_head_tracking", true);
        boolean gyroHead = preferences.getBoolean("pref_head_gyro", true) && gyroscopeSensor != null;
        if (gyroHead != gyroHeadEnabled) resetHeadSource();
        gyroHeadEnabled = gyroHead;
        passthroughEnabled = false;
        vrKeyboardEnabled = preferences.getBoolean("pref_vr_keyboard", true);
        handsVisible = preferences.getBoolean("pref_hands", true);
        handSkeleton = preferences.getBoolean("pref_hand_skeleton", false);
        sixDof = preferences.getBoolean("pref_6dof", false);
        bodyJump = preferences.getBoolean("pref_body_jump", true);
        cardboardHeadEnabled = preferences.getBoolean("pref_head_cardboard", true);
    }

    /** Camera owner: ARCore when 6DoF is on (and ARCore is installed), camera2 otherwise. */
    private boolean cameraAsked;

    private void updateCameraSource() {
        // ARCore and camera hand tracking both need the camera: ask once, then wait for the answer.
        if ((sixDof || cameraHands) && activity.checkSelfPermission(android.Manifest.permission.CAMERA)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (!cameraAsked) {
                cameraAsked = true;
                try { activity.requestPermissions(new String[]{android.Manifest.permission.CAMERA}, 4242); }
                catch (Throwable error) { Log.w(TAG, "camera permission request failed", error); }
            }
            return;
        }
        boolean wantAr = sixDof && resumed && running && (arCore == null || !arCore.failedRecently())
                && ArCoreHead.available(activity);
        if (sixDof && !wantAr && !arInstallPrompted && ArCoreHead.needsInstall(activity)) {
            // 6DoF needs "Google Play Services for AR": open its Play Store page once.
            arInstallPrompted = true;
            try {
                activity.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("market://details?id=com.google.ar.core")));
            } catch (Throwable error) {
                Log.w(TAG, "Play Store unavailable for ARCore install", error);
            }
        }
        if (wantAr) {
            if (arCore == null) {
                arCore = new ArCoreHead(activity, new ArCoreHead.ImageSink() {
                    public void onImage(Image image) {
                        Passthrough target = passthrough;
                        if (target != null) target.processImage(image);
                    }
                    public float headYaw() { return smoothYaw; }
                });
            }
            if (!arCore.isRunning()) {
                if (passthrough != null) passthrough.stopCamera();
                arCore.start();
            }
        } else {
            if (arCore != null && arCore.isRunning()) arCore.stop();
            if (passthrough != null && resumed && needsCamera()) passthrough.start();
        }
    }

    /**
     * Headset lenses -> compositor, as HeadsetSettings.kt's Eyes.load: each eye's picture is
     * drawn centred on a quarter of the screen; move it in under its lens, as a share of half the
     * screen's width (the compositor's eye coordinates).
     */
    private void applyLensProfile() {
        try {
            setUltrawide(activity.getSharedPreferences("roblox_vr", Context.MODE_PRIVATE)
                    .getBoolean("pref_ultrawide", false));
        } catch (Throwable ignored) { }
        int index = headsetIndex(activity.getSharedPreferences("roblox_vr", Context.MODE_PRIVATE));
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        activity.getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
        int longSide = Math.max(metrics.widthPixels, metrics.heightPixels);
        float dpi = metrics.widthPixels >= metrics.heightPixels ? metrics.xdpi : metrics.ydpi;
        if (dpi <= 1f) dpi = 400f;
        float screenMm = longSide / dpi * 25.4f;
        float inwardMm = screenMm / 4f - HEADSET_LENSES_MM[index] / 2f;
        float shift = Math.max(-0.3f, Math.min(0.3f, inwardMm / (screenMm / 2f)));
        Log.i(TAG, "Headset " + HEADSET_NAMES[index] + ": lenses " + HEADSET_LENSES_MM[index]
                + " mm, screen " + screenMm + " mm, eye shift " + shift);
        try { setLensProfile(0.18f, 0.04f, shift); } catch (Throwable ignored) { }
    }

    /** Re-reads the settings and applies them without restarting the app. */
    private void applyPreferences() {
        if (!running) return;
        loadPreferences();
        if (controllerManager != null) {
            controllerManager.setTouchLayout(touchLayout);
            controllerManager.setHandsVisible(handsVisible);
            controllerManager.reloadPreferences();
        }
        if (controllerManager != null) controllerManager.setControllerProfile(controllerType, swapControllers);
        applyLensProfile();
        updateCardboard();
        try {
            setSkeletonEnabled(handSkeleton);
            if (!handSkeleton) setSkeleton(null, null);
        } catch (Throwable ignored) { }
        updateCameraSource();
    }

    private void resumeInternal() {
        if (resumed) return;
        resumed = true;
        if (sensors != null && rotationSensor != null) {
            sensors.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_FASTEST);
        }
        if (sensors != null && gyroscopeSensor != null) {
            sensors.registerListener(this, gyroscopeSensor, SensorManager.SENSOR_DELAY_FASTEST);
        }
        if (sensors != null && accelerometerSensor != null) {
            sensors.registerListener(this, accelerometerSensor, SensorManager.SENSOR_DELAY_FASTEST);
        }
        headTracker.reset();
        updateCardboard();
        if (controllerManager != null) controllerManager.resume();
        if (passthrough != null && needsCamera() && !sixDof) passthrough.start();
        updateCameraSource();
        enableRobloxVr();
    }

    private void pauseInternal() {
        if (!resumed) return;
        resumed = false;
        if (sensors != null) sensors.unregisterListener(this);
        try { setCardboardTracking(false, 0); } catch (Throwable ignored) { }
        cardboardActive = false;
        if (controllerManager != null) controllerManager.pause();
        // Hand the camera back to the system while paused.
        if (arCore != null) arCore.stop();
    }

    private void stopInternal() {
        running = false;
        activity.getSharedPreferences("roblox_vr", Context.MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(prefsListener);
        pauseInternal();
        if (uiUpdateLoop != null) mainHandler.removeCallbacks(uiUpdateLoop);
        if (controllerManager != null) controllerManager.stop();
        controllerManager = null;
        if (passthrough != null) passthrough.stop();
        passthrough = null;
        setRobloxPose(false);
        try {
            NativeInputInterface.nativeGamepadDisconnectEvent(DEVICE_ID);
            NativeInputInterface.nativeSetGyroscopeEnabled(false);
            NativeGLInterface.enableVRVirtualInput(false);
        } catch (Throwable ignored) { }
    }

    private boolean needsCamera() {
        return passthroughEnabled || cameraHands;
    }


    public void recenter() {
        headTracker.recenter();
        if (cardboardActive) try { cardboardRecenter(); } catch (Throwable ignored) { }
        if (arCore != null) arCore.recenter();
        smoothYaw = 0f;
        smoothPitch = 0f;
        appliedYaw = 0f;
        appliedPitch = 0f;
        if (controllerManager != null) controllerManager.recenterMotion();
    }

    private volatile boolean robloxPoseActive;

    private void setRobloxPose(boolean enabled) {
        try {
            robloxPoseActive = setRobloxPoseEnabled(enabled) && enabled;
        } catch (Throwable error) {
            robloxPoseActive = false;
            Log.w(TAG, "Roblox VR pose writer unavailable", error);
        }
        if (enabled) Log.i(TAG, "Roblox VR head/hand pose " + (robloxPoseActive ? "active" : "unsupported"));
    }

    private void resetHeadSource() {
        headTracker.reset();
        smoothYaw = 0f;
        smoothPitch = 0f;
        appliedYaw = 0f;
        appliedPitch = 0f;
    }

    /** Head yaw/pitch relative to the recentered origin, in radians. */
    float getHeadYaw() { return smoothYaw; }
    float getHeadPitch() { return smoothPitch; }

    public void turnYaw(float deltaYaw) {
        headTracker.turn(deltaYaw);
        if (cardboardActive) try { cardboardTurn(deltaYaw); } catch (Throwable ignored) { }
    }

    // ---- Google Cardboard SDK head tracking (default; the Java tracker is the fallback) ----
    private volatile boolean cardboardActive;
    private int cardboardViewport = -1;
    private final float[] cardboardHead = new float[11];

    /** Starts/stops the Cardboard tracker for the current settings and screen orientation. */
    private void updateCardboard() {
        boolean want = resumed && headTrackingEnabled && cardboardHeadEnabled;
        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        int viewport = rotation == Surface.ROTATION_270 ? 1 : 0;
        if (want == cardboardActive && viewport == cardboardViewport) return;
        cardboardViewport = viewport;
        try {
            cardboardActive = setCardboardTracking(want, viewport);
        } catch (Throwable error) {
            cardboardActive = false;
            Log.w(TAG, "Cardboard head tracker unavailable, using the built-in one", error);
        }
        Log.i(TAG, "Head tracking: " + (cardboardActive ? "Cardboard SDK" : "built-in"));
    }

    /** Main-thread poll (~60 Hz): head angles for our panels, and the no-VR camera fallback. */
    private void pollCardboardHead() {
        if (!cardboardActive) return;
        if (++cardboardPolls % 30 == 0) updateCardboard(); // orientation flips
        try {
            if (!getCardboardHead(cardboardHead)) return;
        } catch (Throwable error) {
            return;
        }
        synchronized (headMatrix) { System.arraycopy(cardboardHead, 0, headMatrix, 0, 9); }
        applyHead(cardboardHead[9], cardboardHead[10], false);
    }

    private int cardboardPolls;

    public void toggleHands() {
        handsVisible = !handsVisible;
        if (controllerManager != null) controllerManager.setHandsVisible(handsVisible);
        activity.getSharedPreferences("roblox_vr", Context.MODE_PRIVATE)
                .edit().putBoolean("pref_hands", handsVisible).apply();
    }

    private void enableRobloxVr() {
        if (!gameReady || !vrActive) return;
        long now = System.currentTimeMillis();
        if (now - lastEnableAttempt < 600) return;
        lastEnableAttempt = now;
        try {
            NativeGLInterface.enableVRVirtualInput(true);
            // The phone-gyro camera would rotate the view a second time on top of the VR head.
            NativeInputInterface.nativeSetGyroscopeEnabled(false);
        } catch (Throwable error) {
            Log.d(TAG, "Roblox engine is not ready for VR input yet");
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!resumed || event == null) return;
        try {
            int type = event.sensor.getType();
            if (type == Sensor.TYPE_ACCELEROMETER) {
                headTracker.onAccelerometer(event.values);
                detectJump(event.values, event.timestamp);
                return;
            }
            if (!headTrackingEnabled || cardboardActive) return;
            if (type == Sensor.TYPE_GYROSCOPE) {
                if (!gyroHeadEnabled) return;
                headTracker.onGyroscope(event.values, event.timestamp);
            } else if (type == Sensor.TYPE_GAME_ROTATION_VECTOR || type == Sensor.TYPE_ROTATION_VECTOR) {
                if (gyroHeadEnabled) return;
                SensorManager.getQuaternionFromVector(quaternion, event.values);
                headTracker.setDeviceOrientation(quaternion[0], quaternion[1], quaternion[2], quaternion[3]);
            } else {
                return;
            }
            publishHead(event.timestamp);
        } catch (Throwable error) {
            enableRobloxVr();
        }
    }

    private final HeadTracker headTracker = new HeadTracker();
    private final float[] headMatrix = new float[9];

    /** Latest head rotation in Roblox space (row-major, columns Right/Up/Back). */
    void copyHeadMatrix(float[] out) {
        synchronized (headMatrix) { System.arraycopy(headMatrix, 0, out, 0, 9); }
    }
    private final float[] headYawPitch = new float[2];
    private long lastRotationQueryMs = Long.MIN_VALUE / 2;
    private int cachedDisplayRotation;

    private void publishHead(long timestamp) {
        if (!headTracker.isReady()) return;
        if (timestamp - lastGyroUpdate < 4_000_000L) return;
        lastGyroUpdate = timestamp;

        // The display rotation rarely changes; querying it at the sensor rate (~250 Hz) is wasted work.
        long nowMs = timestamp / 1_000_000L;
        if (nowMs - lastRotationQueryMs > 500) {
            lastRotationQueryMs = nowMs;
            cachedDisplayRotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        }
        int displayRotation = cachedDisplayRotation;
        synchronized (headMatrix) {
            headTracker.headMatrix(displayRotation, headMatrix, headYawPitch);
        }
        applyHead(headYawPitch[0], headYawPitch[1], true);
    }

    /** New head angles: to the compositor/Roblox (built-in tracker), and the camera fallback. */
    private void applyHead(float yaw, float pitch, boolean sendRotation) {
        smoothYaw = yaw;
        smoothPitch = pitch;
        // Full 3DoF rotation for Roblox VR plus yaw/pitch for the menu compositor. The Cardboard
        // tracker writes its own rotation natively (fresher, predicted per frame).
        if (sendRotation) setHeadRotation(headMatrix, smoothYaw, smoothPitch);

        if (gameReady && vrActive && !robloxPoseActive) {
            float deltaYaw = smoothYaw - appliedYaw;
            if (deltaYaw > Math.PI) deltaYaw -= (float) (2.0 * Math.PI);
            if (deltaYaw < -Math.PI) deltaYaw += (float) (2.0 * Math.PI);
            float deltaPitch = smoothPitch - appliedPitch;
            appliedYaw = smoothYaw;
            appliedPitch = smoothPitch;
            View root = activity.getWindow().getDecorView();
            NativeInputInterface.nativePassMousePan(root.getWidth() * 0.5f,
                    root.getHeight() * 0.5f, -deltaYaw * 4.0f, -deltaPitch * 4.0f);
        }
        if (System.currentTimeMillis() - lastHeadLog > 2000) {
            lastHeadLog = System.currentTimeMillis();
            Log.i(TAG, "Head pose (" + (cardboardActive ? "cardboard sdk" : gyroHeadEnabled ? "gyro" : "android") + ") yaw="
                    + smoothYaw + " pitch=" + smoothPitch);
        }
    }

    // ---- Body jump: a sharp upward push of the head (phone accelerometer) presses Jump ----
    private final float[] gravity = new float[3];
    private long lastAccelNs, lastJumpNs;
    private boolean gravityReady;
    private int upSamples;
    /** Upward acceleration beyond gravity that counts as a jump take-off (m/s^2). */
    private static final float JUMP_ACCEL = 7f;

    private void detectJump(float[] a, long timestampNs) {
        if (!bodyJump || sceneMode != SCENE_GAME || controllerManager == null) return;
        float dt = lastAccelNs == 0 ? 0.005f : Math.min(0.1f, (timestampNs - lastAccelNs) * 1e-9f);
        lastAccelNs = timestampNs;
        if (!gravityReady) {
            System.arraycopy(a, 0, gravity, 0, 3);
            gravityReady = true;
            return;
        }
        // Gravity: ~0.5 s low-pass; the rest is the head's own linear acceleration.
        float k = dt / (0.5f + dt);
        for (int i = 0; i < 3; i++) gravity[i] += (a[i] - gravity[i]) * k;
        float g = (float) Math.sqrt(gravity[0] * gravity[0] + gravity[1] * gravity[1] + gravity[2] * gravity[2]);
        if (g < 1f) return;
        // Projection on "up" (the accelerometer reads +g upward at rest).
        float up = (a[0] * gravity[0] + a[1] * gravity[1] + a[2] * gravity[2]) / g - g;
        // A take-off lasts a few samples; one spike (a knock on the headset) does not count.
        upSamples = up > JUMP_ACCEL ? upSamples + 1 : 0;
        if (upSamples >= 4 && timestampNs - lastJumpNs > 800_000_000L) {
            lastJumpNs = timestampNs;
            upSamples = 0;
            Log.i(TAG, "Body jump detected (up " + up + " m/s^2)");
            final ControllerManager controllers = controllerManager;
            activity.runOnUiThread(controllers::jump);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    private final class Passthrough implements TextureView.SurfaceTextureListener {
        private TextureView preview;
        private CameraDevice camera;
        private CameraCaptureSession session;
        private SurfaceView gameSurface;
        private ImageReader reader;
        private HandlerThread cameraThread;
        private Handler cameraHandler;
        private boolean previewVisible = false;
        private boolean permissionRequested;
        private boolean opening;
        private long lastTrackingLog;
        private int trackedFrames;
        private long trackingNanos;
        private long detectNanos;
        private MediaPipeHandTracker handTracker;
        private boolean trackerFailed;
        private int sensorOrientation = 90;
        private long lastHandSeenMs, lastSearchMs;
        private android.util.Range<Integer> fpsRange;
        private ByteBuffer cameraBuffer = ByteBuffer.allocateDirect(320 * 240 * 4);

        void start() {
            if (camera != null || opening) return;
            if (arCore != null && arCore.isRunning()) return;
            if (activity.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                if (!permissionRequested) {
                    permissionRequested = true;
                    activity.requestPermissions(new String[]{Manifest.permission.CAMERA}, 4242);
                }
                mainHandler.postDelayed(() -> {
                    if (activity.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        permissionRequested = false;
                        if (running && resumed && needsCamera()) start();
                    }
                }, 1200);
                return;
            }
            View root = activity.getWindow().getDecorView().findViewById(android.R.id.content);
            if (!(root instanceof ViewGroup)) return;
            gameSurface = findSurface((ViewGroup) root);
            opening = true;
            open();
        }

        void setPreviewVisible(boolean visible) {
            previewVisible = visible;
            if (preview != null) preview.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        }

        private SurfaceView findSurface(ViewGroup parent) {
            for (int i = 0; i < parent.getChildCount(); i++) {
                View child = parent.getChildAt(i);
                if (child instanceof SurfaceView) return (SurfaceView) child;
                if (child instanceof ViewGroup) {
                    SurfaceView found = findSurface((ViewGroup) child);
                    if (found != null) return found;
                }
            }
            return null;
        }

        /** Releases only the camera2 device (ARCore takes the camera); hand tracking stays. */
        void stopCamera() {
            if (session != null) session.close();
            if (camera != null) camera.close();
            if (reader != null) reader.close();
            if (cameraThread != null) cameraThread.quitSafely();
            session = null;
            camera = null;
            opening = false;
            reader = null;
            cameraThread = null;
            cameraHandler = null;
        }

        void stop() {
            if (session != null) session.close();
            if (camera != null) camera.close();
            if (reader != null) reader.close();
            if (cameraThread != null) cameraThread.quitSafely();
            if (handTracker != null) handTracker.close();
            session = null;
            camera = null;
            opening = false;
            reader = null;
            cameraThread = null;
            cameraHandler = null;
            handTracker = null;
            setCameraBuffer(0, 0, null, false);
            if (preview != null && preview.getParent() instanceof ViewGroup)
                ((ViewGroup) preview.getParent()).removeView(preview);
            preview = null;
        }

        private void open() {
            try {
                CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
                if (manager == null || manager.getCameraIdList().length == 0) return;
                cameraThread = new HandlerThread("RobloxVR-Passthrough");
                cameraThread.start();
                cameraHandler = new Handler(cameraThread.getLooper());
                reader = ImageReader.newInstance(320, 240, ImageFormat.YUV_420_888, 2);
                reader.setOnImageAvailableListener(this::analyze, cameraHandler);
                String cameraId = manager.getCameraIdList()[0];
                for (String id : manager.getCameraIdList()) {
                    Integer facing = manager.getCameraCharacteristics(id)
                            .get(CameraCharacteristics.LENS_FACING);
                    if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                        cameraId = id;
                        break;
                    }
                }
                android.util.Range<Integer>[] ranges = manager.getCameraCharacteristics(cameraId)
                        .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
                fpsRange = null;
                if (ranges != null) {
                    for (android.util.Range<Integer> range : ranges) {
                        if (range.getUpper() > 30) continue;
                        if (fpsRange == null || range.getUpper() > fpsRange.getUpper()
                                || (range.getUpper().equals(fpsRange.getUpper()) && range.getLower() > fpsRange.getLower()))
                            fpsRange = range;
                    }
                }
                Log.i(TAG, "Hand camera fps range " + fpsRange);
                Integer orientation = manager.getCameraCharacteristics(cameraId)
                        .get(CameraCharacteristics.SENSOR_ORIENTATION);
                if (orientation != null) sensorOrientation = orientation;
                Log.i(TAG, "Hand camera " + cameraId + " sensor orientation " + sensorOrientation);
                manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                    public void onOpened(CameraDevice device) {
                        camera = device;
                        opening = false;
                        Log.i(TAG, "Camera hand tracking started: " + device.getId());
                        try {
                            final CaptureRequest.Builder request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                            // Hold ~30 fps: auto-exposure would halve the frame rate indoors,
                            // which makes the hands lag.
                            if (fpsRange != null) request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);
                            java.util.ArrayList<Surface> surfaces = new java.util.ArrayList<>();
                            if (reader != null) {
                                surfaces.add(reader.getSurface());
                                request.addTarget(reader.getSurface());
                            }
                            device.createCaptureSession(surfaces,
                                    new CameraCaptureSession.StateCallback() {
                                        public void onConfigured(CameraCaptureSession value) {
                                            session = value;
                                            try { value.setRepeatingRequest(request.build(), null, null); }
                                            catch (Exception error) { Log.w(TAG, "Camera preview failed", error); }
                                        }
                                        public void onConfigureFailed(CameraCaptureSession value) { }
                                    }, cameraHandler);
                        } catch (Exception error) { Log.w(TAG, "Camera session failed", error); }
                    }
                    public void onDisconnected(CameraDevice device) { opening = false; device.close(); }
                    public void onError(CameraDevice device, int error) { opening = false; device.close(); }
                }, cameraHandler);
            } catch (Exception error) { opening = false; Log.w(TAG, "Camera unavailable", error); }
        }

        private void analyze(ImageReader source) {
            Image image = source.acquireLatestImage();
            if (image == null) return;
            try {
                processImage(image);
            } finally {
                image.close();
            }
        }

        /** One camera frame (camera2 or ARCore): hand tracking plus the home passthrough. */
        synchronized void processImage(Image image) {
            try {
                // MediaPipe tracks 21 landmarks per hand; Joy-Con data never affects pose.
                if (cameraHands && controllerManager != null) {
                    if (handTracker == null && !trackerFailed) {
                        try {
                            handTracker = new MediaPipeHandTracker(activity);
                        } catch (Throwable error) {
                            trackerFailed = true;
                            Log.e(TAG, "MediaPipe initialization failed", error);
                        }
                    }
                    // With no hand in view, look for one ~5x per second instead of every frame:
                    // MediaPipe on the CPU (~60 ms/frame) otherwise heats the phone and starves
                    // Roblox's render thread, which is what made the menu lag after a while.
                    long nowMs = System.currentTimeMillis();
                    boolean searching = nowMs - lastHandSeenMs > 1500;
                    if (handTracker != null && searching && nowMs - lastSearchMs < 200) return;
                    lastSearchMs = nowMs;
                    if (handTracker != null) {
                        long started = System.nanoTime();
                        MediaPipeHandTracker.Result tracked = handTracker.track(image, imageRotation());
                        trackedFrames++;
                        trackingNanos += System.nanoTime() - started;
                        detectNanos += handTracker.lastDetectNanos;
                        MediaPipeHandTracker.Pose l = tracked.left;
                        MediaPipeHandTracker.Pose r = tracked.right;
                        if (l.active || r.active) lastHandSeenMs = nowMs;
                        final ControllerManager controllers = controllerManager;
                        activity.runOnUiThread(() -> controllers.updateCameraHands(
                                l.active ? l.x : -1f, l.y, l.angle, l.scale, l.active && l.pinching, l.active && l.fist,
                                r.active ? r.x : -1f, r.y, r.angle, r.scale, r.active && r.pinching, r.active && r.fist));
                        if (handSkeleton) setSkeleton(l.active ? l.screen : null, r.active ? r.screen : null);
                        if (System.currentTimeMillis() - lastTrackingLog > 2000) {
                            lastTrackingLog = System.currentTimeMillis();
                            Log.i(TAG, "MediaPipe hands left=" + l.active + " right=" + r.active
                                    + " fps=" + trackedFrames / 2 + " ms/frame="
                                    + (trackedFrames > 0 ? trackingNanos / trackedFrames / 1_000_000L : 0)
                                    + " detect=" + (trackedFrames > 0 ? detectNanos / trackedFrames / 1_000_000L : 0));
                            trackedFrames = 0;
                            trackingNanos = 0;
                            detectNanos = 0;
                        }
                    }
                }
            } catch (Throwable error) {
                if (System.currentTimeMillis() - lastTrackingLog > 2000) {
                    lastTrackingLog = System.currentTimeMillis();
                    Log.w(TAG, "Hand tracking frame failed", error);
                }
            }
        }

        /** Clockwise rotation that makes the rear-camera image upright on the current display. */
        int imageRotation() {
            int display = activity.getWindowManager().getDefaultDisplay().getRotation() * 90;
            return (sensorOrientation - display + 360) % 360;
        }

        public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) { }
        public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) { }
        public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) { stop(); return true; }
        public void onSurfaceTextureUpdated(SurfaceTexture texture) { }
    }
}
