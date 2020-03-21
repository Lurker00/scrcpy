package com.genymobile.scrcpy.wrappers;

import com.genymobile.scrcpy.Ln;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public final class Settings {
    public static final String SYSTEM = "system";
    public static final String SECURE = "secure";
    public static final String SCREEN_BRIGHTNESS = android.provider.Settings.System.SCREEN_BRIGHTNESS;
    public static final String HIDE_ROTATION_LOCK_TOGGLE_FOR_ACCESSIBILITY = "hide_rotation_lock_toggle_for_accessibility"; //android.provider.Settings.System.HIDE_ROTATION_LOCK_TOGGLE_FOR_ACCESSIBILITY;
    public static final String ACCELEROMETER_ROTATION = android.provider.Settings.System.ACCELEROMETER_ROTATION;
    public static final String USER_ROTATION = android.provider.Settings.System.USER_ROTATION;
    public static final String DEFAULT_INPUT_METHOD = "default_input_method";

    public static String get(final String namespace, final String key) {
        String[] cmd = new String[]{"settings", "get", namespace, key};
        try {
            final Process p = Runtime.getRuntime().exec(cmd);
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(),"UTF-8"));
            String line = reader.readLine();
            reader.close();
            return line;
        }
        catch (Exception e) {
            Ln.e("Settings.get", e);
        }
        return "";
    }

    public static void put(final String namespace, final String key, final String value) {
        String[] cmd = new String[]{"settings","put", namespace, key, value};
        try {
            final Process p = Runtime.getRuntime().exec(cmd);
            p.waitFor();
        }
        catch (Exception e) {}
    }

    public static void setDisableHWOverlays(boolean value) {
        String[] cmd = new String[]{"su","-c", "service", "call", "SurfaceFlinger", "1008", "i32", value ? "1" : "0"};
        try {
            final Process p = Runtime.getRuntime().exec(cmd);
            p.waitFor();
        }
        catch (Exception e) {}
    }
}
