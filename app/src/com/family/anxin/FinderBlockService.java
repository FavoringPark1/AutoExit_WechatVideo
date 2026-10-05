package com.family.anxin;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.ArrayDeque;

/**
 * 拦截服务。
 *
 * 判定分两级：
 *  1) 窗口类名里含 "finder" —— 直接命中（实测 plugin.finder.ui.* ）
 *  2) 类名不含 finder 时，看页面内容特征：同时出现「推荐」和「关注/朋友」两个 tab
 *
 * 动作逻辑（v1.3 重写）：
 *
 *  回桌面模式：返回一次（把视频号页面从微信任务栈里弹掉）→ 450ms 后按桌面键。
 *      只按桌面键的话，任务栈顶部还是视频号页面，重新打开微信会直接落回去，
 *      又被踢回桌面 —— 无限循环。所以必须先返回。这个组合已实测有效。
 *
 *  退回上一页模式：返回 → 等 900ms → **看现在到底在哪个页面** →
 *      还在视频号就再按一次（最多 3 次）→ 三次都出不去才升级成回桌面。
 *      v1.2 之前是"盲发"：检测到就立刻按返回，页面还在入场动画里，
 *      返回键很容易被吃掉，所以看起来"没效果"。现在先等 400ms 让页面停稳。
 *
 * 每一步动作都会写进诊断时间线（前缀 ">> "），和窗口记录混在一起方便对照。
 */
public class FinderBlockService extends AccessibilityService {

    private static final String TAG = "AnXin";

    private static final long CONTENT_SCAN_GAP_MS = 1200L;
    private static final long DEBOUNCE_MS = 1000L;
    /** 检测到之后先等一会儿，让页面入场动画结束、真正拿到按键焦点 */
    private static final long SETTLE_MS = 400L;
    /** 按完返回后等结果 */
    private static final long VERIFY_MS = 900L;
    private static final long RETRY_GAP_MS = 200L;
    private static final long HOME_GAP_MS = 450L;
    private static final int MAX_BACK = 3;

    // 供诊断页读取（同进程，直接读静态字段）
    public static volatile long connectedAt = 0L;
    public static volatile int totalEvents = 0;
    public static volatile int wechatEvents = 0;
    public static volatile String lastWechatClass = "";

    private boolean busy = false;
    private int attempts = 0;
    private long lastTriggerAt = 0L;
    private long lastContentScan = 0L;
    private String lastLoggedClass = "";

    private final Handler handler = new Handler(Looper.getMainLooper());

    // ---------------------------------------------------------------- 生命周期

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        connectedAt = System.currentTimeMillis();
        busy = false;
        Diag.logAction(this, "拦截服务已连接");
        Log.i(TAG, "accessibility service connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        try {
            totalEvents++;

            CharSequence p = event.getPackageName();
            if (p == null) return;
            if (!FinderTargets.WECHAT.equals(p.toString())) return;
            wechatEvents++;

            CharSequence c = event.getClassName();
            String cls = c == null ? "" : c.toString();
            if (cls.length() == 0) cls = "(空)";

            int type = event.getEventType();
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                lastWechatClass = cls;
                lastLoggedClass = cls;
                Diag.logWindow(this, cls);
            } else if (!cls.equals(lastLoggedClass)) {
                lastLoggedClass = cls;
                Diag.logWindow(this, cls + "   [content]");
            }

            if (FinderTargets.isFinderWindow(cls)) {
                trigger(cls, "类名");
                return;
            }

