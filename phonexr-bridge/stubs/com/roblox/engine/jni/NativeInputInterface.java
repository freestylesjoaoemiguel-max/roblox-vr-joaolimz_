package com.roblox.engine.jni;

public final class NativeInputInterface {
    public static native void nativeGamepadAxisEvent(int deviceId, int axis, float x, float y, float z);
    public static native void nativeGamepadButtonEvent(int deviceId, int keyCode, int pressed);
    public static native void nativeGamepadConnectEventWithGamepadType(int deviceId, int type);
    public static native void nativeGamepadDisconnectEvent(int deviceId);
    public static native void nativeSetGamepadSupportedKeyWithGamepadType(
            int deviceId, int keyCode, boolean supported, int gamepadType);
    public static native void nativeSetGamepadSupportedMotionWithGamepadType(
            int deviceId, int axis, int pairedAxis, boolean supported, int gamepadType);
    public static native void nativePassGyroscopeChange(float x, float y, float z,
            float qx, float qz, float qw, float qy);
    public static native void nativeSetGyroscopeEnabled(boolean enabled);
    public static native void nativePassMouseButton(float x, float y, boolean down, int button);
    public static native void nativePassMouseMove(float x, float y, float dx, float dy);
    public static native void nativePassMousePan(float x, float y, float dx, float dy);
}
