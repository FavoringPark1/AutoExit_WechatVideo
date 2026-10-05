package com.family.anxin;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;

import java.util.Calendar;

/** 把所有排查需要的信息汇总成一段可以直接复制发出去的文本。 */
public final class Diag {

    private static final int MAX_LINES = 60;

    private Diag() {
    }

    public static String fmt(long millis) {
        if (millis <= 0L) return "-";
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        return two(c.get(Calendar.HOUR_OF_DAY)) + ":" + two(c.get(Calendar.MINUTE))
                + ":" + two(c.get(Calendar.SECOND));
    }

    private static String two(int v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }

    /** 记录一条微信窗口类名。 */
    public static void logWindow(Context ctx, String cls) {
        logLine(ctx, cls);
    }

    /** 记录一条我们自己的动作，和窗口记录混在同一条时间线上，方便对照因果。 */
    public static void logAction(Context ctx, String what) {
        logLine(ctx, ">> " + what);
    }

    /** 最多保留 MAX_LINES 条（最新的在最上面）。 */
    private static void logLine(Context ctx, String body) {
        try {
            String old = Prefs.get(ctx).getString(Prefs.KEY_LOG, "");
            StringBuilder sb = new StringBuilder();
            sb.append(fmt(System.currentTimeMillis())).append("  ").append(body).append('\n');
            sb.append(old);

            String[] lines = sb.toString().split("\n");
            if (lines.length > MAX_LINES) {
                StringBuilder t = new StringBuilder();
                for (int i = 0; i < MAX_LINES; i++) t.append(lines[i]).append('\n');
                Prefs.get(ctx).edit().putString(Prefs.KEY_LOG, t.toString()).apply();
            } else {
                Prefs.get(ctx).edit().putString(Prefs.KEY_LOG, sb.toString()).apply();
            }
        } catch (Throwable ignored) {
        }
    }

    public static boolean isAccessibilityEnabled(Context ctx) {
        try {
            String flat = Settings.Secure.getString(ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (flat == null) return false;
            String full = ctx.getPackageName() + "/" + FinderBlockService.class.getName();
            String shortForm = ctx.getPackageName() + "/.FinderBlockService";
            for (String s : flat.split(":")) {
                if (s.equalsIgnoreCase(full) || s.equalsIgnoreCase(shortForm)) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static String buildReport(Context ctx) {
        StringBuilder sb = new StringBuilder();

        sb.append("===== 跳广告 诊断 =====").append('\n');
        sb.append("时间   : ").append(fmt(System.currentTimeMillis())).append('\n');
        sb.append("机型   : ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" / Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")").append('\n');
        sb.append("目标SDK: 33 / 版本 1.5").append('\n');
        sb.append("拦截方式: ")
                .append(Prefs.mode(ctx) == Prefs.MODE_HOME ? "直接回桌面" : "退回上一页")
                .append('\n');
        sb.append("内容兜底: ").append(Prefs.contentFallback(ctx) ? "开" : "关").append('\n').append('\n');

        sb.append("----- 无障碍服务 -----").append('\n');
        sb.append("系统开关 : ").append(isAccessibilityEnabled(ctx) ? "已开启" : "未开启").append('\n');
        sb.append("服务连接 : ")
                .append(FinderBlockService.connectedAt == 0L
                        ? "从未连接（服务没跑起来）"
                        : ("已连接，于 " + fmt(FinderBlockService.connectedAt)
                        + "（" + ((System.currentTimeMillis() - FinderBlockService.connectedAt) / 1000) + " 秒前）"))
                .append('\n');
        sb.append("收到事件 : ").append(FinderBlockService.totalEvents)
                .append(" 条，其中微信 ").append(FinderBlockService.wechatEvents).append(" 条").append('\n');
        sb.append("已拦截   : ").append(Prefs.get(ctx).getInt(Prefs.KEY_BLOCKED, 0)).append(" 次").append('\n');
        sb.append("最近命中 : ").append(Prefs.get(ctx).getString(Prefs.KEY_LAST, "无")).append('\n');
        sb.append('\n');

        sb.append("----- 窗口 / 动作时间线（最新在最上面，>> 开头是本应用的动作）-----").append('\n');
        String log = Prefs.get(ctx).getString(Prefs.KEY_LOG, "");
        sb.append(log.length() == 0 ? "（还没有任何记录）" : log);
        sb.append('\n');

        sb.append("----- Shizuku -----").append('\n');
        sb.append("binder   : ").append(Sh.binderReceived() ? "已收到" : "未收到").append('\n');
        sb.append("ping     : ").append(Sh.available() ? "通" : "不通").append('\n');
        sb.append("授权     : ").append(Sh.granted() ? "已授权" : "未授权").append('\n');
        sb.append("版本     : ").append(Sh.version()).append('\n');
        sb.append("uid      : ").append(Sh.uid()).append("（2000=adb, 0=root）").append('\n');
        sb.append("异常     : ").append(Sh.lastError()).append('\n');
        if (Sh.available() && !Sh.granted()) {
            sb.append("提示     : binder 通了但没授权，点「执行深度锁定」应该会弹出授权框").append('\n');
        }
        if (!Sh.binderReceived()) {
            sb.append("提示     : binder 没收到 = Shizuku 管理器没有把 binder 推给本应用。").append('\n');
            sb.append("           常见原因：装完本应用后没有重启过 Shizuku。").append('\n');
            sb.append("           解决：打开 Shizuku → 停止 → 重新启动 → 再回到本应用。").append('\n');
        }
        sb.append('\n');

        sb.append("----- 深度锁定 -----").append('\n');
        String locked = Prefs.lockedList(ctx);
        int n = locked.trim().length() == 0 ? 0 : locked.trim().split("\n").length;
        sb.append("已记录禁用组件 : ").append(n).append(" 个").append('\n');
        sb.append("微信已安装     : ").append(FinderTargets.isWeChatInstalled(ctx) ? "是" : "否").append('\n');
        sb.append("枚举到 finder  : ").append(FinderTargets.list(ctx, false).size()).append(" 个 Activity").append('\n');

        return sb.toString();
    }
}
