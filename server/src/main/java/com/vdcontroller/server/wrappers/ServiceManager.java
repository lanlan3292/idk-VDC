package com.vdcontroller.server.wrappers;

import android.annotation.SuppressLint;
import android.hardware.input.InputManager;
import android.os.IBinder;
import android.os.IInterface;

import java.lang.reflect.Method;

/**
 * Reflection-based access to system services (scrcpy pattern).
 */
@SuppressLint("PrivateApi")
public final class ServiceManager {

    private static final Method GET_SERVICE_METHOD;

    static {
        try {
            GET_SERVICE_METHOD = Class.forName("android.os.ServiceManager")
                    .getDeclaredMethod("getService", String.class);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static InputManager inputManager;
    private static Object displayManagerGlobal;
    private static Object windowManager;

    private ServiceManager() {}

    public static IInterface getService(String service, String type) {
        try {
            IBinder binder = (IBinder) GET_SERVICE_METHOD.invoke(null, service);
            Method asInterface = Class.forName(type + "$Stub")
                    .getMethod("asInterface", IBinder.class);
            return (IInterface) asInterface.invoke(null, binder);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    public static Object getDisplayManagerGlobal() {
        if (displayManagerGlobal == null) {
            try {
                Class<?> dmgClass = Class.forName("android.hardware.display.DisplayManagerGlobal");
                Method getInstance = dmgClass.getMethod("getInstance");
                displayManagerGlobal = getInstance.invoke(null);
            } catch (Exception e) {
                throw new AssertionError("Cannot get DisplayManagerGlobal", e);
            }
        }
        return displayManagerGlobal;
    }

    public static InputManager getInputManager() {
        if (inputManager == null) {
            try {
                Method getInstance = InputManager.class.getDeclaredMethod("getInstance");
                getInstance.setAccessible(true);
                inputManager = (InputManager) getInstance.invoke(null);
            } catch (Exception e) {
                throw new AssertionError("Cannot get InputManager", e);
            }
        }
        return inputManager;
    }

    public static Object getWindowManager() {
        if (windowManager == null) {
            windowManager = getService("window", "android.view.IWindowManager");
        }
        return windowManager;
    }
}
