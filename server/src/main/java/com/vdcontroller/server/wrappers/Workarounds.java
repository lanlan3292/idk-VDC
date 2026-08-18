package com.vdcontroller.server.wrappers;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Looper;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Bootstrap ActivityThread + AppBindData (via Unsafe) so packageName matches shell UID.
 * Same approach as scrcpy Workarounds.
 */
@SuppressLint({"PrivateApi", "DiscouragedPrivateApi", "BlockedPrivateApi"})
public final class Workarounds {

    private static final String FAKE_PACKAGE = "com.android.shell";

    private static Context context;
    private static Object activityThread;

    private Workarounds() {}

    public static void apply() {
        if (Looper.myLooper() == null) {
            Looper.prepareMainLooper();
        }
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Method systemMain = atClass.getDeclaredMethod("systemMain");
            systemMain.setAccessible(true);
            activityThread = systemMain.invoke(null);

            Method getSystemContext = atClass.getDeclaredMethod("getSystemContext");
            getSystemContext.setAccessible(true);
            context = (Context) getSystemContext.invoke(activityThread);

            try {
                Field sCurrent = atClass.getDeclaredField("sCurrentActivityThread");
                sCurrent.setAccessible(true);
                sCurrent.set(null, activityThread);
            } catch (Exception e) {
                Ln.d("sCurrentActivityThread: " + e.getMessage());
            }

            fillAppInfo(activityThread);
            Ln.i("Workarounds: ready, package=" + FAKE_PACKAGE);
        } catch (Exception e) {
            Ln.w("Workarounds.apply failed (non-fatal): " + e);
        }
    }

    private static void fillAppInfo(Object at) {
        try {
            Class<?> atClass = at.getClass();
            Class<?> abdClass = Class.forName("android.app.ActivityThread$AppBindData");

            Object abd = allocateInstance(abdClass);

            ApplicationInfo ai = new ApplicationInfo();
            ai.packageName = FAKE_PACKAGE;
            try {
                Field f = ApplicationInfo.class.getDeclaredField("processName");
                f.setAccessible(true);
                f.set(ai, FAKE_PACKAGE);
            } catch (Exception ignored) {}

            setField(abd, abdClass, "appInfo", ai);
            setField(abd, abdClass, "processName", FAKE_PACKAGE);
            setField(at, atClass, "mBoundApplication", abd);

            try {
                Application app = new Application();
                Method attach = Application.class.getDeclaredMethod("attach", Context.class);
                attach.setAccessible(true);
                if (context != null) {
                    Context base = context;
                    try {
                        base = context.createPackageContext(FAKE_PACKAGE, Context.CONTEXT_INCLUDE_CODE);
                    } catch (Exception e) {
                        Ln.d("createPackageContext: " + e.getMessage());
                    }
                    attach.invoke(app, base);
                }
                setField(at, atClass, "mInitialApplication", app);
            } catch (Exception e) {
                Ln.d("Application attach: " + e.getMessage());
            }

            try {
                Method cpn = atClass.getDeclaredMethod("currentPackageName");
                cpn.setAccessible(true);
                Object pkg = cpn.invoke(null);
                Ln.i("currentPackageName=" + pkg);
            } catch (Exception e) {
                Ln.d("currentPackageName check: " + e.getMessage());
            }

            Ln.i("fillAppInfo done");
        } catch (Exception e) {
            Ln.w("fillAppInfo failed: " + e);
        }
    }

    private static Object allocateInstance(Class<?> clazz) throws Exception {
        try {
            Constructor<?> c = clazz.getDeclaredConstructor();
            c.setAccessible(true);
            return c.newInstance();
        } catch (Exception ignored) {}

        try {
            Class<?> unsafeClass;
            try {
                unsafeClass = Class.forName("sun.misc.Unsafe");
            } catch (ClassNotFoundException e) {
                unsafeClass = Class.forName("jdk.internal.misc.Unsafe");
            }
            Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Object unsafe = theUnsafe.get(null);
            Method allocateInstance = unsafeClass.getMethod("allocateInstance", Class.class);
            return allocateInstance.invoke(unsafe, clazz);
        } catch (Exception e) {
            Ln.w("Unsafe.allocateInstance failed: " + e);
        }

        throw new IllegalAccessException("Cannot allocate " + clazz.getName());
    }

    private static void setField(Object obj, Class<?> clazz, String name, Object value) throws Exception {
        Field f = null;
        Class<?> c = clazz;
        while (c != null) {
            try {
                f = c.getDeclaredField(name);
                break;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        if (f == null) throw new NoSuchFieldException(name);
        f.setAccessible(true);
        f.set(obj, value);
    }

    public static Context getContext() {
        if (context == null) apply();
        return context;
    }

    public static String getPackageName() {
        return FAKE_PACKAGE;
    }
}
