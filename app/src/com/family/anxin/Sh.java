package com.family.anxin;

import android.os.ParcelFileDescriptor;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;

/**
 * 通过 Shizuku 拿到 shell 权限执行命令（等价于 adb shell）。
 * Shizuku 13 把 newProcess 收进了 IShizukuService，这里走 binder 调用。
 */
public final class Sh {

    private static volatile String lastError = "无";

    private Sh() {
    }

    public static String lastError() {
        return lastError;
    }

    private static void note(String where, Throwable t) {
        lastError = where + ": " + t;
    }

    /** 是否已经收到 Shizuku 的 binder（这是所有其它调用能否成功的前提）。 */
    public static boolean binderReceived() {
        try {
            return Shizuku.getBinder() != null;
        } catch (Throwable t) {
            note("getBinder", t);
            return false;
        }
    }

    public static boolean available() {
        try {
            boolean ok = Shizuku.pingBinder();
            if (!ok) lastError = "pingBinder 返回 false（binder 未收到或已断开）";
            return ok;
        } catch (Throwable t) {
            note("pingBinder", t);
            return false;
        }
    }

    public static boolean granted() {
        try {
            if (Shizuku.isPreV11()) {
                lastError = "Shizuku 版本低于 v11，不支持";
                return false;
            }
            boolean ok = Shizuku.checkSelfPermission()
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
            if (!ok) lastError = "checkSelfPermission 返回未授权";
            return ok;
        } catch (Throwable t) {
            note("checkSelfPermission", t);
            return false;
        }
    }

    public static int version() {
        try {
            return Shizuku.getVersion();
        } catch (Throwable t) {
            note("getVersion", t);
            return -1;
        }
    }

    public static int uid() {
        try {
            return Shizuku.getUid();
        } catch (Throwable t) {
            note("getUid", t);
            return -1;
        }
    }

    /** 同步执行，返回 stdout+stderr 合并结果。必须在子线程调用。 */
    public static String run(String cmd) {
        StringBuilder sb = new StringBuilder();
        IRemoteProcess rp = null;
        InputStream in = null;
        try {
            IShizukuService service =
                    IShizukuService.Stub.asInterface(new ShizukuBinderWrapper(Shizuku.getBinder()));
            rp = service.newProcess(new String[]{"sh", "-c", cmd + " 2>&1"}, null, null);
            in = new ParcelFileDescriptor.AutoCloseInputStream(rp.getInputStream());
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            r.close();
            rp.waitFor();
        } catch (Throwable t) {
            note("run(" + cmd + ")", t);
            sb.append("ERR: ").append(t).append('\n');
        } finally {
            try {
                if (in != null) in.close();
            } catch (Throwable ignored) {
            }
            try {
                if (rp != null) rp.destroy();
            } catch (Throwable ignored) {
            }
        }
        return sb.toString();
    }

    public static String disable(String component) {
        return run("pm disable-user --user 0 " + component);
    }

    public static String enable(String component) {
        return run("pm enable " + component);
    }
}
