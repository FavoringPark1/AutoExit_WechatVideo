package com.family.anxin;

import android.content.Context;
import android.content.SharedPreferences;

/** 全部配置都只存在本机，不联网、不上传。 */
public final class Prefs {

    private static final String NAME = "anxin";
    public static final String DEFAULT_PIN = "1234";

    public static final String KEY_PIN = "pin";
    public static final String KEY_VIBRATE = "vibrate";
    public static final String KEY_HOME = "go_home";
    public static final String KEY_MODE = "block_mode";
    public static final String KEY_TOAST = "toast";
    public static final String KEY_CONTENT = "content_fallback";
    public static final String KEY_FOREGROUND = "foreground";
    public static final String KEY_BLOCKED = "blocked_count";
    public static final String KEY_LAST = "last_window";
    public static final String KEY_LOCKED = "locked_components";
    public static final String KEY_LOG = "win_log";

    /** 拦截方式：退出到上一页（默认） */
    public static final int MODE_EXIT = 0;
    /** 拦截方式：直接回桌面 */
    public static final int MODE_HOME = 1;

    private Prefs() {
    }

    public static SharedPreferences get(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static String pin(Context c) {
        return get(c).getString(KEY_PIN, DEFAULT_PIN);
    }

    public static void setPin(Context c, String pin) {
        get(c).edit().putString(KEY_PIN, pin).apply();
    }

    public static boolean vibrate(Context c) {
        return get(c).getBoolean(KEY_VIBRATE, true);
    }

    public static boolean goHome(Context c) {
        return mode(c) == MODE_HOME;
    }

    public static int mode(Context c) {
        return get(c).getInt(KEY_MODE, MODE_EXIT);
    }

    public static void setMode(Context c, int m) {
        get(c).edit().putInt(KEY_MODE, m).apply();
    }

    public static boolean showToast(Context c) {
        return get(c).getBoolean(KEY_TOAST, false);
    }

    public static boolean contentFallback(Context c) {
        return get(c).getBoolean(KEY_CONTENT, true);
    }

    /** 后台常驻（前台服务）。默认关闭：开了通知栏会留一条常驻通知。 */
    public static boolean foreground(Context c) {
        return get(c).getBoolean(KEY_FOREGROUND, false);
    }

    public static void setForeground(Context c, boolean v) {
        get(c).edit().putBoolean(KEY_FOREGROUND, v).apply();
    }

    public static void setBool(Context c, String key, boolean v) {
        get(c).edit().putBoolean(key, v).apply();
    }

    public static void bumpBlocked(Context c) {
        get(c).edit().putInt(KEY_BLOCKED, get(c).getInt(KEY_BLOCKED, 0) + 1).apply();
    }

    /** 换行分隔的已禁用组件全名。 */
    public static String lockedList(Context c) {
        return get(c).getString(KEY_LOCKED, "");
    }

    public static void setLockedList(Context c, String v) {
        get(c).edit().putString(KEY_LOCKED, v).apply();
    }
}
