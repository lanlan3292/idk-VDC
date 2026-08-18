package com.vdcontroller.server;

import android.annotation.SuppressLint;
import android.hardware.display.VirtualDisplay;
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
        inputInjector = new InputManagerWrapper();
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

        int flags = VirtualDisplayFactory.defaultFlags();
        Ln.i("create() flags=" + flags + " surface=" + (targetSurface != null));

        try {
            virtualDisplay = VirtualDisplayFactory.create(DISPLAY_NAME, width, height, dpi,
                    targetSurface, flags, callbackHandler);
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
            String resolve = "cmd package resolve-activity --brief "
                    + "-a android.intent.action.MAIN "
                    + "-c android.intent.category.LAUNCHER "
                    + packageName;
            Process rp = Runtime.getRuntime().exec(new String[]{"sh", "-c", resolve});
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(rp.getInputStream()));
            String line;
            String component = null;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.contains("/") && !line.startsWith("priority=") && !line.startsWith("No activity")) {
                    component = line;
                }
            }
            rp.waitFor();

            String cmd;
            if (component != null && component.contains("/")) {
                cmd = "am start --display " + displayId + " -n " + component;
                Ln.i("launchApp component=" + component);
            } else {
                cmd = "am start --display " + displayId
                        + " -a android.intent.action.MAIN"
                        + " -c android.intent.category.LAUNCHER"
                        + " -p " + packageName;
                Ln.i("launchApp package fallback " + packageName);
            }

            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            int code = p.waitFor();
            java.io.BufferedReader er = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getErrorStream()));
            String el;
            while ((el = er.readLine()) != null) {
                Ln.w("am: " + el);
            }
            Ln.i("launchApp " + packageName + " exit=" + code);

            if (code != 0) {
                String cmd2 = "monkey --display-id " + displayId
                        + " -p " + packageName
                        + " -c android.intent.category.LAUNCHER 1";
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
