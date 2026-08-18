package com.vdcontroller.server.wrappers;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Looper;

import java.lang.reflect.Method;

/**
 * Bootstrap ActivityThread so some system APIs work under app_process.
 */
@SuppressLint({"PrivateApi", "DiscouragedPrivateApi", "BlockedPrivateApi"})
public final class Workarounds {

    private static Context context;

    private Workarounds() {}

    public static void apply() {
        if (context != null) return;
        try {
            if (Looper.myLooper() == null) {
                Looper.prepareMainLooper();
            }
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Method systemMain = atClass.getDeclaredMethod("systemMain");
            systemMain.setAccessible(true);
            Object activityThread = systemMain.invoke(null);

            Method getSystemContext = atClass.getDeclaredMethod("getSystemContext");
            getSystemContext.setAccessible(true);
            context = (Context) getSystemContext.invoke(activityThread);
            Ln.i("Workarounds: ActivityThread system context ready");
        } catch (Exception e) {
            Ln.w("Workarounds.apply failed (non-fatal): " + e.getMessage());
            try {
                Class<?> atClass = Class.forName("android.app.ActivityThread");
                Method current = atClass.getDeclaredMethod("currentActivityThread");
                Object at = current.invoke(null);
                if (at != null) {
                    Method getSystemContext = atClass.getDeclaredMethod("getSystemContext");
                    context = (Context) getSystemContext.invoke(at);
                }
            } catch (Exception e2) {
                Ln.w("Workarounds fallback failed (non-fatal): " + e2.getMessage());
            }
        }
    }

    public static Context getContext() {
        if (context == null) apply();
        return context;
    }
}
