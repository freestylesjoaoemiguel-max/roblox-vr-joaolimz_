package com.phonexr.roblox;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import com.roblox.engine.jni.NativeGLInterface;
import com.roblox.engine.jni.NativeInputInterface;

public final class ControllerManager implements InputManager.InputDeviceListener,
        View.OnGenericMotionListener, View.OnKeyListener {
    private static final String TAG = "RobloxVR-JoyCon";
    private static final int VIRTUAL_ID = 0x4a4f5943;

    private final Activity activity;
    private final InputManager input;
    private static ControllerManager active;
    private PhoneXRBridge bridge;

    private boolean connected;
    private boolean inputLogged;
    private boolean touchLayout = true;
    private boolean swapHands;
    /** Swap the walking stick and the camera/turn stick (independent of which hand is which). */
    private boolean swapSticks;
    /** Pinch = trigger, fist = grip in games; off: the hands only aim. */
    private boolean handGestures = true;
    private int gamepadType;

    // Optical camera-tracked hand positions in [0, 1] screen space
    private float leftRayX = 0.35f;
    private float leftRayY = 0.65f;
    private boolean leftRayActive = true;

    private float rightRayX = 0.65f;
    private float rightRayY = 0.65f;
    private boolean rightRayActive = true;

    private boolean handsVisible = true;
    private boolean raysEnabled = true;
    private boolean joyConEnabled = true;
    private float deadzone = 0.14f;
    private float turnSensitivity = 1.0f;
    private boolean snapTurning;
    private int snapDegrees = 30;
    private boolean snapLatched;

    // Joy-Con IMU pointing (Android 12+ exposes controller gyro via InputDevice sensors).
    private static final long MOTION_FRESH_MS = 400;
    private final SparseArray<MotionHand> motionHands = new SparseArray<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean joyConMotion = false;
    private float motionSensitivity = 1.0f;
    private int motionGrip;
    private boolean motionInvertPitch;
    private final long[] lastMotionMs = new long[2];
    private float leftAngle, rightAngle;
    private float leftScale = 1f, rightScale = 1f;

    private float leftStickX, leftStickY;
    private float rightStickX, rightStickY;
    private long lastRecenterPress = 0;

    ControllerManager(Activity activity) {
        this.activity = activity;
        this.input = (InputManager) activity.getSystemService(Context.INPUT_SERVICE);
        SharedPreferences prefs = activity.getSharedPreferences("roblox_vr", Context.MODE_PRIVATE);
        reloadPreferences();
    }

    void setBridge(PhoneXRBridge bridge) {
        this.bridge = bridge;
    }

    void start() {
        active = this;
        if (input != null) {
            input.registerInputDeviceListener(this, null);
            for (int id : input.getInputDeviceIds()) onInputDeviceAdded(id);
        }
        View root = activity.getWindow().getDecorView();
        root.setOnGenericMotionListener(this);
        root.setOnKeyListener(this);
        root.setFocusableInTouchMode(true);
        root.requestFocus();
        attach(root);
        updateRaysToCompositor();
    }

    private void attach(View view) {
        view.setOnGenericMotionListener(this);
        view.setOnKeyListener(this);
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) attach(group.getChildAt(i));
        }
    }

    public static boolean dispatchMotion(Activity activity, MotionEvent event) {
        if (active != null && active.activity == activity)
            return active.onGenericMotion(activity.getWindow().getDecorView(), event);
        return false;
    }

    public static boolean dispatchKey(Activity activity, KeyEvent event) {
        if (active != null && active.activity == activity)
            return active.onKey(activity.getWindow().getDecorView(), event.getKeyCode(), event);
        return false;
    }

    void reloadPreferences() {
        SharedPreferences prefs = activity.getSharedPreferences("roblox_vr", Context.MODE_PRIVATE);
        joyConEnabled = prefs.getBoolean("pref_joycon", true);
        deadzone = Math.max(0.02f, prefs.getInt("pref_deadzone", 14) / 100f);
        turnSensitivity = Math.max(0.2f, prefs.getInt("pref_turn_sensitivity", 100) / 100f);
        snapTurning = prefs.getBoolean("pref_snap_turn", false);
        snapDegrees = Math.max(15, prefs.getInt("pref_snap_degrees", 30));
        raysEnabled = prefs.getBoolean("pref_rays", true);
        swapSticks = prefs.getBoolean("pref_swap_sticks", false);
        handGestures = prefs.getBoolean("pref_no_controller", false); // experimental: no controller, hands click and walk
        // Joy-Cons are buttons by default; hands come from the camera.
        boolean motion = joyConEnabled && prefs.getInt("controller_kind", 0) == 0 && prefs.getBoolean("pref_nintendo_motion", true);
        motionSensitivity = Math.max(0.2f, prefs.getInt("pref_joycon_motion_sens", 100) / 100f);
        motionGrip = prefs.getInt("pref_joycon_grip", 0);
        motionInvertPitch = prefs.getBoolean("pref_joycon_invert_pitch", false);
        if (motion != joyConMotion) {
            joyConMotion = motion;
            if (active == this) rescanMotion();
        }
    }

    void pause() {
        unregisterAllMotion();
    }

    void resume() {
        if (active == this) rescanMotion();
    }

    void stop() {
        if (input != null) input.unregisterInputDeviceListener(this);
        unregisterAllMotion();
        View root = activity.getWindow().getDecorView();
        root.setOnGenericMotionListener(null);
        root.setOnKeyListener(null);
        if (connected) NativeInputInterface.nativeGamepadDisconnectEvent(VIRTUAL_ID);
        connected = false;
        if (active == this) active = null;
    }

    private static boolean controller(InputDevice device) {
        if (device == null || device.isVirtual()) return false;
        // Fingerprint readers and other uinput helpers advertise joystick sources.
        if (device.getName().toLowerCase(java.util.Locale.US).startsWith("uinput")) return false;
        int sources = device.getSources();
        return (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    private void ensureConnected() {
        if (connected) return;
        NativeInputInterface.nativeGamepadConnectEventWithGamepadType(VIRTUAL_ID, gamepadType);
        int[] keys = {
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
                KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
                KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
                KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
                KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR,
                KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT,
                KeyEvent.KEYCODE_BUTTON_MODE,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT
        };
        for (int key : keys)
            NativeInputInterface.nativeSetGamepadSupportedKeyWithGamepadType(VIRTUAL_ID, key, true, gamepadType);
        NativeInputInterface.nativeSetGamepadSupportedMotionWithGamepadType(
                VIRTUAL_ID, MotionEvent.AXIS_X, MotionEvent.AXIS_Y, true, gamepadType);
        NativeInputInterface.nativeSetGamepadSupportedMotionWithGamepadType(
                VIRTUAL_ID, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ, true, gamepadType);
        connected = true;
    }

    /**
     * The Roblox app shell only redraws on input, so in the home menu it fell to ~1 fps
     * while the head kept moving. A neutral stick sample (current values, no motion)
     * counts as input and keeps the menu rendering.
     */
    private boolean keepRenderingFlip;

    /** Jump (A on Touch / gamepad) from a body jump; a short real press and release. */
    void jump() {
        try {
            ensureConnected();
            NativeInputInterface.nativeGamepadButtonEvent(VIRTUAL_ID, KeyEvent.KEYCODE_BUTTON_A, 1);
            mainHandler.postDelayed(() -> {
                try { NativeInputInterface.nativeGamepadButtonEvent(VIRTUAL_ID, KeyEvent.KEYCODE_BUTTON_A, 0); }
                catch (Throwable ignored) { }
            }, 150);
        } catch (Throwable ignored) { }
    }

    void keepRendering() {
        try {
            // A 1 px mouse wiggle in the top-left corner, where the home UI has nothing to hover.
            keepRenderingFlip = !keepRenderingFlip;
            NativeInputInterface.nativePassMouseMove(keepRenderingFlip ? 2f : 1f, 1f,
                    keepRenderingFlip ? 1f : -1f, 0f);
        } catch (Throwable ignored) { }
    }

    void setTouchLayout(boolean enabled) {
        touchLayout = enabled;
    }

    void setControllerProfile(int type, boolean swap) {
        boolean typeChanged = gamepadType != type;
        gamepadType = type;
        boolean swapChanged = swapHands != swap;
        swapHands = swap;
        if (swapChanged && active == this) rescanMotion();
        if (typeChanged && connected) {
            NativeInputInterface.nativeGamepadDisconnectEvent(VIRTUAL_ID);
            connected = false;
            ensureConnected();
        }
    }

    void setHandsVisible(boolean visible) {
        this.handsVisible = visible;
        updateRaysToCompositor();
    }

    void setRaysEnabled(boolean enabled) {
        this.raysEnabled = enabled;
        updateRaysToCompositor();
    }

    // Hands are tracked via phone camera:
    void updateCameraHands(float leftX, float leftY, float leftAngle, float leftScale,
                           boolean leftPinchNow, boolean leftFist,
                           float rightX, float rightY, float rightAngle, float rightScale,
                           boolean rightPinchNow, boolean rightFist) {
        long now = System.nanoTime();
        // A Joy-Con that is moving owns its hand; its buttons click, not the camera hand.
        leftPinch = leftPinchNow && !motionFresh(0);
        rightPinch = rightPinchNow && !motionFresh(1);
        // Hand gestures: pinch = trigger (click), fist = grip (Oculus Touch), in the Roblox home and in games.
        int scene = bridge != null ? bridge.getSceneMode() : PhoneXRBridge.SCENE_LOADING;
        boolean inGame = handGestures && scene == PhoneXRBridge.SCENE_GAME && !pointerUiActive();
        boolean inMenu = handGestures && scene == PhoneXRBridge.SCENE_HOME && !pointerUiActive();
        // Game: pinch = trigger held. Menu: tap = click, pinch-hold + move down/up = scroll.
        menuPinch(0, inMenu && leftPinch, leftY);
        menuPinch(1, inMenu && rightPinch, rightY);
        setGesture(0, KeyEvent.KEYCODE_BUTTON_L2, (inGame && leftPinch) || clickPulse[0]);
        setGesture(1, KeyEvent.KEYCODE_BUTTON_R2, (inGame && rightPinch) || clickPulse[1] || screenDown);
        // Experimental hand mode: a closed fist (either hand) walks forward.
        setFistWalk(handGestures && inGame && (leftFist || rightFist));
        if (!motionFresh(0)) {
            if (leftX >= 0f) {
                leftSeen = now;
                leftRayX = leftFilterX.filter(leftX, now);
                leftRayY = leftFilterY.filter(leftY, now);
                this.leftAngle = leftAngle;
                this.leftScale = leftScaleFilter.filter(leftScale, now);
            }
            // A hand missed for a frame or two keeps its place instead of blinking out.
            leftRayActive = now - leftSeen < HAND_HOLD_NS;
            if (!leftRayActive) { leftFilterX.reset(); leftFilterY.reset(); leftScaleFilter.reset(); }
        }
        if (!motionFresh(1)) {
            if (rightX >= 0f) {
                rightSeen = now;
                rightRayX = rightFilterX.filter(rightX, now);
                rightRayY = rightFilterY.filter(rightY, now);
                this.rightAngle = rightAngle;
                this.rightScale = rightScaleFilter.filter(rightScale, now);
            }
            rightRayActive = now - rightSeen < HAND_HOLD_NS;
            if (!rightRayActive) { rightFilterX.reset(); rightFilterY.reset(); rightScaleFilter.reset(); }
        }
        publishHands();
    }

    private static final long HAND_HOLD_NS = 300_000_000L;

    /** Camera-hand pinch (PhoneXR HandGestures.PinchLatch): the hand's click. */
    private boolean leftPinch, rightPinch;
    /** L2, R2, L1, R1 currently held by a hand gesture. */
    private final boolean[] gestureHeld = new boolean[4];

    private void setGesture(int slot, int key, boolean down) {
        if (gestureHeld[slot] == down) return;
        gestureHeld[slot] = down;
        try {
            ensureConnected();
            NativeInputInterface.nativeGamepadButtonEvent(VIRTUAL_ID, key, down ? 1 : 0);
            if (key == KeyEvent.KEYCODE_BUTTON_L2)
                NativeInputInterface.nativeGamepadAxisEvent(VIRTUAL_ID, 0x0f, 0f, 0f, down ? 1f : 0f);
            else if (key == KeyEvent.KEYCODE_BUTTON_R2)
                NativeInputInterface.nativeGamepadAxisEvent(VIRTUAL_ID, 0x10, 0f, 0f, down ? -1f : 0f);
        } catch (Throwable ignored) { }
    }

    private boolean leftTrigger, rightTrigger;
    private long lastStickLog;
    private long leftSeen, rightSeen;
    private final OneEuro leftFilterX = new OneEuro(1.2f, 6f), leftFilterY = new OneEuro(1.2f, 6f);
    private final OneEuro rightFilterX = new OneEuro(1.2f, 6f), rightFilterY = new OneEuro(1.2f, 6f);
    private final OneEuro leftScaleFilter = new OneEuro(0.8f, 1f), rightScaleFilter = new OneEuro(0.8f, 1f);

    /** One-Euro filter: steady when the hand rests, almost no lag when it moves fast. */
    private static final class OneEuro {
        private final float minCutoff, beta;
        private float value, derivative;
        private long time;
        private boolean ready;

        OneEuro(float minCutoff, float beta) {
            this.minCutoff = minCutoff;
            this.beta = beta;
        }

        void reset() { ready = false; }

        float filter(float x, long now) {
            if (!ready) {
                ready = true;
                value = x;
                derivative = 0f;
                time = now;
                return x;
            }
            float dt = Math.max(1e-3f, (now - time) * 1e-9f);
            time = now;
            derivative += alpha(dt, 1f) * ((x - value) / dt - derivative);
            value += alpha(dt, minCutoff + beta * Math.abs(derivative)) * (x - value);
            return value;
        }

        private static float alpha(float dt, float cutoff) {
            return 1f / (1f + 1f / (2f * (float) Math.PI * cutoff * dt));
        }
    }

    private boolean gaze;

    /** Touching the screen (the Cardboard button) clicks like a pinch. */
    private volatile boolean screenDown;

    private static final float SCROLL_START = 0.04f, SCROLL_GAIN = 12f;
    private final boolean[] clickPulse = new boolean[2];
    private final boolean[] mPinch = new boolean[2], mScroll = new boolean[2];
    private final float[] mStartY = new float[2];
    private final android.os.Handler clickHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    /** Menu hand: a quick pinch clicks; pinch-and-hold then moving the hand scrolls like a thumbstick. */
    private void menuPinch(int hand, boolean pinch, float y) {
        if (pinch) {
            if (!mPinch[hand]) { mPinch[hand] = true; mScroll[hand] = false; mStartY[hand] = y; }
            float dy = y - mStartY[hand];
            if (!mScroll[hand] && Math.abs(dy) > SCROLL_START) mScroll[hand] = true;
            if (mScroll[hand]) sendScroll(-Math.max(-1f, Math.min(1f, dy * SCROLL_GAIN))); // hand down = stick down
        } else if (mPinch[hand]) {
            mPinch[hand] = false;
            if (mScroll[hand]) {
                sendScroll(0f);
            } else {
                final int slot = hand, key = hand == 0 ? KeyEvent.KEYCODE_BUTTON_L2 : KeyEvent.KEYCODE_BUTTON_R2;
                clickPulse[slot] = true;
                setGesture(slot, key, true);
                clickHandler.postDelayed(() -> { clickPulse[slot] = false; setGesture(slot, key, screenDown && slot == 1); }, 90);
            }
            mScroll[hand] = false;
        }
    }

    private boolean fistWalk;
    private float arWalkX, arWalkY, sentWalkX, sentWalkY;

    private void setFistWalk(boolean on) {
        if (fistWalk == on) return;
        fistWalk = on;
        applyWalk();
    }

    /** Real-world steps from ARCore (stick x, y). */
    void setBodyWalk(float x, float y) {
        arWalkX = x;
        arWalkY = y;
        applyWalk();
    }

    private void applyWalk() {
        boolean game = bridge != null && bridge.getSceneMode() == PhoneXRBridge.SCENE_GAME;
        float x = game ? arWalkX : 0f;
        float y = game ? Math.max(-1f, Math.min(1f, arWalkY + (fistWalk ? 1f : 0f))) : 0f;
        if (Math.abs(leftStickX) > 0.05f || Math.abs(leftStickY) > 0.05f) return; // a real pad owns the stick
        if (Math.abs(x - sentWalkX) < 0.04f && Math.abs(y - sentWalkY) < 0.04f && !((x == 0f && y == 0f) && (sentWalkX != 0f || sentWalkY != 0f))) return;
        sentWalkX = x;
        sentWalkY = y;
        try {
            ensureConnected();
            NativeInputInterface.nativeGamepadAxisEvent(VIRTUAL_ID, 0, x, y, 0f);
        } catch (Throwable ignored) { }
    }

    private void sendScroll(float v) {
        try { ensureConnected(); NativeInputInterface.nativeGamepadAxisEvent(VIRTUAL_ID, 1, 0f, v, 0f); } catch (Throwable ignored) { }
    }

    private volatile boolean screenA;

    /** Cardboard button: in the Roblox home menu a tap is the A button, in a game it is the trigger. */
    void screenClick(boolean down) {
        boolean home = bridge != null && bridge.getSceneMode() == PhoneXRBridge.SCENE_HOME;
        if (down) {
            if (home) {
                screenA = true;
                try {
                    ensureConnected();
                    NativeInputInterface.nativeGamepadButtonEvent(VIRTUAL_ID, KeyEvent.KEYCODE_BUTTON_A, 1);
                } catch (Throwable ignored) { }
            } else {
                screenDown = true;
                setGesture(1, KeyEvent.KEYCODE_BUTTON_R2, true);
            }
        } else {
            if (screenA) {
                screenA = false;
                try { NativeInputInterface.nativeGamepadButtonEvent(VIRTUAL_ID, KeyEvent.KEYCODE_BUTTON_A, 0); }
                catch (Throwable ignored) { }
            }
            if (screenDown) {
                screenDown = false;
                setGesture(1, KeyEvent.KEYCODE_BUTTON_R2, false);
            }
        }
    }

    /** True while no hand is tracked and the pointer follows the view direction. */
    private int gazeTick;

    /** Called every UI tick: with no hand tracked (menu / home) the pointer follows the view direction. */
    void tickGaze() {
        long now = System.nanoTime();
        boolean scene = bridge != null && bridge.getSceneMode() != PhoneXRBridge.SCENE_GAME
                && bridge.getSceneMode() != PhoneXRBridge.SCENE_LOADING;
        boolean none = scene && now - leftSeen >= HAND_HOLD_NS && now - rightSeen >= HAND_HOLD_NS
                && !motionFresh(0) && !motionFresh(1);
        if (none) {
            boolean first = !gaze;
            if (first) {
                gaze = true;
                Log.i(TAG, "gaze pointer on");
                try { PhoneXRBridge.setGazeMode(true); } catch (Throwable ignored) { }
            }
            rightRayX = 0.5f;
            rightRayY = 0.5f;
            rightRayActive = true;
            leftRayActive = false;
            if (first || ++gazeTick % 6 == 0) publishHands();
        } else if (gaze) {
            gaze = false;
            try { PhoneXRBridge.setGazeMode(false); } catch (Throwable ignored) { }
            rightRayActive = false;
            publishHands();
        }
    }

    boolean isGaze() {
        return gaze;
    }

    private void publishHands() {
        updateRaysToCompositor();
        PhoneXRBridge.setHands(leftRayX, leftRayY, leftAngle, leftScale, leftRayActive,
                rightRayX, rightRayY, rightAngle, rightScale, rightRayActive, handsVisible);

        VRUIManager ui = bridge != null ? bridge.getVrUiManager() : null;
        if (ui != null && ui.isPointerUiActive()) {
            ui.onRay(rightRayX, rightRayY, rightTrigger, rightRayActive, 1, bridge);
            ui.onRay(leftRayX, leftRayY, leftTrigger, leftRayActive, 0, bridge);
        }
    }

    private boolean motionFresh(int side) {
        return joyConMotion && System.currentTimeMillis() - lastMotionMs[side] < MOTION_FRESH_MS;
    }

    // ---- Joy-Con IMU hand pointing ----

    private final class MotionHand implements SensorEventListener {
        final int deviceId;
        final int side;
        final String name;
        final SensorManager sensorManager;
        final Sensor gyro;
        final Sensor accel;
        final float[] up = new float[3];
        boolean haveUp;
        long lastTimestamp;
        float yaw, pitch, pitchOrigin, roll;
        boolean pitchReady;
        boolean needsRecenter = true;

        MotionHand(int deviceId, int side, String name, SensorManager sensorManager, Sensor gyro, Sensor accel) {
            this.deviceId = deviceId;
            this.side = side;
            this.name = name;
            this.sensorManager = sensorManager;
            this.gyro = gyro;
            this.accel = accel;
        }

        void register() {
            sensorManager.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME, mainHandler);
            if (accel != null)
                sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME, mainHandler);
        }

        void unregister() {
            sensorManager.unregisterListener(this);
        }

        void recenter() {
            yaw = 0f;
            pitchOrigin = pitch;
            needsRecenter = !pitchReady;
        }

        @Override
        public void onSensorChanged(SensorEvent event) {
            if (!joyConMotion) return;
            if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                for (int i = 0; i < 3; i++)
                    up[i] = haveUp ? up[i] + (event.values[i] - up[i]) * 0.08f : event.values[i];
                haveUp = true;
                return;
            }
            long timestamp = event.timestamp;
            if (lastTimestamp == 0 || !haveUp) {
                lastTimestamp = timestamp;
                return;
            }
            float dt = (timestamp - lastTimestamp) * 1e-9f;
            lastTimestamp = timestamp;
            if (dt <= 0f || dt > 0.1f) return;

            float un = (float) Math.sqrt(up[0] * up[0] + up[1] * up[1] + up[2] * up[2]);
            if (un < 1f) return;
            float ux = up[0] / un, uy = up[1] / un, uz = up[2] / un;
            float wx = event.values[0], wy = event.values[1], wz = event.values[2];

            // Pointing axis of the controller in its own sensor frame.
            float fx = 0f, fy = 1f, fz = 0f;
            if (motionGrip == 1) { fx = 1f; fy = 0f; }
            else if (motionGrip == 2) { fy = 0f; fz = -1f; }

            // Yaw: rotation about gravity, independent of how the controller is held.
            float yawRate = -(wx * ux + wy * uy + wz * uz);
            // Pitch: rotation that tilts the pointing axis toward "up" (axis = f x up).
            float px = fy * uz - fz * uy, py = fz * ux - fx * uz, pz = fx * uy - fy * ux;
            float pn = (float) Math.sqrt(px * px + py * py + pz * pz);
            float pitchRate = pn > 0.2f ? (wx * px + wy * py + wz * pz) / pn : 0f;
            // Roll around the pointing axis, used only to rotate the drawn hand.
            float rollRate = wx * fx + wy * fy + wz * fz;
            if (Math.abs(yawRate) < 0.01f) yawRate = 0f;
            if (Math.abs(pitchRate) < 0.01f) pitchRate = 0f;

            yaw += yawRate * dt;
            float tiltPitch = (float) Math.asin(Math.max(-1f, Math.min(1f, fx * ux + fy * uy + fz * uz)));
            if (!pitchReady || pn <= 0.2f) {
                pitch = tiltPitch;
                pitchReady = true;
            } else {
                pitch = 0.97f * (pitch + pitchRate * dt) + 0.03f * tiltPitch;
            }
            roll = (roll + rollRate * dt) * 0.995f;
            if (needsRecenter) {
                pitchOrigin = pitch - (bridge != null ? bridge.getHeadPitch() : 0f);
                needsRecenter = false;
            }
            onMotion(this);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) { }
    }

    private void onMotion(MotionHand hand) {
        float headYaw = bridge != null ? bridge.getHeadYaw() : 0f;
        float headPitch = bridge != null ? bridge.getHeadPitch() : 0f;
        float relPitch = hand.pitch - hand.pitchOrigin;
        if (motionInvertPitch) relPitch = -relPitch;
        float relYaw = hand.yaw - headYaw;
        relPitch -= headPitch;

        float gain = motionSensitivity;
        float offset = hand.side == 0 ? -0.06f : 0.06f;
        float x = clamp01(0.5f + offset + relYaw * 0.75f * gain);
        float y = clamp01(0.55f - relPitch * 0.95f * gain);

        lastMotionMs[hand.side] = System.currentTimeMillis();
        if (hand.side == 0) {
            leftRayX = leftRayX * 0.3f + x * 0.7f;
            leftRayY = leftRayY * 0.3f + y * 0.7f;
            leftRayActive = true;
            leftAngle = -hand.roll;
            leftScale = 1f;
        } else {
            rightRayX = rightRayX * 0.3f + x * 0.7f;
            rightRayY = rightRayY * 0.3f + y * 0.7f;
            rightRayActive = true;
            rightAngle = -hand.roll;
            rightScale = 1f;
        }
        publishHands();
    }

    private static float clamp01(float value) {
        return Math.max(0.02f, Math.min(0.98f, value));
    }

    void recenterMotion() {
        for (int i = 0; i < motionHands.size(); i++) motionHands.valueAt(i).recenter();
    }

    String motionStatus() {
        if (Build.VERSION.SDK_INT < 31) return "Joy-Con motion needs Android 12+";
        if (motionHands.size() == 0) {
            boolean anyController = false;
            if (input != null)
                for (int id : input.getInputDeviceIds())
                    if (controller(input.getInputDevice(id))) { anyController = true; break; }
            return anyController ? "Controller connected, but its gyro is not exposed by Android"
                    : "No Joy-Con connected";
        }
        StringBuilder text = new StringBuilder("Motion: ");
        for (int i = 0; i < motionHands.size(); i++) {
            MotionHand hand = motionHands.valueAt(i);
            if (i > 0) text.append(", ");
            text.append(hand.side == 0 ? "L " : "R ").append(hand.name);
        }
        return text.toString();
    }

    private int handSide(InputDevice device) {
        String name = device.getName().toLowerCase(java.util.Locale.US);
        int productId = device.getProductId();
        boolean left = name.contains("(l)") || name.contains("left") || productId == 0x2006;
        int side = left ? 0 : 1;
        return swapHands ? 1 - side : side;
    }

    private void addMotion(int deviceId) {
        if (!joyConMotion || Build.VERSION.SDK_INT < 31 || input == null) return;
        if (motionHands.get(deviceId) != null) return;
        InputDevice device = input.getInputDevice(deviceId);
        if (device == null) return;
        // Only Nintendo pads aim the hands; Xbox / PlayStation pads are plain gamepads (hands rest).
        String devName = device.getName().toLowerCase(java.util.Locale.US);
        if (device.getVendorId() != 0x057e && !devName.contains("joy-con") && !devName.contains("pro controller")) return;
        try {
            SensorManager sensorManager = device.getSensorManager();
            Sensor gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
            if (gyro == null) return;
            Sensor accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            int side = handSide(device);
            for (int i = 0; i < motionHands.size(); i++)
                if (motionHands.valueAt(i).side == side) return; // one controller per hand
            MotionHand hand = new MotionHand(deviceId, side, device.getName(), sensorManager, gyro, accel);
            hand.yaw = bridge != null ? bridge.getHeadYaw() : 0f; // start pointing where the head looks
            hand.register();
            motionHands.put(deviceId, hand);
            Log.i(TAG, "Joy-Con motion tracking on " + (side == 0 ? "left" : "right")
                    + " hand: " + device.getName() + (accel == null ? " (gyro only)" : ""));
        } catch (Throwable error) {
            Log.w(TAG, "Controller sensors unavailable: " + device.getName(), error);
        }
    }

    private void removeMotion(int deviceId) {
        MotionHand hand = motionHands.get(deviceId);
        if (hand == null) return;
        hand.unregister();
        motionHands.remove(deviceId);
    }

    private void unregisterAllMotion() {
        for (int i = 0; i < motionHands.size(); i++) motionHands.valueAt(i).unregister();
        motionHands.clear();
    }

    private void rescanMotion() {
        unregisterAllMotion();
        if (input == null) return;
        for (int id : input.getInputDeviceIds())
            if (controller(input.getInputDevice(id)) || hasGyro(id)) addMotion(id);
    }

    private boolean hasGyro(int deviceId) {
        if (Build.VERSION.SDK_INT < 31) return false;
        InputDevice device = input.getInputDevice(deviceId);
        if (device == null) return false;
        String name = device.getName().toLowerCase(java.util.Locale.US);
        if (!name.contains("joy-con") && !name.contains("pro controller") && device.getVendorId() != 0x057e)
            return false;
        try {
            return device.getSensorManager().getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private float axis(MotionEvent event, int primary, int fallback) {
        float value = event.getAxisValue(primary);
        if (Math.abs(value) < 0.001f && fallback >= 0) value = event.getAxisValue(fallback);
        float magnitude = Math.abs(value);
        if (magnitude <= deadzone) return 0f;
        return Math.copySign((magnitude - deadzone) / (1f - deadzone), value);
    }

    @Override
    public boolean onGenericMotion(View view, MotionEvent event) {
        if (!joyConEnabled || event.getAction() != MotionEvent.ACTION_MOVE || !controller(event.getDevice())) return false;
        if (!inputLogged) {
            inputLogged = true;
            Log.i(TAG, "Joy-Con input active: " + event.getDevice().getName());
        }
        ensureConnected();

        String name = event.getDevice().getName().toLowerCase(java.util.Locale.US);
        int vendorId = event.getDevice().getVendorId();
        int productId = event.getDevice().getProductId();

        // Treat only the two detachable Joy-Cons as single-stick devices.
        // Switch Pro, Xbox, PlayStation and generic HID pads stay dual-stick gamepads.
        boolean isJoyCon = name.contains("joy-con")
                || (vendorId == 0x057e && (productId == 0x2006 || productId == 0x2007));
        boolean isRightJoyCon = (name.contains("(r)") || name.contains("right") || productId == 0x2007);
        if (swapHands) isRightJoyCon = !isRightJoyCon;

        float x = axis(event, MotionEvent.AXIS_X, MotionEvent.AXIS_HAT_X);
        float y = axis(event, MotionEvent.AXIS_Y, MotionEvent.AXIS_HAT_Y);
        float rx = axis(event, MotionEvent.AXIS_Z, MotionEvent.AXIS_RX);
        float ry = axis(event, MotionEvent.AXIS_RZ, MotionEvent.AXIS_RY);

        if (isJoyCon) {
            if (isRightJoyCon) {
                float stickX = Math.abs(rx) + Math.abs(ry) > 0.001f ? rx : x;
                float stickY = Math.abs(rx) + Math.abs(ry) > 0.001f ? ry : y;
                rightStickX = stickX;
                rightStickY = stickY;
            } else {
                leftStickX = x;
                leftStickY = y;
            }
        } else {
            leftStickX = swapHands ? rx : x;
            leftStickY = swapHands ? ry : y;
            rightStickX = swapHands ? x : rx;
            rightStickY = swapHands ? y : ry;
        }

        if (swapSticks) {
            float tx = leftStickX, ty = leftStickY;
            leftStickX = rightStickX; leftStickY = rightStickY;
            rightStickX = tx; rightStickY = ty;
        }

        // Snap turning is handled here; smooth turning stays a normal Roblox gamepad axis.
        if (snapTurning && bridge != null) {
            if (Math.abs(rightStickX) > 0.72f && !snapLatched) {
                bridge.turnYaw((float) Math.toRadians(-Math.copySign(snapDegrees, rightStickX)));
                snapLatched = true;
            } else if (Math.abs(rightStickX) < 0.35f) {
                snapLatched = false;
            }
        }

        if (System.currentTimeMillis() - lastStickLog > 2000
                && Math.abs(leftStickX) + Math.abs(leftStickY) + Math.abs(rightStickX) + Math.abs(rightStickY) > 0.2f) {
            lastStickLog = System.currentTimeMillis();
            Log.i(TAG, "Sticks " + event.getDevice().getName() + " L=" + leftStickX + "," + leftStickY
                    + " R=" + rightStickX + "," + rightStickY + " panel=" + pointerUiActive()
                    + " scene=" + (bridge != null ? bridge.getSceneMode() : -1));
        }
        float lt = Math.max(axis(event, MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE), 0f);
        float rt = Math.max(axis(event, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS), 0f);

        // Hysteresis so a trigger resting near the threshold does not type twice.
        rightTrigger = rightTrigger ? rt > 0.35f : rt > 0.6f;
        leftTrigger = leftTrigger ? lt > 0.35f : lt > 0.6f;
        if (pointerUiActive()) {
            // Our panel (keyboard, settings) has the controllers: nothing reaches
            // Roblox underneath, so a click never also hits the Roblox UI behind it.
            bridge.getVrUiManager().onRay(rightRayX, rightRayY, rightTrigger, rightRayActive, 1, bridge);
            bridge.getVrUiManager().onRay(leftRayX, leftRayY, leftTrigger, leftRayActive, 0, bridge);
            return true;
        }

        // Movement strictly uses left stick (NEVER moves hands):
        NativeInputInterface.nativeGamepadAxisEvent(VIRTUAL_ID, 0, leftStickX, -leftStickY, 0f);
        float turnX = snapTurning ? 0f : rightStickX * turnSensitivity;
        NativeInputInterface.nativeGamepadAxisEvent(VIRTUAL_ID, 1,
                Math.max(-1f, Math.min(1f, turnX)), -rightStickY, 0f);

        // Right stick rotates the Roblox camera directly and never feeds the UI pointer.
        if (!snapTurning && bridge != null
                && (Math.abs(rightStickX) > 0.001f || Math.abs(rightStickY) > 0.001f)) {
            View root = activity.getWindow().getDecorView();
            NativeInputInterface.nativePassMousePan(root.getWidth() * 0.5f,
                    root.getHeight() * 0.5f,
                    -rightStickX * turnSensitivity * 0.075f,
                    rightStickY * turnSensitivity * 0.060f);
        }
        NativeInputInterface.nativeGamepadAxisEvent(VIRTUAL_ID, 0x0f, 0f, 0f, lt);
        NativeInputInterface.nativeGamepadAxisEvent(VIRTUAL_ID, 0x10, 0f, 0f, -rt);

        // Forward left stick to Roblox VR player walk
        NativeGLInterface.setVirtualVRJoyStickPosition(leftStickX, -leftStickY);

        return true;
    }

    private void updateRaysToCompositor() {
        boolean ui = pointerUiActive();
        PhoneXRBridge.setRays(leftRayX, leftRayY, ui && raysEnabled && leftRayActive,
                rightRayX, rightRayY, ui && raysEnabled && rightRayActive, handsVisible);
    }

    void refreshUiMode() {
        updateRaysToCompositor();
    }

    private boolean pointerUiActive() {
        return bridge != null && bridge.getVrUiManager() != null
                && bridge.getVrUiManager().isPointerUiActive();
    }

    @Override
    public boolean onKey(View view, int keyCode, KeyEvent event) {
        if (!joyConEnabled || !controller(event.getDevice())
                || (!KeyEvent.isGamepadButton(keyCode) && !isDpad(keyCode))) return false;
        ensureConnected();

        boolean isDown = event.getAction() == KeyEvent.ACTION_DOWN;
        int physicalKey = remapButton(swapHands ? swappedKey(normalizeButton(keyCode))
                : normalizeButton(keyCode));
        int mappedKey = touchLayout ? touchButton(physicalKey) : physicalKey;

        // D-pad has VR-global actions and is still forwarded to Roblox (in the Touch
        // layout the left Joy-Con arrows are the X/Y buttons instead).
        if (!touchLayout && isDown && bridge != null) {
            if (mappedKey == KeyEvent.KEYCODE_DPAD_UP) bridge.recenter();
            else if (mappedKey == KeyEvent.KEYCODE_DPAD_DOWN) bridge.toggleHands();
            else if (mappedKey == KeyEvent.KEYCODE_DPAD_LEFT)
                bridge.turnYaw((float)Math.toRadians(30));
            else if (mappedKey == KeyEvent.KEYCODE_DPAD_RIGHT)
                bridge.turnYaw((float)Math.toRadians(-30));
        }

        // Joy-Con Trigger / Click actions
        boolean isRightTrigger = mappedKey == KeyEvent.KEYCODE_BUTTON_R2
                || mappedKey == KeyEvent.KEYCODE_BUTTON_R1;
        boolean isLeftTrigger = mappedKey == KeyEvent.KEYCODE_BUTTON_L2
                || mappedKey == KeyEvent.KEYCODE_BUTTON_L1;
        boolean isA = mappedKey == KeyEvent.KEYCODE_BUTTON_A;

        if (pointerUiActive()) {
            // Key repeats keep isDown true; VRUIManager types only on the press edge.
            if (isRightTrigger || isA) {
                rightTrigger = isDown;
                bridge.getVrUiManager().onRay(rightRayX, rightRayY, isDown, rightRayActive, 1, bridge);
            }
            if (isLeftTrigger) {
                leftTrigger = isDown;
                bridge.getVrUiManager().onRay(leftRayX, leftRayY, isDown, leftRayActive, 0, bridge);
            }
            // Only the system buttons (Minus, Plus, Home) still work while our panel is open.
            if (physicalKey != KeyEvent.KEYCODE_BUTTON_SELECT && physicalKey != KeyEvent.KEYCODE_BUTTON_START
                    && physicalKey != KeyEvent.KEYCODE_BUTTON_MODE) return true;
        }

        // Minus: short press = Menu for Roblox, hold = VR settings panel.
        if (physicalKey == KeyEvent.KEYCODE_BUTTON_SELECT) {
            if (isDown) {
                if (event.getRepeatCount() == 0) {
                    settingsOpenedByHold = false;
                    mainHandler.removeCallbacks(openSettingsHold);
                    mainHandler.postDelayed(openSettingsHold, 700);
                }
            } else {
                mainHandler.removeCallbacks(openSettingsHold);
                if (!settingsOpenedByHold) {
                    NativeInputInterface.nativeGamepadButtonEvent(VIRTUAL_ID, mappedKey, 1);
                    NativeInputInterface.nativeGamepadButtonEvent(VIRTUAL_ID, mappedKey, 0);
                }
            }
            return true;
        }

        // Recenter shortcut: hold Plus (+) or Home.
        boolean recenterKey = physicalKey == KeyEvent.KEYCODE_BUTTON_START
                || physicalKey == KeyEvent.KEYCODE_BUTTON_MODE;
        if (recenterKey) {
            if (isDown) {
                if (event.getRepeatCount() == 0) lastRecenterPress = System.currentTimeMillis();
            } else if (System.currentTimeMillis() - lastRecenterPress > 300) {
                if (bridge != null) bridge.recenter();
            }
            // Like the Oculus button on Touch, Plus/Home belong to the system, not the game.
            if (touchLayout) return true;
        }

        // Send button event to Roblox (A = jump, B = back, etc.)
        NativeInputInterface.nativeGamepadButtonEvent(VIRTUAL_ID, mappedKey, isDown ? 1 : 0);

        return true;
    }

    /**
     * Oculus Touch layout (what Roblox VR expects): right hand A (lower) / B (upper),
     * left hand X (lower) / Y (upper), trigger = R2/L2, grip = R1/L1, stick click =
     * R3/L3, Menu = Start. Joy-Con face buttons come from the right controller and the
     * arrows from the left one; they are matched by position.
     */
    private boolean settingsOpenedByHold;
    private final Runnable openSettingsHold = () -> {
        settingsOpenedByHold = true;
        if (bridge != null && bridge.getVrUiManager() != null) bridge.getVrUiManager().toggleSettings();
        refreshUiMode();
    };

    private static int touchButton(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_BUTTON_A:      // south
            case KeyEvent.KEYCODE_BUTTON_B:      // east
                return KeyEvent.KEYCODE_BUTTON_A;
            case KeyEvent.KEYCODE_BUTTON_X:      // west
            case KeyEvent.KEYCODE_BUTTON_Y:      // north
                return KeyEvent.KEYCODE_BUTTON_B;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return KeyEvent.KEYCODE_BUTTON_X;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return KeyEvent.KEYCODE_BUTTON_Y;
            case KeyEvent.KEYCODE_BUTTON_SELECT: // Minus: the Touch menu button
                return KeyEvent.KEYCODE_BUTTON_START;
            default:
                return key;
        }
    }

    private static boolean isDpad(int key) {
        return key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN
                || key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    private static int normalizeButton(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_BUTTON_1: return KeyEvent.KEYCODE_BUTTON_A; // Jump
            case KeyEvent.KEYCODE_BUTTON_2: return KeyEvent.KEYCODE_BUTTON_B; // Back / cancel
            case KeyEvent.KEYCODE_BUTTON_3: return KeyEvent.KEYCODE_BUTTON_X; // Interact
            case KeyEvent.KEYCODE_BUTTON_4: return KeyEvent.KEYCODE_BUTTON_Y; // Tool / inventory
            case KeyEvent.KEYCODE_BUTTON_5: return KeyEvent.KEYCODE_BUTTON_L1;
            case KeyEvent.KEYCODE_BUTTON_6: return KeyEvent.KEYCODE_BUTTON_R1;
            case KeyEvent.KEYCODE_BUTTON_7: return KeyEvent.KEYCODE_BUTTON_L2;
            case KeyEvent.KEYCODE_BUTTON_8: return KeyEvent.KEYCODE_BUTTON_R2;
            case KeyEvent.KEYCODE_BUTTON_9: return KeyEvent.KEYCODE_BUTTON_SELECT;
            case KeyEvent.KEYCODE_BUTTON_10: return KeyEvent.KEYCODE_BUTTON_START;
            case KeyEvent.KEYCODE_BUTTON_11: return KeyEvent.KEYCODE_BUTTON_THUMBL;
            case KeyEvent.KEYCODE_BUTTON_12: return KeyEvent.KEYCODE_BUTTON_THUMBR;
            default: return key;
        }
    }

    private int remapButton(int key) {
        SharedPreferences prefs = activity.getSharedPreferences("roblox_vr", Context.MODE_PRIVATE);
        if (key == KeyEvent.KEYCODE_BUTTON_A) return prefs.getInt("map_button_a", KeyEvent.KEYCODE_BUTTON_A);
        if (key == KeyEvent.KEYCODE_BUTTON_B) return prefs.getInt("map_button_b", KeyEvent.KEYCODE_BUTTON_B);
        if (key == KeyEvent.KEYCODE_BUTTON_X) return prefs.getInt("map_button_x", KeyEvent.KEYCODE_BUTTON_X);
        if (key == KeyEvent.KEYCODE_BUTTON_Y) return prefs.getInt("map_button_y", KeyEvent.KEYCODE_BUTTON_Y);
        return key;
    }

    private static int swappedKey(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_BUTTON_L1: return KeyEvent.KEYCODE_BUTTON_R1;
            case KeyEvent.KEYCODE_BUTTON_R1: return KeyEvent.KEYCODE_BUTTON_L1;
            case KeyEvent.KEYCODE_BUTTON_L2: return KeyEvent.KEYCODE_BUTTON_R2;
            case KeyEvent.KEYCODE_BUTTON_R2: return KeyEvent.KEYCODE_BUTTON_L2;
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return KeyEvent.KEYCODE_BUTTON_THUMBR;
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return KeyEvent.KEYCODE_BUTTON_THUMBL;
            case KeyEvent.KEYCODE_BUTTON_X: return KeyEvent.KEYCODE_BUTTON_A;
            case KeyEvent.KEYCODE_BUTTON_A: return KeyEvent.KEYCODE_BUTTON_X;
            case KeyEvent.KEYCODE_BUTTON_Y: return KeyEvent.KEYCODE_BUTTON_B;
            case KeyEvent.KEYCODE_BUTTON_B: return KeyEvent.KEYCODE_BUTTON_Y;
            case KeyEvent.KEYCODE_BUTTON_SELECT: return KeyEvent.KEYCODE_BUTTON_START;
            case KeyEvent.KEYCODE_BUTTON_START: return KeyEvent.KEYCODE_BUTTON_SELECT;
            default: return key;
        }
    }

    @Override
    public void onInputDeviceAdded(int deviceId) {
        InputDevice dev = input.getInputDevice(deviceId);
        if (controller(dev)) {
            ensureConnected();
            Log.i(TAG, "Added Joy-Con / controller: " + dev.getName());
        }
        if (controller(dev) || hasGyro(deviceId)) addMotion(deviceId);
    }

    @Override
    public void onInputDeviceChanged(int deviceId) {
        // The IMU node can attach after the buttons, so re-check for sensors.
        removeMotion(deviceId);
        InputDevice dev = input.getInputDevice(deviceId);
        if (controller(dev) || hasGyro(deviceId)) addMotion(deviceId);
    }

    @Override
    public void onInputDeviceRemoved(int deviceId) {
        removeMotion(deviceId);
        boolean any = false;
        for (int id : input.getInputDeviceIds())
            if (controller(input.getInputDevice(id))) { any = true; break; }
        if (!any && connected) {
            NativeInputInterface.nativeGamepadDisconnectEvent(VIRTUAL_ID);
            connected = false;
        }
    }
}
