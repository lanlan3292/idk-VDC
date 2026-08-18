package com.vdcontroller.server;

import android.annotation.SuppressLint;
import android.content.Context;
import android.hardware.display.VirtualDisplay;
import android.os.Build;
import android.os.Handler;
import android.view.Surface;

import com.vdcontroller.server.wrappers.Ln;
import com.vdcontroller.server.wrappers.ServiceManager;
import com.vdcontroller.server.wrappers.Workarounds;

import java.lang.reflect.Method;

@SuppressLint({"PrivateApi", "BlockedPrivateApi", "DiscouragedPrivateApi"})
public final class VirtualDisplayFactory {

    private static final int FLAG_PUBLIC = 1;
    private static final int FLAG_OWN_CONTENT_ONLY = 1 << 3;
    private static final int FLAG_OWN_FOCUS = 1 << 14;

    private VirtualDisplayFactory() {}

    public static int defaultFlags() {
        int flags = FLAG_PUBLIC | FLAG_OWN_CONTENT_ONLY;
        if (Build.VERSION.SDK_INT >= 34) flags |= FLAG_OWN_FOCUS;
        return flags;
    }

    public static VirtualDisplay create(String name, int w, int h, int density,
                                        Surface surface, int flags, Handler handler) throws Exception {
        Ln.i("VirtualDisplayFactory.create flags=" + flags);

        VirtualDisplay vd = tryContextDisplayManager(name, w, h, density, surface, flags, handler);
        if (vd != null) return vd;

        vd = tryDisplayManagerGlobal(name, w, h, density, surface, flags, handler);
        if (vd != null) return vd;

        vd = tryIDisplayManager(name, w, h, density, surface, flags, handler);
        if (vd != null) return vd;

        if (Build.VERSION.SDK_INT >= 34) {
            vd = tryConfig(name, w, h, density, surface, flags, handler);
            if (vd != null) return vd;
        }

        int simple = FLAG_PUBLIC | FLAG_OWN_CONTENT_ONLY;
        if (flags != simple) {
            Ln.i("Retry with simple flags=" + simple);
            return create(name, w, h, density, surface, simple, handler);
        }

        throw new IllegalStateException("createVirtualDisplay failed on all paths");
    }

    private static VirtualDisplay tryContextDisplayManager(String name, int w, int h, int density,
                                                           Surface surface, int flags, Handler handler) {
        try {
            Context ctx = Workarounds.getDisplayContext();
            if (ctx == null) {
                Ln.w("No display Context from Workarounds");
                return null;
            }
            Ln.i("displayContext.package=" + ctx.getPackageName());
            Object dm = ctx.getSystemService("display");
            if (dm == null) return null;
            Ln.i("Context DisplayManager: " + dm.getClass().getName());
            try {
                java.lang.reflect.Field f = dm.getClass().getDeclaredField("mContext");
                f.setAccessible(true);
                Object oldCtx = f.get(dm);
                String oldPkg = oldCtx instanceof Context ? ((Context) oldCtx).getPackageName() : String.valueOf(oldCtx);
                Ln.i("DM.mContext was package=" + oldPkg);
                f.set(dm, ctx);
                Ln.i("DM.mContext patched to " + ctx.getPackageName());
            } catch (Exception e) {
                Ln.w("patch DM.mContext failed: " + e.getMessage());
            }
            return invokeCreate(dm, name, w, h, density, surface, flags, handler);
        } catch (Exception e) {
            Ln.w("Context DM failed: " + e.getMessage());
            return null;
        }
    }

