package com.vdcontroller.server.wrappers;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Looper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Bootstrap ActivityThread + fake AppBindData so DisplayManager accepts our packageName.
 * Mirrors scrcpy Workarounds.fillAppInfo().
 */
@SuppressLint({"PrivateApi", "DiscouragedPrivateApi", "BlockedPrivateApi"})
public final class Workarounds {

    private static final String FAKE_PACKAGE = "com.android.shell";

    private static Context context;

    private Workarounds() {}

    public static void apply() {
        if (Looper.myLooper() == null) {
            Looper.prepareMainLooper();
        }
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Method systemMain = atClass.getDeclaredMethod("systemMain");
            systemMain.setAccessible(true);
            Object activityThread = systemMain.invoke(null);

            Method getSystemContext = atClass.getDeclaredMethod("getSystemContext");
            getSystemContext.setAccessible(true);
            context = (Context) getSystemContext.invoke(activityThread);

            fillAppInfo(activityThread);
            Ln.i("Workarounds: ready, package=" + FAKE_PACKAGE);
        } catch (Exception e) {
            Ln.w("Workarounds.apply failed (non-fatal): " + e);
            try {
                Class<?> atClass = Class.forName("android.app.ActivityThread");
                Object at = atClass.getDeclaredMethod("currentActivityThread").invoke(null);
                if (at != null) {
                    context = (Context) atClass.getDeclaredMethod("getSystemContext").invoke(at);
                    fillAppInfo(at);
                }
            } catch (Exception e2) {
                Ln.w("Workarounds fallback failed: " + e2.getMessage());
            }
        }
    }

    private static void fillAppInfo(Object activityThread) {
        try {
            Class<?> atClass = activityThread.getClass();

            Class<?> abdClass = Class.forName("android.app.ActivityThread$AppBindData");
            Object abd = abdClass.getDeclaredConstructor().newInstance();

            ApplicationInfo ai = new ApplicationInfo();
            ai.packageName = FAKE_PACKAGE;
            try {
                Field processName = ApplicationInfo.class.getDeclaredField("processName");
                processName.setAccessible(true);
                processName.set(ai, FAKE_PACKAGE);
            } catch (Exception ignored) {}

            Field infoField = abdClass.getDeclaredField("appInfo");
            infoField.setAccessible(true);
            infoField.set(abd, ai);

            try {
                Field pn = abdClass.getDeclaredField("processName");
                pn.setAccessible(true);
                pn.set(abd, FAKE_PACKAGE);
            } catch (Exception ignored) {}

            Field bound = atClass.getDeclaredField("mBoundApplication");
            bound.setAccessible(true);
            bound.set(activityThread, abd);

            try {
                Application app = new Application();
                Method attach = Application.class.getDeclaredMethod("attach", Context.class);
                attach.setAccessible(true);
                if (context != null) {
                    attach.invoke(app, context);
                }
                Field mInitialApplication = atClass.getDeclaredField("mInitialApplication");
                mInitialApplication.setAccessible(true);
                mInitialApplication.set(activityThread, app);
            } catch (Exception e) {
                Ln.d("attach Application skipped: " + e.getMessage());
            }

            Ln.i("fillAppInfo done");
        } catch (Exception e) {
            Ln.w("fillAppInfo failed: " + e);
        }
    }

    public static Context getContext() {
        if (context == null) apply();
        return context;
    }

    public static String getPackageName() {
        return FAKE_PACKAGE;
    }
}
