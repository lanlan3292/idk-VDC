package com.vdcontroller.server;

import android.annotation.SuppressLint;
import android.hardware.display.VirtualDisplay;
import android.hardware.input.InputManager;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import com.vdcontroller.server.wrappers.InputManagerWrapper;
import com.vdcontroller.server.wrappers.Ln;
import com.vdcontroller.server.wrappers.ServiceManager;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Creates / manages a VirtualDisplay and injects input into it.
 * Runs under shell UID (via app_process or Shizuku).
 */
@SuppressLint({"PrivateApi", "BlockedPrivateApi", "DiscouragedPrivateApi"})
public class VirtualDisplayController {

    private static final String DISPLAY_NAME = "VD-Controller";

    private static final int FLAG_PUBLIC = 1;
    private static final int FLAG_OWN_CONTENT_ONLY = 1 << 3;
    private static final int FLAG_SUPPORTS_TOUCH = 1 << 6;
    private static final int FLAG_OWN_FOCUS = 1 << 14;
    private static final int FLAG_DEVICE_DISPLAY_GROUP = 1 << 15;

    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private int displayId = -1;
    private int width;
    private int height;
    private int dpi;

    private final InputManagerWrapper inputInjector;
    private HandlerThread callbackThread;
    private Handler callbackHandler;
    private Surface externalSurface;

    public VirtualDisplayController() {
        InputManager im = ServiceManager.getInputManager();
        inputInjector = new InputManagerWrapper(im);
    }

    public synchronized int create(int width, int height, int dpi, Surface surface) {
        if (virtualDisplay != null) {
            destroy();
        }
        this.width = width;
        this.height = height;
        this.dpi = dpi;
        this.externalSurface = surface;

        callbackThread = new HandlerThread("VD-Callback");
        callbackThread.start();
        callbackHandler = new Handler(callbackThread.getLooper());

        Surface targetSurface = surface;
        if (targetSurface == null) {
            imageReader = ImageReader.newInstance(width, height, 0x1, 2);
            targetSurface = imageReader.getSurface();
        }

        int flags = FLAG_PUBLIC | FLAG_OWN_CONTENT_ONLY | FLAG_SUPPORTS_TOUCH;
        if (Build.VERSION.SDK_INT >= 34) {
            flags |= FLAG_OWN_FOCUS | FLAG_DEVICE_DISPLAY_GROUP;
        }

        try {
            virtualDisplay = createVirtualDisplay(DISPLAY_NAME, width, height, dpi,
                    targetSurface, flags);
            if (virtualDisplay != null) {
                displayId = virtualDisplay.getDisplay().getDisplayId();
                Ln.i("VirtualDisplay created: " + width + "x" + height + "/" + dpi
                        + " id=" + displayId);
            } else {
                Ln.e("createVirtualDisplay returned null");
                displayId = -1;
            }
        } catch (Exception e) {
            Ln.e("Failed to create VirtualDisplay", e);
            displayId = -1;
            releaseResources();
        }
        return displayId;
    }

    private VirtualDisplay createVirtualDisplay(String name, int w, int h, int density,
                                                Surface surface, int flags) throws Exception {
        Object dmg = ServiceManager.getDisplayManagerGlobal();
        Class<?> dmgClass = dmg.getClass();

        for (Method m : dmgClass.getDeclaredMethods()) {
            if (!"createVirtualDisplay".equals(m.getName())) continue;
            m.setAccessible(true);
            Class<?>[] p = m.getParameterTypes();

            try {
                Object result = null;

                if (p.length == 6
                        && p[0] == String.class && p[1] == int.class
                        && p[4] == Surface.class && p[5] == int.class) {
                    result = m.invoke(dmg, name, w, h, density, surface, flags);
                } else if (p.length == 8
                        && p[0] == String.class && p[4] == Surface.class) {
                    result = m.invoke(dmg, name, w, h, density, surface, flags,
                            null, callbackHandler);
                } else if (p.length >= 9 && p[0] == String.class) {
                    Object[] args = new Object[p.length];
                    args[0] = name;
                    args[1] = w;
                    args[2] = h;
                    args[3] = density;
                    args[4] = surface;
                    args[5] = flags;
                    for (int i = 6; i < p.length; i++) {
                        if (p[i] == String.class) {
                            args[i] = "com.android.shell";
                        } else if (p[i] == int.class || p[i] == Integer.TYPE) {
                            args[i] = 0;
                        } else if (p[i] == boolean.class || p[i] == Boolean.TYPE) {
                            args[i] = false;
                        } else if (Handler.class.isAssignableFrom(p[i])) {
                            args[i] = callbackHandler;
                        } else {
                            args[i] = null;
                        }
                    }
                    result = m.invoke(dmg, args);
                } else {
                    continue;
                }

                if (result instanceof VirtualDisplay) {
                    Ln.i("createVirtualDisplay via " + m.toGenericString());
                    return (VirtualDisplay) result;
                }

                if (result != null) {
                    VirtualDisplay vd = wrapToken(result, name, w, h, density, surface);
                    if (vd != null) return vd;
                    Ln.w("Unhandled return type: " + result.getClass().getName());
                }
            } catch (Exception e) {
                Ln.d("Overload failed: " + m.getName() + " -> " + e.getCause());
            }
        }

        if (Build.VERSION.SDK_INT >= 34) {
            try {
                return createWithConfig(name, w, h, density, surface, flags);
            } catch (Exception e) {
                Ln.w("VirtualDisplayConfig path failed: " + e.getMessage());
            }
        }

        throw new IllegalStateException("No usable createVirtualDisplay method found on this device");
    }

