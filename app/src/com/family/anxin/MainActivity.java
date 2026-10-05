package com.family.anxin;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {

    private static final int REQ_SHIZUKU = 4001;
    private static final int REQ_NOTI = 4002;

    private TextView tvStatus;
    private TextView tvDeep;
    private TextView tvLast;
    private TextView tvBlocked;
    private CheckBox cbVibrate;
    private CheckBox cbToast;
    private CheckBox cbContent;
    private CheckBox cbForeground;
    private android.widget.RadioGroup rgMode;

    private boolean listenersRegistered = false;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = (TextView) findViewById(R.id.tvStatus);
        tvDeep = (TextView) findViewById(R.id.tvDeep);
        tvLast = (TextView) findViewById(R.id.tvLast);
        tvBlocked = (TextView) findViewById(R.id.tvBlocked);
        cbVibrate = (CheckBox) findViewById(R.id.cbVibrate);
        cbToast = (CheckBox) findViewById(R.id.cbToast);
        cbContent = (CheckBox) findViewById(R.id.cbContent);
        cbForeground = (CheckBox) findViewById(R.id.cbForeground);
        rgMode = (android.widget.RadioGroup) findViewById(R.id.rgMode);

        cbVibrate.setChecked(Prefs.vibrate(this));
        cbToast.setChecked(Prefs.showToast(this));
        cbContent.setChecked(Prefs.contentFallback(this));
        cbForeground.setChecked(Prefs.foreground(this));
        ((android.widget.RadioButton) findViewById(
                Prefs.mode(this) == Prefs.MODE_HOME ? R.id.rbHome : R.id.rbExit)).setChecked(true);

        cbVibrate.setOnClickListener(bool(cbVibrate, Prefs.KEY_VIBRATE));
        cbToast.setOnClickListener(bool(cbToast, Prefs.KEY_TOAST));
        cbContent.setOnClickListener(bool(cbContent, Prefs.KEY_CONTENT));

        cbForeground.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean on = cbForeground.isChecked();
                Prefs.setForeground(MainActivity.this, on);
                if (on) {
                    requestNotificationPermission();
                    startGuardService();
                    toast("已开启后台常驻，通知栏会出现一条通知");
                } else {
                    stopGuardService();
                    toast("已关闭后台常驻，通知栏不再有通知");
                }
            }
        });

        rgMode.setOnCheckedChangeListener(new android.widget.RadioGroup.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.RadioGroup group, int checkedId) {
                Prefs.setMode(MainActivity.this,
                        checkedId == R.id.rbHome ? Prefs.MODE_HOME : Prefs.MODE_EXIT);
            }
        });

        findViewById(R.id.btnA11y).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                openAccessibilitySettings();
            }
        });
        findViewById(R.id.btnLock).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                onDeepLockClicked();
            }
        });
        findViewById(R.id.btnAdb).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showAdbHelp();
            }
        });
        findViewById(R.id.btnUnlock).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                onDeepUnlockClicked();
            }
        });
        findViewById(R.id.btnDiag).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showDiag();
            }
        });
        findViewById(R.id.btnWeChat).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                openWeChat();
            }
        });
        findViewById(R.id.btnPin).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                changePin();
            }
        });
        findViewById(R.id.btnHelp).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showHelp();
            }
        });

        registerShizukuListener();
        // 后台常驻默认关闭；只有用户主动开过才启动前台服务（会在通知栏留一条通知）
        if (Prefs.foreground(this)) startGuardService();
        askPin();
    }

    private View.OnClickListener bool(final CheckBox box, final String key) {
        return new View.OnClickListener() {
            public void onClick(View v) {
                Prefs.setBool(MainActivity.this, key, box.isChecked());
            }
        };
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        // binder 可能是异步推过来的，几秒后再刷一次
        ui.postDelayed(new Runnable() {
            public void run() {
                refresh();
            }
        }, 1500L);
    }

    // ---------------------------------------------------------------- 状态刷新

    private void refresh() {
        boolean on = Diag.isAccessibilityEnabled(this);
        tvStatus.setText(on ? "跳过服务：已开启 ✓" : "跳过服务：未开启 ✗");
        tvStatus.setTextColor(on ? 0xFF1B7A3D : 0xFFB3261E);

        tvBlocked.setText("已拦截：" + Prefs.get(this).getInt(Prefs.KEY_BLOCKED, 0) + " 次");
        String last = Prefs.get(this).getString(Prefs.KEY_LAST, "");
        tvLast.setText(last.length() == 0 ? "最近命中：无" : "最近命中：" + last);

        updateDeepStatus();
    }

    private void updateDeepStatus() {
        String locked = Prefs.lockedList(this);
        int n = locked.trim().length() == 0 ? 0 : locked.trim().split("\n").length;

        String msg;
        if (!Sh.binderReceived()) {
            msg = "深度锁定：Shizuku 未连接（binder 没收到，见诊断信息）";
        } else if (!Sh.available()) {
            msg = "深度锁定：Shizuku binder 已收到但 ping 不通";
        } else if (!Sh.granted()) {
            msg = "深度锁定：Shizuku 已连接，等待授权";
        } else if (n == 0) {
            msg = "深度锁定：未执行（Shizuku v" + Sh.version() + " 已就绪）";
        } else {
            msg = "深度锁定：已锁定 " + n + " 个组件，当前生效 " + countStillDisabled(locked) + " 个";
        }
        tvDeep.setText(msg);
    }

    private int countStillDisabled(String locked) {
        int c = 0;
        try {
            PackageManager pm = getPackageManager();
            for (String cls : locked.trim().split("\n")) {
                if (cls.length() == 0) continue;
                int st = pm.getComponentEnabledSetting(new ComponentName(FinderTargets.WECHAT, cls));
                if (st == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                        || st == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                    c++;
                }
            }
        } catch (Throwable ignored) {
        }
        return c;
    }

    // ---------------------------------------------------------------- 无障碍

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
            toast("请在列表里找到「跳广告」并打开开关");
        } catch (Throwable t) {
            dialog("打不开设置", "请手动进入：设置 → 辅助功能 → 无障碍 → 跳广告，然后打开开关。");
        }
    }

    private void startGuardService() {
        try {
            Intent s = new Intent(this, GuardService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(s);
            } else {
                startService(s);
            }
        } catch (Throwable ignored) {
        }
    }

    private void stopGuardService() {
        try {
            stopService(new Intent(this, GuardService.class));
        } catch (Throwable ignored) {
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return;
        try {
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTI);
            }
        } catch (Throwable ignored) {
        }
    }

    private void openWeChat() {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(FinderTargets.WECHAT);
            if (i == null) {
                toast("这台手机上没装微信");
                return;
            }
            startActivity(i);
        } catch (Throwable t) {
            toast("打不开微信：" + t);
        }
    }

    // ---------------------------------------------------------------- Shizuku

    private void registerShizukuListener() {
        if (listenersRegistered) return;
        try {
            Shizuku.addBinderReceivedListenerSticky(new Shizuku.OnBinderReceivedListener() {
                public void onBinderReceived() {
                    ui.post(new Runnable() {
                        public void run() {
                            updateDeepStatus();
                        }
                    });
                }
            });
            Shizuku.addBinderDeadListener(new Shizuku.OnBinderDeadListener() {
                public void onBinderDead() {
                    ui.post(new Runnable() {
                        public void run() {
                            updateDeepStatus();
                        }
                    });
                }
            });
            Shizuku.addRequestPermissionResultListener(new Shizuku.OnRequestPermissionResultListener() {
                public void onRequestPermissionResult(int requestCode, int grantResult) {
                    if (requestCode != REQ_SHIZUKU) return;
                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        ui.post(new Runnable() {
                            public void run() {
                                confirmDeepLock();
                            }
                        });
                    } else {
                        toast("Shizuku 授权被拒绝");
                    }
                }
            });
            listenersRegistered = true;
        } catch (Throwable t) {
            toast("Shizuku 监听注册失败：" + t);
        }
    }

    private void onDeepLockClicked() {
        if (!Sh.binderReceived()) {
            registerShizukuListener();
            ui.postDelayed(new Runnable() {
                public void run() {
                    if (!Sh.binderReceived()) {
                        dialog("还没连上 Shizuku",
                                "Shizuku 的 binder 没有送到本应用。\n\n"
                                        + "binder 是 Shizuku 管理器主动推送给已安装应用的。"
                                        + "如果本应用是在 Shizuku 启动之后才装的，"
                                        + "Shizuku 可能还没扫到它。\n\n"
                                        + "解决办法（任选其一）：\n"
                                        + "1. 打开 Shizuku → 停止 → 重新启动 → 回到本应用再点一次。\n"
                                        + "2. 直接点「用电脑 adb 深度锁定」，效果完全一样而且更可靠。\n\n"
                                        + "当前状态：" + Sh.lastError());
                    } else {
                        onDeepLockClicked();
                    }
                }
            }, 600L);
            return;
        }
        if (!Sh.available()) {
            dialog("Shizuku 连接异常", "binder 收到了但 ping 不通：\n" + Sh.lastError());
            return;
        }
        if (!Sh.granted()) {
            try {
                Shizuku.requestPermission(REQ_SHIZUKU);
                toast("请在 Shizuku 弹窗里选择「允许」");
            } catch (Throwable t) {
                dialog("请求 Shizuku 授权失败", String.valueOf(t) + "\n" + Sh.lastError());
            }
            return;
        }
        confirmDeepLock();
    }

    private void confirmDeepLock() {
        toast("正在读取微信组件列表…");
        new Thread(new Runnable() {
            public void run() {
                final List<String> targets = FinderTargets.list(MainActivity.this, true);
                ui.post(new Runnable() {
                    public void run() {
                        if (targets.isEmpty()) {
                            dialog("没找到相关组件",
                                    "可能是微信版本比较特殊，或者系统限制了本应用查看其它应用。\n\n"
                                            + "先只用上面的「跳过服务」也够用。");
                            return;
                        }
                        StringBuilder sb = new StringBuilder();
                        sb.append("将禁用以下 ").append(targets.size()).append(" 个组件：\n\n");
                        int show = Math.min(12, targets.size());
                        for (int i = 0; i < show; i++) {
                            sb.append("· ").append(shortName(targets.get(i))).append('\n');
                        }
                        if (targets.size() > show) {
                            sb.append("… 以及另外 ").append(targets.size() - show).append(" 个\n");
                        }
                        sb.append("\n禁用后点进去可能没反应或报错。随时可以「解除深度锁定」恢复。");
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("确认深度锁定")
                                .setMessage(sb.toString())
                                .setNegativeButton("取消", null)
                                .setPositiveButton("执行", new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface d, int w) {
                                        runDisable(targets);
                                    }
                                })
                                .show();
                    }
                });
            }
        }).start();
    }

    private void runDisable(final List<String> targets) {
        toast("正在执行 " + targets.size() + " 个组件，请稍候…");
        new Thread(new Runnable() {
            public void run() {
                // 拼成一条命令一次跑完，比逐个起进程快几十倍
                StringBuilder cmd = new StringBuilder("for c in");
                for (String cls : targets) {
                    cmd.append(' ').append(FinderTargets.WECHAT).append('/').append(cls);
                }
                cmd.append("; do echo \"@$c\"; pm disable-user --user 0 \"$c\" 2>&1; done; ")
                        .append("am force-stop ").append(FinderTargets.WECHAT);

                String out = Sh.run(cmd.toString());

                List<String> okList = new java.util.ArrayList<String>();
                List<String> errList = new java.util.ArrayList<String>();
                String firstErr = "";
                String cur = null;
                boolean curOk = false;
                for (String line : out.split("\n")) {
                    if (line.startsWith("@")) {
                        if (cur != null) {
                            if (curOk) okList.add(cur);
                            else errList.add(cur);
                        }
                        cur = line.substring(1).trim();
                        curOk = false;
                    } else if (cur != null && line.contains("new state: disabled")) {
                        curOk = true;
                    } else if (cur != null && firstErr.length() == 0
                            && (line.contains("SecurityException") || line.contains("Exception occurred"))) {
                        firstErr = line.trim();
                    }
                }
                if (cur != null) {
                    if (curOk) okList.add(cur);
                    else errList.add(cur);
                }

                StringBuilder okSb = new StringBuilder();
                for (String s : okList) okSb.append(s).append('\n');
                Prefs.setLockedList(MainActivity.this, okSb.toString());

                final int okCount = okList.size();
                final int errCount = errList.size();
                final String errSample = firstErr;
                final int total = targets.size();

                ui.post(new Runnable() {
                    public void run() {
                        updateDeepStatus();
                        StringBuilder m = new StringBuilder();
                        m.append("成功 ").append(okCount).append(" / 共 ").append(total).append(" 个\n");
                        if (okCount > 0) {
                            m.append("\n去微信里试一下那个入口。");
                        }
                        if (errCount > 0) {
                            m.append("\n失败 ").append(errCount).append(" 个。");
                            if (errSample.contains("Shell cannot change component state")
                                    || errSample.contains("SecurityException")) {
                                m.append("\n\n原因：这台手机的系统不允许用 shell 权限改动微信的组件状态"
                                        + "（华为改过的 PackageManagerService 主动拒绝）。\n\n"
                                        + "这是系统级限制：Shizuku 拿到的只是 adb 权限（uid 2000），"
                                        + "绕不过去，只有 root 才可能。换电脑 adb 跑结果完全一样。\n\n"
                                        + "好消息：上面那层「跳过服务」已经实测能正常挡住，"
                                        + "深度锁定只是加固，不做也不影响使用。");
                            }
                            if (errSample.length() > 0) {
                                m.append("\n\n首个错误：\n").append(tail(errSample, 400));
                            }
                        }
                        showTextDialog("深度锁定结果", m.toString(), m.toString());
                    }
                });
            }
        }).start();
    }

    private void onDeepUnlockClicked() {
        final String locked = Prefs.lockedList(this);
        if (locked.trim().length() == 0) {
            dialog("没有需要恢复的组件", "本应用没有记录到深度锁定过的组件。");
            return;
        }
        if (!Sh.available() || !Sh.granted()) {
            dialog("Shizuku 不可用",
                    "恢复需要 Shizuku 正在运行并且已授权本应用。\n\n"
                            + "也可以用电脑 adb 跑 unlock-finder.sh 恢复。\n\n"
                            + "当前状态：" + Sh.lastError());
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("解除深度锁定")
                .setMessage("将把之前禁用的组件全部恢复。确定吗？")
                .setNegativeButton("取消", null)
                .setPositiveButton("恢复", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        runEnable(locked);
                    }
                })
                .show();
    }

    private void runEnable(final String locked) {
        toast("正在恢复…");
        new Thread(new Runnable() {
            public void run() {
                StringBuilder cmd = new StringBuilder("for c in");
                for (String cls : locked.trim().split("\n")) {
                    if (cls.length() == 0) continue;
                    cmd.append(' ').append(FinderTargets.WECHAT).append('/').append(cls);
                }
                cmd.append("; do echo \"@$c\"; pm enable \"$c\" 2>&1; done; ")
                        .append("am force-stop ").append(FinderTargets.WECHAT);

                String out = Sh.run(cmd.toString());

                StringBuilder left = new StringBuilder();
                String cur = null;
                boolean curOk = false;
                for (String line : out.split("\n")) {
                    if (line.startsWith("@")) {
                        if (cur != null && !curOk) left.append(cur).append('\n');
                        cur = line.substring(1).trim();
                        curOk = false;
                    } else if (cur != null && line.contains("new state: enabled")) {
                        curOk = true;
                    }
                }
                if (cur != null && !curOk) left.append(cur).append('\n');
                Prefs.setLockedList(MainActivity.this, left.toString());

                final String detail = tail(out, 1500);
                ui.post(new Runnable() {
                    public void run() {
                        updateDeepStatus();
                        showTextDialog("已恢复", "组件已恢复启用。\n\n" + detail, detail);
                    }
                });
            }
        }).start();
    }

    // ---------------------------------------------------------------- 诊断 / adb 通道

    private void showDiag() {
        final String report = Diag.buildReport(this);
        showTextDialog("诊断信息", report, report);
    }

    private void showAdbHelp() {
        String cmd = "adb push lock-finder.sh /data/local/tmp/\n"
                + "adb shell sh /data/local/tmp/lock-finder.sh";
        String msg = "用电脑 adb 做深度锁定（最可靠，推荐先用这个）\n\n"
                + "1. 手机连电脑，确认 adb devices 能看到设备。\n"
                + "2. 把仓库里的 tools/lock-finder.sh 拷到电脑当前目录，然后执行：\n\n"
                + cmd + "\n\n"
                + "脚本会自动找出微信里所有 finder 组件并逐个禁用，最后重启微信。\n\n"
                + "想恢复就跑 tools/unlock-finder.sh，命令一样。\n\n"
                + "这个方式和 App 里的「Shizuku 一键深度锁定」效果完全相同，\n"
                + "而且不依赖 Shizuku 的 binder 推送，一次成功。";
        showTextDialog("用电脑 adb 深度锁定", msg, cmd);
    }

    // ---------------------------------------------------------------- 密码

    private void askPin() {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        et.setHint("管理密码");

        final AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(R.string.app_name)
                .setMessage("请输入管理密码（出厂默认 1234）")
                .setView(et)
                .setCancelable(false)
                .setPositiveButton("确定", null)
                .setNegativeButton("退出", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        finish();
                    }
                })
                .create();
        d.show();
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (Prefs.pin(MainActivity.this).equals(et.getText().toString().trim())) {
                    d.dismiss();
                    refresh();
                } else {
                    et.setError("密码不对");
                    et.setText("");
                }
            }
        });
    }

    private void changePin() {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        et.setHint("新的 4~8 位数字密码");
        new AlertDialog.Builder(this)
                .setTitle("修改管理密码")
                .setView(et)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String v = et.getText().toString().trim();
                        if (v.length() < 4 || v.length() > 8) {
                            toast("密码必须是 4~8 位数字");
                            return;
                        }
                        Prefs.setPin(MainActivity.this, v);
                        toast("密码已更新，请记牢");
                    }
                })
                .show();
    }

    private void showHelp() {
        String s = "【怎么用】\n"
                + "1. 点「开启跳过服务」，在系统列表里打开「跳广告」。\n"
                + "2. 选「退回上一页」还是「直接回桌面」。\n"
                + "   · 退回上一页：从群里点开就立刻回到群聊，从发现页点开就回到发现页。\n"
                + "   · 直接回桌面：先返回把页面弹掉，再回桌面，所以重新打开微信不会又落回去。\n"
                + "   · 连续被顶回来 3 次会自动升级成回桌面。\n\n"
                + "【深度锁定】\n"
                + "把微信 finder 插件里的页面从系统层面禁掉，重启、微信升级、卸载本应用都不解除。\n"
                + "注意：华为/荣耀改过系统，不允许 shell（Shizuku/adb）改动微信组件状态，\n"
                + "这类机型上深度锁定会失败——不影响第一层拦截，可以不管它。\n\n"
                + "【华为/荣耀手机必做】\n"
                + "设置 → 应用 → 应用启动管理 → 跳广告 → 关闭「自动管理」，"
                + "手动勾选「自启动」「关联启动」「后台活动」，否则后台可能被清理。\n\n"
                + "【想全部恢复】\n"
                + "关掉无障碍里的开关即可；如果深度锁定成功过，再点「解除深度锁定」。\n\n"
                + "【排查问题】\n"
                + "点「查看 / 复制诊断信息」，把内容发出来即可定位。\n\n"
                + "【后台常驻（可选）】\n"
                + "默认关闭。如果发现服务老是被系统杀掉，可以打开它，"
                + "代价是通知栏会多一条常驻通知。\n\n"
                + "本工具由 大肥鱼 生成，已开源。";
        dialog("使用说明", s);
    }

    // ---------------------------------------------------------------- 小工具

    private void toast(String s) {
        try {
            Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    private void dialog(String title, String msg) {
        try {
            new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(msg)
                    .setPositiveButton("好", null)
                    .show();
        } catch (Throwable ignored) {
        }
    }

    private void showTextDialog(String title, String text, final String copyPayload) {
        try {
            float d = getResources().getDisplayMetrics().density;
            int pad = (int) (14 * d);

            TextView tv = new TextView(this);
            tv.setText(text);
            tv.setTextSize(11f);
            tv.setTextIsSelectable(true);
            tv.setPadding(pad, pad, pad, pad);

            ScrollView sv = new ScrollView(this);
            sv.addView(tv);

            AlertDialog.Builder b = new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setView(sv)
                    .setPositiveButton("关闭", null);
            if (copyPayload != null) {
                b.setNeutralButton("复制全部", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dlg, int w) {
                        copy(copyPayload);
                    }
                });
            }
            b.show();
        } catch (Throwable t) {
            dialog(title, text);
        }
    }

    private void copy(String s) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("tiaoguanggao", s));
                toast("已复制到剪贴板");
            }
        } catch (Throwable t) {
            toast("复制失败：" + t);
        }
    }

    private static String shortName(String cls) {
        int i = cls.lastIndexOf('.');
        return i < 0 ? cls : cls.substring(i + 1);
    }

    private static String tail(String s, int max) {
        if (s.length() <= max) return s;
        return "…" + s.substring(s.length() - max);
    }
}
