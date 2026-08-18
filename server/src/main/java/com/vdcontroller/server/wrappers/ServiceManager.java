package com.vdcontroller.server.wrappers;

import android.annotation.SuppressLint;
import android.os.IBinder;
import android.os.IInterface;

import java.lang.reflect.Method;

/**
 * Access system services via ServiceManager binders (works in app_process).
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

    private static Object inputManager;          // IInputManager
    private static Object displayManagerGlobal;  // DisplayManagerGlobal

    private ServiceManager() {}

    public static IBinder getServiceBinder(String name) {
        try {
            return (IBinder) GET_SERVICE_METHOD.invoke(null, name);
        } catch (Exception e) {
            throw new AssertionError("getService(" + name + ")", e);
        }
    }

    public static IInterface getService(String service, String stubClass) {
        try {
            IBinder binder = getServiceBinder(service);
            Method asInterface = Class.forName(stubClass + "$Stub")
                    .getMethod("asInterface", IBinder.class);
            return (IInterface) asInterface.invoke(null, binder);
        } catch (Exception e) {
            throw new AssertionError("getService " + service, e);
        }
    }

    /** Returns android.hardware.input.IInputManager */
    public static Object getInputManager() {
        if (inputManager == null) {
            try {
                IBinder binder = getServiceBinder("input");
                Class<?> stub = Class.forName("android.hardware.input.IInputManager$Stub");
                Method asInterface = stub.getMethod("asInterface", IBinder.class);
                inputManager = asInterface.invoke(null, binder);
                if (inputManager == null) {
                    throw new NullPointerException("IInputManager is null");
                }
                Ln.i("IInputManager acquired");
            } catch (Exception e) {
                throw new AssertionError("Cannot get IInputManager", e);
            }
        }
        return inputManager;
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

    public static Object getWindowManager() {
        return getService("window", "android.view.IWindowManager");
    }
}