    private static VirtualDisplay tryIDisplayManager(String name, int w, int h, int density,
                                                     Surface surface, int flags, Handler handler) {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Object binder = sm.getMethod("getService", String.class).invoke(null, "display");
            if (binder == null) {
                Ln.w("IDisplayManager binder null");
                return null;
            }
            Class<?> stub = Class.forName("android.hardware.display.IDisplayManager$Stub");
            Object idm = stub.getMethod("asInterface", android.os.IBinder.class).invoke(null, binder);
            Ln.i("IDisplayManager: " + idm.getClass().getName());

            if (Build.VERSION.SDK_INT >= 30) {
                Class<?> configClass = Class.forName("android.hardware.display.VirtualDisplayConfig");
                Class<?> builderClass = Class.forName("android.hardware.display.VirtualDisplayConfig$Builder");
                Object builder = builderClass.getConstructor(String.class, int.class, int.class, int.class)
                        .newInstance(name, w, h, density);
                builderClass.getMethod("setFlags", int.class).invoke(builder, flags);
                if (surface != null) {
                    builderClass.getMethod("setSurface", Surface.class).invoke(builder, surface);
                }
                Object config = builderClass.getMethod("build").invoke(builder);
                String pkg = Workarounds.getPackageName();
                for (Method m : idm.getClass().getMethods()) {
                    if (!"createVirtualDisplay".equals(m.getName())) continue;
                    Class<?>[] p = m.getParameterTypes();
                    Ln.i("IDisplayManager.createVirtualDisplay params=" + java.util.Arrays.toString(p));
                    try {
                        Object[] args = new Object[p.length];
                        for (int i = 0; i < p.length; i++) {
                            if (p[i] == configClass) args[i] = config;
                            else if (p[i] == String.class) args[i] = pkg;
                            else if (p[i] == Surface.class) args[i] = surface;
                            else if (p[i] == int.class || p[i] == Integer.TYPE) args[i] = 0;
                            else args[i] = null;
                        }
                        Object result = m.invoke(idm, args);
                        Ln.i("IDisplayManager result=" + result);
                        if (result instanceof Integer) {
                            int displayId = (Integer) result;
                            if (displayId >= 0) {
                                Ln.i("IDisplayManager created displayId=" + displayId);
                                return wrapDisplayId(displayId, surface);
                            }
                        }
                        if (result instanceof VirtualDisplay) return (VirtualDisplay) result;
                    } catch (Exception e) {
                        Throwable c = e.getCause() != null ? e.getCause() : e;
                        Ln.w("IDisplayManager invoke failed: " + c);
                    }
                }
            }
        } catch (Exception e) {
            Ln.w("tryIDisplayManager failed: " + e);
        }
        return null;
    }

    private static VirtualDisplay wrapDisplayId(int displayId, Surface surface) {
        Ln.w("wrapDisplayId not fully implemented for id=" + displayId);
        return null;
    }

    private static VirtualDisplay tryDisplayManagerGlobal(String name, int w, int h, int density,
                                                          Surface surface, int flags, Handler handler) {
        try {
            Object dmg = ServiceManager.getDisplayManagerGlobal();
            Ln.i("DisplayManagerGlobal: " + dmg.getClass().getName());
            return invokeCreate(dmg, name, w, h, density, surface, flags, handler);
        } catch (Exception e) {
            Ln.w("DMG failed: " + e.getMessage());
            return null;
        }
    }

    private static VirtualDisplay tryStringVariants(Object target, Method m, String name, int w, int h,
                                                   int density, Surface surface, int flags, Handler handler) {
        Class<?>[] p = m.getParameterTypes();
        String[] packages = new String[] {
            Workarounds.getPackageName(),
            "com.android.shell",
            null
        };
        String[] uniqueIds = new String[] { "vdcontroller", name, null };
        for (String pkg : packages) {
            for (String uid : uniqueIds) {
                try {
                    Object[] args = new Object[p.length];
                    args[0] = name; args[1] = w; args[2] = h; args[3] = density;
                    args[4] = surface; args[5] = flags;
                    int sIdx = 0;
                    for (int i = 6; i < p.length; i++) {
                        if (Handler.class.isAssignableFrom(p[i])) args[i] = handler;
                        else if (p[i] == String.class) {
                            args[i] = (sIdx == 0) ? uid : pkg;
                            sIdx++;
                        } else if (p[i] == int.class || p[i] == Integer.TYPE) args[i] = 0;
                        else if (p[i] == boolean.class || p[i] == Boolean.TYPE) args[i] = false;
                        else args[i] = null;
                    }
                    m.setAccessible(true);
                    Object result = m.invoke(target, args);
                    if (result instanceof VirtualDisplay) {
                        Ln.i("OK variant pkg=" + pkg + " uniqueId=" + uid);
                        return (VirtualDisplay) result;
                    }
                } catch (Exception e) {
                    Throwable c = e.getCause() != null ? e.getCause() : e;
                    Ln.d("variant fail pkg=" + pkg + ": " + c.getClass().getSimpleName());
                }
            }
        }
        return null;
    }

    private static VirtualDisplay invokeCreate(Object target, String name, int w, int h, int density,
                                               Surface surface, int flags, Handler handler) {
        for (Method m : target.getClass().getMethods()) {
            if (!"createVirtualDisplay".equals(m.getName())) continue;
            Class<?>[] p = m.getParameterTypes();
            try {
                m.setAccessible(true);
                Object result = null;
                if (p.length == 6 && p[0] == String.class && p[4] == Surface.class) {
                    result = m.invoke(target, name, w, h, density, surface, flags);
                } else if (p.length == 8 && p[0] == String.class && p[4] == Surface.class) {
                    result = m.invoke(target, name, w, h, density, surface, flags, null, handler);
                } else if (p.length >= 6 && p[0] == String.class) {
                    VirtualDisplay vd = tryStringVariants(target, m, name, w, h, density, surface, flags, handler);
                    if (vd != null) return vd;
                    result = null;
                } else {
                    continue;
                }
                if (result instanceof VirtualDisplay) {
                    Ln.i("OK via " + target.getClass().getSimpleName() + " " + m.getName());
                    return (VirtualDisplay) result;
                }
                if (result != null) {
                    Ln.w("Non-VD return: " + result.getClass().getName());
                } else if (p.length <= 8) {
                    Ln.w("null from " + m.toGenericString());
                }
            } catch (Exception e) {
                Throwable c = e.getCause() != null ? e.getCause() : e;
                Ln.w("invoke failed: " + c);
            }
        }
        for (Method m : target.getClass().getDeclaredMethods()) {
            if (!"createVirtualDisplay".equals(m.getName())) continue;
            try {
                m.setAccessible(true);
                Class<?>[] p = m.getParameterTypes();
                if (p.length < 6 || p[0] != String.class) continue;
                if (p.length > 8) {
                    VirtualDisplay vd = tryStringVariants(target, m, name, w, h, density, surface, flags, handler);
                    if (vd != null) return vd;
                    continue;
                }
                Object[] args = new Object[p.length];
                args[0] = name; args[1] = w; args[2] = h; args[3] = density;
                args[4] = surface; args[5] = flags;
                for (int i = 6; i < p.length; i++) {
                    if (Handler.class.isAssignableFrom(p[i])) args[i] = handler;
                    else if (p[i] == String.class) args[i] = Workarounds.getPackageName();
                    else if (p[i] == int.class || p[i] == Integer.TYPE) args[i] = 0;
                    else if (p[i] == boolean.class || p[i] == Boolean.TYPE) args[i] = false;
                    else args[i] = null;
                }
                Object result = m.invoke(target, args);
                if (result instanceof VirtualDisplay) {
                    Ln.i("OK declared " + m.toGenericString());
                    return (VirtualDisplay) result;
                }
            } catch (Exception e) {
                Throwable c = e.getCause() != null ? e.getCause() : e;
                Ln.w("declared invoke failed: " + c);
            }
        }
        return null;
    }

    private static VirtualDisplay tryConfig(String name, int w, int h, int density,
                                            Surface surface, int flags, Handler handler) {
        try {
            Class<?> configClass = Class.forName("android.hardware.display.VirtualDisplayConfig");
            Class<?> builderClass = Class.forName("android.hardware.display.VirtualDisplayConfig$Builder");
            Object builder = builderClass.getConstructor(String.class, int.class, int.class, int.class)
                    .newInstance(name, w, h, density);
            builderClass.getMethod("setFlags", int.class).invoke(builder, flags);
            if (surface != null) {
                builderClass.getMethod("setSurface", Surface.class).invoke(builder, surface);
            }
            Object config = builderClass.getMethod("build").invoke(builder);

            Context ctx = Workarounds.getDisplayContext();
            Object target = ctx != null ? ctx.getSystemService("display") : null;
            if (target == null) target = ServiceManager.getDisplayManagerGlobal();

            for (Method m : target.getClass().getMethods()) {
                if (!"createVirtualDisplay".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length < 1 || p[0] != configClass) continue;
                Object[] args = new Object[p.length];
                args[0] = config;
                for (int i = 1; i < p.length; i++) {
                    if (Handler.class.isAssignableFrom(p[i])) args[i] = handler;
                    else if (p[i] == String.class) args[i] = Workarounds.getPackageName();
                    else args[i] = null;
                }
                Object result = m.invoke(target, args);
                if (result instanceof VirtualDisplay) {
                    Ln.i("OK VirtualDisplayConfig");
                    return (VirtualDisplay) result;
                }
            }
        } catch (Exception e) {
            Ln.w("tryConfig failed: " + e.getMessage());
        }
        return null;
    }
}
