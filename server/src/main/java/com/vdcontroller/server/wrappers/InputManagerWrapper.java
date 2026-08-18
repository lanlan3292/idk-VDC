package com.vdcontroller.server.wrappers;

import android.annotation.SuppressLint;
import android.hardware.input.InputManager;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.lang.reflect.Method;

/**
 * Inject input events with optional displayId (scrcpy pattern).
 */
@SuppressLint({"PrivateApi", "BlockedPrivateApi", "DiscouragedPrivateApi"})
public final class InputManagerWrapper {

    public static final int INJECT_MODE_ASYNC = 0;
    public static final int INJECT_MODE_WAIT_FOR_RESULT = 1;
    public static final int INJECT_MODE_WAIT_FOR_FINISH = 2;

    private static Method injectInputEventMethod;
    private static Method setDisplayIdMethod;

    private final InputManager manager;

    public InputManagerWrapper(InputManager manager) {
        this.manager = manager;
    }

    private static Method getInjectMethod() throws NoSuchMethodException {
        if (injectInputEventMethod == null) {
            injectInputEventMethod = InputManager.class.getMethod(
                    "injectInputEvent", InputEvent.class, int.class);
        }
        return injectInputEventMethod;
    }

    private static Method getSetDisplayIdMethod() throws NoSuchMethodException {
        if (setDisplayIdMethod == null) {
            setDisplayIdMethod = InputEvent.class.getMethod("setDisplayId", int.class);
        }
        return setDisplayIdMethod;
    }

    public static boolean setDisplayId(InputEvent event, int displayId) {
        try {
            getSetDisplayIdMethod().invoke(event, displayId);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean injectInputEvent(InputEvent event, int mode) {
        try {
            return (boolean) getInjectMethod().invoke(manager, event, mode);
        } catch (Exception e) {
            Ln.e("injectInputEvent failed", e);
            return false;
        }
    }

    public boolean injectEvent(InputEvent event, int displayId, int mode) {
        if (displayId != 0) {
            if (!setDisplayId(event, displayId)) {
                Ln.w("Failed to set displayId=" + displayId);
            }
        }
        return injectInputEvent(event, mode);
    }

    // ---------- helpers ----------

    public boolean injectKeyEvent(int action, int keyCode, int metaState, int displayId) {
        long now = SystemClock.uptimeMillis();
        KeyEvent event = new KeyEvent(
                now, now, action, keyCode, 0, metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                InputDevice.SOURCE_KEYBOARD);
        return injectEvent(event, displayId, INJECT_MODE_ASYNC);
    }

    public boolean pressKey(int keyCode, int displayId) {
        return injectKeyEvent(KeyEvent.ACTION_DOWN, keyCode, 0, displayId)
                && injectKeyEvent(KeyEvent.ACTION_UP, keyCode, 0, displayId);
    }

    public boolean injectTouch(int action, float x, float y, int pointerId,
                               float pressure, long downTime, int displayId) {
        long now = SystemClock.uptimeMillis();
        if (downTime <= 0) downTime = now;

        MotionEvent.PointerProperties props = new MotionEvent.PointerProperties();
        props.id = pointerId;
        props.toolType = MotionEvent.TOOL_TYPE_FINGER;

        MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
        coords.x = x;
        coords.y = y;
        coords.pressure = pressure;
        coords.size = 1f;

        MotionEvent event = MotionEvent.obtain(
                downTime, now, action,
                1, new MotionEvent.PointerProperties[]{props},
                new MotionEvent.PointerCoords[]{coords},
                0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_TOUCHSCREEN, 0);

        boolean ok = injectEvent(event, displayId, INJECT_MODE_ASYNC);
        event.recycle();
        return ok;
    }

    public boolean injectScroll(float x, float y, float hScroll, float vScroll, int displayId) {
        long now = SystemClock.uptimeMillis();

        MotionEvent.PointerProperties props = new MotionEvent.PointerProperties();
        props.id = 0;
        props.toolType = MotionEvent.TOOL_TYPE_FINGER;

        MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
        coords.x = x;
        coords.y = y;
        coords.setAxisValue(MotionEvent.AXIS_HSCROLL, hScroll);
        coords.setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll);

        MotionEvent event = MotionEvent.obtain(
                now, now, MotionEvent.ACTION_SCROLL,
                1, new MotionEvent.PointerProperties[]{props},
                new MotionEvent.PointerCoords[]{coords},
                0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_TOUCHSCREEN, 0);

        boolean ok = injectEvent(event, displayId, INJECT_MODE_ASYNC);
        event.recycle();
        return ok;
    }
}
