package com.family.anxin;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null) return;
        // 后台常驻默认关闭，没主动开过就不要在开机时拉起前台服务
        if (!Prefs.foreground(context)) return;
        try {
            Intent s = new Intent(context, GuardService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(s);
            } else {
                context.startService(s);
            }
        } catch (Throwable ignored) {
        }
    }
}
