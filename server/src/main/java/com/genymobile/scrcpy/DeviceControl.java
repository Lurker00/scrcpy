package com.genymobile.scrcpy;

import com.genymobile.scrcpy.wrappers.InputManager;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Point;
import android.os.SystemClock;
import android.os.UserHandle;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.KeyCharacterMap;
import android.view.Surface;

import com.genymobile.scrcpy.wrappers.ActivityManager;
import com.genymobile.scrcpy.wrappers.InputMethodManager;
import com.genymobile.scrcpy.wrappers.PackageManager;
import com.genymobile.scrcpy.wrappers.ServiceManager;
import com.genymobile.scrcpy.wrappers.Settings;
import com.genymobile.scrcpy.wrappers.WindowManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public final class DeviceControl {
    private final ServiceManager serviceManager = new ServiceManager();
    private final WindowManager wm              = serviceManager.getWindowManager();
    private final InputMethodManager imm        = serviceManager.getInputMethodManager();
    private final PackageManager pm             = serviceManager.getPackageManager();

    private final int initialDensity = wm.getInitialDisplayDensity();
    private final int baseDensity    = wm.getBaseDisplayDensity();
    private final boolean densityChanged;

    private final int oldRotation        = wm.getRotation();
    private final boolean rotationFrozen = wm.isRotationFrozen();
    private boolean orientationChanged   = false;

    private final Point initialSize = new Point(0, 0);
    private final Point baseSize    = new Point(0, 0);
    private final boolean sizeChanged;

    private String baseBrightness;

    private static DeviceControl Instance;
    private final boolean tabletMode;

    private boolean rotateByBroadcast = true;
    static final String BROADCAST_ACTION = "name.lurker.RotateScreen";
    private final ActivityManager activityManager = serviceManager.getActivityManager();

    public static final String AdbKeyboard = "com.android.adbkeyboard";
    public static final String AdbIME      = AdbKeyboard+"/.AdbIME";
    private boolean disableAdbIme = false;
    private boolean enabledAdbIme = false;
    private final String lastIMEMethod;

    private boolean disableHWOverlays = false;

    enum Rotations {
        UNKNOWN,
        PORTRAIT,
        LANDSCAPE
    }
    private Rotations Rotation = Rotations.UNKNOWN;

    DeviceControl(final Options options) {
        Ln.i("DeviceControl is starting");
        Instance = this;

        if (turnScreenOn()) {
            // dirty hack
            // After POWER is injected, the device is powered on asynchronously.
            // To turn the device screen off while mirroring, the client will send a message that
            // would be handled before the device is actually powered on, so its effect would
            // be "canceled" once the device is turned back on.
            // Adding this delay prevents to handle the message before the device is actually
            // powered on.
            SystemClock.sleep(500);
        }

        tabletMode        = options.getTabletMode();
        disableHWOverlays = options.getDisableHWOverlays();

        if (options.getUseIME() && pm.isPackageAvailable(AdbKeyboard)) {
            lastIMEMethod = Settings.get(Settings.SECURE, Settings.DEFAULT_INPUT_METHOD);
            // Init AdbIME before rotateByBroadcast!
            try {
                disableAdbIme = !imm.setInputMethodEnabled(AdbIME, true);
                enabledAdbIme = imm.setInputMethod(AdbIME);
                Ln.i("AdbIME enabled: "+enabledAdbIme+" lastMethod: "+lastIMEMethod);
            } catch (Exception e) {
                Ln.w("IME: "+e.toString());
            }
        }
        else
            lastIMEMethod = "";

        wm.getInitialDisplaySize(initialSize);
        wm.getBaseDisplaySize(baseSize);

        if (tabletMode) {
            Ln.i("Rotation: " + oldRotation + ", Frozen: " + rotationFrozen + ", Tablet: " + options.getTabletMode());

            baseBrightness = Settings.get(Settings.SYSTEM, Settings.SCREEN_BRIGHTNESS);
            if (!"".equals(baseBrightness)) {
                Ln.i("baseBrightness: " + baseBrightness);
                Settings.put(Settings.SYSTEM, Settings.SCREEN_BRIGHTNESS, "8");
            }
        }

        if (disableHWOverlays)
           setDisableHWOverlays(true);

        densityChanged    = setDensity(options.getDensity());
        sizeChanged       = setSize(options.getSize());
        rotateByBroadcast = tabletMode && startRotator();
        Ln.i("DeviceControl started");
    }

    private boolean sendRotate(int orientation) {
        if (!rotateByBroadcast) return false;
        Intent intent = new Intent();
        intent.setAction(BROADCAST_ACTION);
        intent.putExtra("o", orientation);
        boolean result = activityManager.broadcastIntent(intent);
        Ln.i("Rotation by broadcast: "+orientation+" "+result);
        return result;
    }

    private boolean sameOrientation(Rotations r) {
        if (Instance.Rotation != r) return false;
        int co = wm.getRotation();
        if (Instance.Rotation == Rotations.PORTRAIT  && (co == Surface.ROTATION_0  || co == Surface.ROTATION_180)) return true;
        if (Instance.Rotation == Rotations.LANDSCAPE && (co == Surface.ROTATION_90 || co == Surface.ROTATION_270)) return true;
        return false;
    }

    static public void setPortrait() {
        if (Instance == null || !Instance.tabletMode || Instance.sameOrientation(Rotations.PORTRAIT)) return;

        if (!Instance.sendRotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT))
            Instance.wm.freezeRotation(Surface.ROTATION_0);

        Instance.Rotation = Rotations.PORTRAIT;
        Instance.orientationChanged = true;
    }

    static public void setLandscape() {
        if (Instance == null || !Instance.tabletMode || Instance.sameOrientation(Rotations.LANDSCAPE)) return;

        if (!Instance.sendRotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE))
            Instance.wm.freezeRotation(Surface.ROTATION_90);

        Instance.Rotation = Rotations.LANDSCAPE;
        Instance.orientationChanged = true;
    }

    public void Finish() {
        if (baseBrightness != null && !baseBrightness.isEmpty())
            Settings.put(Settings.SYSTEM, Settings.SCREEN_BRIGHTNESS, baseBrightness);

        if (orientationChanged) {
            if (!Instance.sendRotate(-1)) {
                wm.freezeRotation(oldRotation);
                if (!rotationFrozen) wm.thawRotation();
            }
        }

        if (densityChanged) {
            if (baseDensity == initialDensity) wm.clearForcedDisplayDensity();
            else wm.setForcedDisplayDensity(baseDensity);
        }

        if (sizeChanged) {
            if (baseSize.x == initialSize.x && baseSize.y == initialSize.y)
                wm.clearForcedDisplaySize();
            else
                wm.setForcedDisplaySize(baseSize.x, baseSize.y);
        }

        if (disableHWOverlays)
           setDisableHWOverlays(false);

        if (enabledAdbIme && lastIMEMethod != null && !lastIMEMethod.isEmpty())
            imm.setInputMethod(lastIMEMethod);
        if (disableAdbIme)
            imm.setInputMethodEnabled(AdbIME, false);

        Instance = null;
        Ln.i("DeviceControl stopped");
    }

    private boolean setDensity(final int density) {
        if (density <= 0) return false;
        Ln.i("initialDensity: " + initialDensity + ", baseDensity: " + baseDensity + ", New density: " + density);
        wm.setForcedDisplayDensity(density);
        return true;
    }

    private boolean setSize(final Point size) {
        if (size.x <= 0 || size.y <= 0) return false;
        Ln.i("initialSize: " + initialSize.x + "x" + initialSize.y + ", baseSize: " + baseSize.x + "x" + baseSize.y + " New size: " + size.x + "x" + size.y);
        wm.setForcedDisplaySize(size.x, size.y);
        return true;
    }

    private void setDisableHWOverlays(boolean value) {
        Settings.setDisableHWOverlays(value);
    }

    public static boolean isAdbIMEEnabled() {
        return Instance != null && Instance.enabledAdbIme;
    }

    static private final String SERVICE = "com.android.adbkeyboard/.Rotate";
    private boolean startRotator() {
        return activityManager.startService(SERVICE);
    }

    // Below methods were copy-pasted from Controller.
    // TODO: Remove redundant methods from Controller.
    public boolean isScreenOn() {
        return serviceManager.getPowerManager().isScreenOn();
    }

    public boolean injectInputEvent(InputEvent inputEvent, int mode) {
        return serviceManager.getInputManager().injectInputEvent(inputEvent, mode);
    }

    private boolean injectEvent(InputEvent event) {
        return injectInputEvent(event, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
    }

    private boolean injectKeyEvent(int action, int keyCode, int repeat, int metaState) {
        long now = SystemClock.uptimeMillis();
        KeyEvent event = new KeyEvent(now, now, action, keyCode, repeat, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                InputDevice.SOURCE_KEYBOARD);
        return injectEvent(event);
    }

    private boolean injectKeycode(int keyCode) {
        return injectKeyEvent(KeyEvent.ACTION_DOWN, keyCode, 0, 0)
                && injectKeyEvent(KeyEvent.ACTION_UP, keyCode, 0, 0);
    }

    private boolean turnScreenOn() {
        return isScreenOn() ? false : injectKeycode(KeyEvent.KEYCODE_POWER);
    }
}
