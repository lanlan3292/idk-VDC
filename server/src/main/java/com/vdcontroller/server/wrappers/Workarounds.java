package com.vdcontroller.server.wrappers;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Looper;
import android.os.Process;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

@SuppressLint({"PrivateApi", "DiscouragedPrivateApi", "BlockedPrivateApi"})
public final class Workarounds {

    private static Context context;
    private static Object activityThread;
    private static String resolvedPackage = "com.android.shell";

    private Workarounds() {}

    public static void apply() {
        if (Looper.myLooper() == null) {
            Looper.prepareMainLooper();
        }
        int uid = Process.myUid();
        Ln.i("Process.myUid=" + uid + " myPid=" + Process.myPid());

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

            resolvedPackage = resolvePackageForUid(uid);
            Ln.i("resolvedPackage for uid " + uid + " = " + resolvedPackage);

            fillAppInfo(activityThread, resolvedPackage);
            Ln.i("Workarounds: ready, package=" + resolvedPackage);
        } catch (Exception e) {
            Ln.w("Workarounds.apply failed (non-fatal): " + e);
        }
    }

    private static String resolvePackageForUid(int uid) {
        try {
            if (context != null) {
                PackageManager pm = context.getPackageManager();
                String[] pkgs = pm.getPackagesForUid(uid);
                if (pkgs != null && pkgs.length > 0) {
                    for (String p : pkgs) {
                        Ln.i("uid " + uid + " owns package: " + p);
                    }
                    return pkgs[0];
                }
                Ln.w("getPackagesForUid(" + uid + ") empty");
            }
        } catch (Exception e) {
            Ln.w("resolvePackageForUid: " + e.getMessage());
        }

        if (uid == 2000) return "com.android.shell";
        if (uid == 1000) return "android";
        if (uid == 0) {
            Ln.w("Running as ROOT (uid 0). DisplayManager requires a package owned by this uid.");
            Ln.w("Prefer: adb unroot, then start server as shell (uid 2000).");
            return "android";
        }
        return "com.android.shell";
    }

    private static void fillAppInfo(Object at, String packageName) {
        try {
            Class<?> atClass = at.getClass();
            Class<?> abdClass = Class.forName("android.app.ActivityThread$AppBindData");
            Object abd = allocateInstance(abdClass);

            ApplicationInfo ai = new ApplicationInfo();
            ai.packageName = packageName;
            try {
                Field f = ApplicationInfo.class.getDeclaredField("processName");
                f.setAccessible(true);
                f.set(ai, packageName);
            } catch (Exception ignored) {}

            setField(abd, abdClass, "appInfo", ai);
            setField(abd, abdClass, "processName", packageName);
            setField(at, atClass, "mBoundApplication", abd);

            try {
                Application app = new Application();
                Method attach = Application.class.getDeclaredMethod("attach", Context.class);
                attach.setAccessible(true);
                if (context != null) {
                    Context base = context;
                    try {
                        base = context.createPackageContext(packageName, 0);
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
                Ln.i("currentPackageName=" + cpn.invoke(null));
            } catch (Exception e) {
                Ln.d("currentPackageName: " + e.getMessage());
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
            return unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, clazz);
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
        return resolvedPackage;
    }
}