            if (!Prefs.contentFallback(this)) return;
            long now = System.currentTimeMillis();
            if (now - lastContentScan < CONTENT_SCAN_GAP_MS) return;
            lastContentScan = now;
            if (looksLikeFinderContent()) {
                trigger(cls, "内容");
            }
        } catch (Throwable t) {
            Log.w(TAG, "onAccessibilityEvent failed", t);
        }
    }

    @Override
    public void onInterrupt() {
        Log.i(TAG, "accessibility service interrupted");
    }

    // ---------------------------------------------------------------- 动作流程

    private void trigger(String cls, String how) {
        long now = System.currentTimeMillis();
        if (busy) return;
        if (now - lastTriggerAt < DEBOUNCE_MS) return;
        lastTriggerAt = now;
        busy = true;
        attempts = 0;

        final boolean homeMode = Prefs.mode(this) == Prefs.MODE_HOME;

        Prefs.bumpBlocked(this);
        Prefs.get(this).edit().putString(Prefs.KEY_LAST, cls + "  (" + how + ")").apply();
        Diag.logAction(this, "命中 " + shortName(cls) + " [" + how + "] → "
                + (homeMode ? "回桌面流程" : "退出流程"));

        if (Prefs.vibrate(this)) {
            try {
                Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                if (v != null) v.vibrate(60L);
            } catch (Throwable ignored) {
            }
        }
        if (Prefs.showToast(this)) {
            try {
                Toast.makeText(this, "已跳过", Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
            }
        }

        // 先等页面停稳再按返回，否则按键很容易被入场动画吃掉
        handler.postDelayed(backStep, SETTLE_MS);
    }

    private final Runnable backStep = new Runnable() {
        public void run() {
            attempts++;
            // 第一次用无障碍注入；如果被吃掉，第二次起改用 Shizuku 的
            // `input keyevent 4`——走的是完全不同的注入通道（shell → InputManager），
            // 微信对无障碍按键的拦截对它无效。
            final boolean useShell = attempts >= 2 && Sh.available() && Sh.granted();

            Diag.logAction(FinderBlockService.this,
                    (useShell ? "shell 注入返回键" : "按返回") + " 第" + attempts + "次（当前 "
                            + shortName(lastWechatClass) + "）");

            if (useShell) {
                new Thread(new Runnable() {
                    public void run() {
                        Sh.run("input keyevent 4");
                    }
                }).start();
            } else {
                performGlobalAction(GLOBAL_ACTION_BACK);
            }

            if (Prefs.mode(FinderBlockService.this) == Prefs.MODE_HOME) {
                // 回桌面模式：一次返回把视频号页面弹出任务栈，再回桌面
                handler.postDelayed(homeStep, HOME_GAP_MS);
                return;
            }
            handler.postDelayed(verifyStep, VERIFY_MS);
        }
    };

    private final Runnable verifyStep = new Runnable() {
        public void run() {
            String cur = lastWechatClass;
            boolean still = FinderTargets.isFinderWindow(cur);
            Diag.logAction(FinderBlockService.this, "检查 → " + shortName(cur)
                    + (still ? "（还在原页面）" : "（已退出）"));

            if (still && attempts < MAX_BACK) {
                handler.postDelayed(backStep, RETRY_GAP_MS);
                return;
            }
            if (still) {
                Diag.logAction(FinderBlockService.this,
                        "返回 " + attempts + " 次都出不去，升级成回桌面");
                handler.postDelayed(homeStep, HOME_GAP_MS);
                return;
            }
            busy = false;
        }
    };

    private final Runnable homeStep = new Runnable() {
        public void run() {
            Diag.logAction(FinderBlockService.this, "按桌面键");
            performGlobalAction(GLOBAL_ACTION_HOME);
            busy = false;
        }
    };

    // ---------------------------------------------------------------- 内容兜底

    /** 视频号首页顶部同时有「推荐」和「关注/朋友」两个 tab，用这个组合做特征。 */
    private boolean looksLikeFinderContent() {
        AccessibilityNodeInfo root;
        try {
            root = getRootInActiveWindow();
        } catch (Throwable t) {
            return false;
        }
        if (root == null) return false;

        boolean hasRec = false;
        boolean hasFollow = false;
        int visited = 0;
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<AccessibilityNodeInfo>();
        q.add(root);
        try {
            while (!q.isEmpty() && visited < 400) {
                AccessibilityNodeInfo n = q.poll();
                if (n == null) continue;
                visited++;
                CharSequence t = n.getText();
                if (t == null) t = n.getContentDescription();
                if (t != null) {
                    String s = t.toString();
                    if (s.length() <= 6) {
                        if (s.equals("推荐")) hasRec = true;
                        else if (s.equals("关注") || s.equals("朋友")) hasFollow = true;
                    }
                }
                if (hasRec && hasFollow) return true;
                int cnt = n.getChildCount();
                for (int i = 0; i < cnt; i++) {
                    AccessibilityNodeInfo ch = n.getChild(i);
                    if (ch != null) q.add(ch);
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static String shortName(String cls) {
        if (cls == null || cls.length() == 0) return "(空)";
        int i = cls.lastIndexOf('.');
        return i < 0 ? cls : cls.substring(i + 1);
    }
}
