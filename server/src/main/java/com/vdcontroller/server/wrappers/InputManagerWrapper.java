package com.vdcontroller.server.wrappers;

import android.annotation.SuppressLint;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.lang.reflect.Method;

@SuppressLint({"PrivateApi", "BlockedPrivateApi", "DiscouragedPrivateApi"})
public final class InputManagerWrapper {

    public static final int INJECT_MODE_ASYNC = 0;
    public static final int INJECT_MODE_WAIT_FOR_RESULT = 1;
    public static final int INJECT_MODE_WAIT_FOR_FINISH = 2;

    private final Object iInputManager;
    private Method injectMethod;
    private Method setDisplayIdMethod;

    public InputManagerWrapper() {
        this.iInputManager = ServiceManager.getInputManager();
        if (iInputManager == null) {
            Ln.e("IInputManager is null - injection will fail");
        }
    }

    private Method getInjectMethod() throws Exception {
        if (injectMethod == null) {
            for (Method m : iInputManager.getClass().getMethods()) {
                if ("injectInputEvent".equals(m.getName())) {
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length >= 2 && InputEvent.class.isAssignableFrom(p[0])) {
                        injectMethod = m;
                        injectMethod.setAccessible(true);
                        Ln.i("injectInputEvent: " + m.toGenericString());
                        break;
                    }
                }
            }
            if (injectMethod == null) {
                throw new NoSuchMethodException("injectInputEvent");
            }
        }
        return injectMethod;
    }

    private boolean setDisplayId(InputEvent event, int displayId) {
        try {
            if (setDisplayIdMethod == null) {
                setDisplayIdMethod = InputEvent.class.getMethod("setDisplayId", int.class);
                setDisplayIdMethod.setAccessible(true);
            }
            setDisplayIdMethod.invoke(event, displayId);
            return true;
        } catch (Exception e) {
            Ln.w("setDisplayId failed: " + e.getMessage());
            return false;
        }
    }

    public boolean injectInputEvent(InputEvent event, int mode) {
        try {
            Object result = getInjectMethod().invoke(iInputManager, event, mode);
            if (result instanceof Boolean) {
                return (Boolean) result;
            }
            return true;
        } catch (Exception e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            Ln.e("injectInputEvent failed: " + c);
            return false;
        }
    }

    public boolean injectEvent(InputEvent event, int displayId, int mode) {
        if (displayId > 0) {
            if (!setDisplayId(event, displayId)) {
                Ln.w("could not set displayId=" + displayId + " on event");
            }
        }
        boolean ok = injectInputEvent(event, mode);
        if (!ok) {
            Ln.w("inject returned false action displayId=" + displayId);
        }
        return ok;
    }

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
        coords.pressure = pressure > 0 ? pressure : 1f;
        coords.size = 0.1f;

        MotionEvent event = MotionEvent.obtain(
                downTime, now, action,
                1,
                new MotionEvent.PointerProperties[]{props},
                new MotionEvent.PointerCoords[]{coords},
                0, 0, 1f, 1f,
                -1, 0,
                InputDevice.SOURCE_TOUCHSCREEN, 0);

        boolean ok = injectEvent(event, displayId, INJECT_MODE_ASYNC);
        Ln.d("touch action=" + action + " (" + x + "," + y + ") displayId=" + displayId + " ok=" + ok);
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
                1,
                new MotionEvent.PointerProperties[]{props},
                new MotionEvent.PointerCoords[]{coords},
                0, 0, 1f, 1f,
                -1, 0,
                InputDevice.SOURCE_TOUCHSCREEN, 0);

        boolean ok = injectEvent(event, displayId, INJECT_MODE_ASYNC);
        event.recycle();
        return ok;
    }
}
