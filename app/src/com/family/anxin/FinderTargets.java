package com.family.anxin;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 找出微信里所有「视频号」相关的 Activity。
 * 视频号（含直播、视频号小店）都在 com.tencent.mm 的 finder 插件里，
 * 所以只要把 finder 相关的 Activity 全部禁用，视频号就彻底打不开。
 */
public final class FinderTargets {

    public static final String WECHAT = "com.tencent.mm";

    private FinderTargets() {
    }

    /**
     * 无障碍判定用：窗口类名里含 "finder" 就认为是视频号界面。
     * 实测命中 com.tencent.mm.plugin.finder.ui.FinderShareFeedRelUI 等。
     */
    public static boolean isFinderWindow(String cls) {
        if (cls == null) return false;
        return cls.toLowerCase().contains("finder");
    }

    /**
     * 深度锁定用：只认 finder 插件本身的组件。
     *
     * 注意这里必须比 isFinderWindow 严格得多。用 contains("finder") 去枚举组件会
     * 命中 351 个 Activity，其中一大堆和视频号无关
     * （例如 com.tencent.mm.ui.contact.privacy.FinderBlockListUI 属于隐私设置）。
     * 微信的插件都在 com.tencent.mm.plugin.&lt;name&gt;，视频号插件就是 plugin.finder。
     */
    public static boolean isFinderComponent(String cls) {
        if (cls == null) return false;
        String s = cls.toLowerCase();
        return s.startsWith("com.tencent.mm.plugin.finder.");
    }

    public static boolean isWeChatInstalled(Context ctx) {
        try {
            ctx.getPackageManager().getPackageInfo(WECHAT, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 优先用 PackageManager 枚举；拿不到时再用 shell dump 兜底。 */
    public static List<String> list(Context ctx, boolean allowShellFallback) {
        List<String> out = byPackageManager(ctx);
        if (out.isEmpty() && allowShellFallback) {
            out = byShellDump();
        }
        Collections.sort(out);
        return out;
    }

    private static List<String> byPackageManager(Context ctx) {
        List<String> out = new ArrayList<String>();
        try {
            PackageManager pm = ctx.getPackageManager();
            PackageInfo pi = pm.getPackageInfo(WECHAT, PackageManager.GET_ACTIVITIES);
            if (pi != null && pi.activities != null) {
                for (ActivityInfo ai : pi.activities) {
                    if (ai == null || ai.name == null) continue;
                    if (isFinderComponent(ai.name) && !out.contains(ai.name)) out.add(ai.name);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static List<String> byShellDump() {
        List<String> out = new ArrayList<String>();
        try {
            String dump = Sh.run("dumpsys package " + WECHAT);
            java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile("com\\.tencent\\.mm/([A-Za-z0-9._$]+)").matcher(dump);
            while (m.find()) {
                String cls = m.group(1);
                if (isFinderComponent(cls) && !out.contains(cls)) out.add(cls);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }
}
