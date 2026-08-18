package com.vdcontroller.server.wrappers;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.ApplicationInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Bootstrap ActivityThread / fake Application so system services work under app_process.
 * Same idea as scrcpy Workarounds.
 */
@SuppressLint({"PrivateApi", "DiscouragedPrivateApi", "BlockedPrivateApi"})
public final class Workarounds {

    private static Context context;

    private Workarounds() {}

    public static void apply() {
        if (context != null) return;
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Method systemMain = atClass.getDeclaredMethod("systemMain");
            systemMain.setAccessible(true);
            Object activityThread = systemMain.invoke(null);

            Method getSystemContext = atClass.getDeclaredMethod("getSystemContext");
            getSystemContext.setAccessible(true);
            Context ctx = (Context) getSystemContext.invoke(activityThread);

            ApplicationInfo ai = new ApplicationInfo();
            ai.packageName = "com.android.shell";
            try {
                Field pkgInfo = ctx.getClass().getDeclaredField("mPackageInfo");
            } catch (Exception ignored) {}

            context = ctx;
            Ln.i("Workarounds: ActivityThread system context ready");
        } catch (Exception e) {
            Ln.e("Workarounds.apply failed", e);
            try {
                Class<?> atClass = Class.forName("android.app.ActivityThread");
                Method current = atClass.getDeclaredMethod("currentActivityThread");
                Object at = current.invoke(null);
                if (at != null) {
                    Method getSystemContext = atClass.getDeclaredMethod("getSystemContext");
                    context = (Context) getSystemContext.invoke(at);
                }
            } catch (Exception e2) {
                Ln.e("Workarounds fallback failed", e2);
            }
        }
    }

    public static Context getContext() {
        if (context == null) apply();
        return context;
    }
}