    private VirtualDisplay createWithConfig(String name, int w, int h, int density,
                                            Surface surface, int flags) throws Exception {
        Class<?> configClass = Class.forName("android.hardware.display.VirtualDisplayConfig");
        Class<?> builderClass = Class.forName("android.hardware.display.VirtualDisplayConfig$Builder");

        Constructor<?> builderCtor = builderClass.getConstructor(String.class, int.class, int.class, int.class);
        Object builder = builderCtor.newInstance(name, w, h, density);

        builderClass.getMethod("setFlags", int.class).invoke(builder, flags);
        if (surface != null) {
            builderClass.getMethod("setSurface", Surface.class).invoke(builder, surface);
        }
        Object config = builderClass.getMethod("build").invoke(builder);

        Object dmg = ServiceManager.getDisplayManagerGlobal();
        for (Method m : dmg.getClass().getDeclaredMethods()) {
            if (!"createVirtualDisplay".equals(m.getName())) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length >= 1 && p[0] == configClass) {
                m.setAccessible(true);
                Object[] args = new Object[p.length];
                args[0] = config;
                for (int i = 1; i < p.length; i++) {
                    if (Handler.class.isAssignableFrom(p[i])) args[i] = callbackHandler;
                    else if (p[i] == String.class) args[i] = "com.android.shell";
                    else args[i] = null;
                }
                Object result = m.invoke(dmg, args);
                if (result instanceof VirtualDisplay) return (VirtualDisplay) result;
            }
        }
        return null;
    }

    private VirtualDisplay wrapToken(Object token, String name, int w, int h,
                                     int density, Surface surface) {
        try {
            for (Constructor<?> c : VirtualDisplay.class.getDeclaredConstructors()) {
                c.setAccessible(true);
                Class<?>[] p = c.getParameterTypes();
                if (p.length >= 1 && p[0].isInstance(token)) {
                    Object[] args = new Object[p.length];
                    args[0] = token;
                    for (int i = 1; i < p.length; i++) {
                        if (p[i] == String.class) args[i] = name;
                        else if (p[i] == int.class) args[i] = (i == 1 ? w : (i == 2 ? h : density));
                        else if (p[i] == Surface.class) args[i] = surface;
                        else args[i] = null;
                    }
                    return (VirtualDisplay) c.newInstance(args);
                }
            }
        } catch (Exception e) {
            Ln.d("wrapToken failed: " + e.getMessage());
        }
        return null;
    }

    public synchronized void setSurface(Surface surface) {
        this.externalSurface = surface;
        if (virtualDisplay != null && surface != null) {
            virtualDisplay.setSurface(surface);
            Ln.i("Surface updated");
        }
    }

    public synchronized void resize(int width, int height, int dpi) {
        if (virtualDisplay == null) return;
        this.width = width;
        this.height = height;
        this.dpi = dpi;
        virtualDisplay.resize(width, height, dpi);
        Ln.i("Resized to " + width + "x" + height + "/" + dpi);
    }

    public synchronized void destroy() {
        if (virtualDisplay != null) {
            try {
                virtualDisplay.release();
            } catch (Exception e) {
                Ln.w("release error: " + e.getMessage());
            }
            virtualDisplay = null;
        }
        releaseResources();
        displayId = -1;
        Ln.i("VirtualDisplay destroyed");
    }

    private void releaseResources() {
        if (imageReader != null) {
            try { imageReader.close(); } catch (Exception ignored) {}
            imageReader = null;
        }
        if (callbackThread != null) {
            callbackThread.quitSafely();
            callbackThread = null;
            callbackHandler = null;
        }
        externalSurface = null;
    }

    public int getDisplayId() { return displayId; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public int getDpi() { return dpi; }

    public boolean isActive() {
        return virtualDisplay != null && displayId >= 0;
    }

    public boolean injectTouch(int action, float x, float y, int pointerId,
                               float pressure, long downTime) {
        if (!isActive()) return false;
        x = Math.max(0, Math.min(width - 1, x));
        y = Math.max(0, Math.min(height - 1, y));
        return inputInjector.injectTouch(action, x, y, pointerId, pressure, downTime, displayId);
    }

    public boolean injectScroll(float x, float y, float hScroll, float vScroll) {
        if (!isActive()) return false;
        return inputInjector.injectScroll(x, y, hScroll, vScroll, displayId);
    }

    public boolean injectKey(int keyCode) {
        if (!isActive()) return false;
        return inputInjector.pressKey(keyCode, displayId);
    }

    public boolean launchApp(String packageName) {
        if (!isActive()) return false;
        try {
            String cmd = "am start --display " + displayId
                    + " -a android.intent.action.MAIN"
                    + " -c android.intent.category.LAUNCHER "
                    + packageName;
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            int code = p.waitFor();
            Ln.i("launchApp " + packageName + " exit=" + code);
            if (code != 0) {
                String cmd2 = "monkey --display-id " + displayId
                        + " -p " + packageName + " -c android.intent.category.LAUNCHER 1";
                Process p2 = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd2});
                code = p2.waitFor();
                Ln.i("launchApp via monkey exit=" + code);
            }
            return code == 0;
        } catch (Exception e) {
            Ln.e("launchApp failed", e);
            return false;
        }
    }

    public boolean launchAppComponent(String component) {
        if (!isActive()) return false;
        try {
            String cmd = "am start --display " + displayId + " -n " + component;
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            int code = p.waitFor();
            Ln.i("launchComponent " + component + " exit=" + code);
            return code == 0;
        } catch (Exception e) {
            Ln.e("launchComponent failed", e);
            return false;
        }
    }
}
