package com.vdcontroller.server.wrappers;

import android.annotation.SuppressLint;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

@SuppressLint({"PrivateApi", "BlockedPrivateApi", "DiscouragedPrivateApi"})
public final class InputManagerWrapper {

    public static final int INJECT_MODE_ASYNC = 0;

    private final Object iInputManager;
    private Method injectMethod;
    private Method setDisplayIdMethod;
    private boolean loggedInjectMethod;

    private final Map<Integer, float[]> pointers = new LinkedHashMap<>();
    private long gestureDownTime = 0;

    public InputManagerWrapper() {
        this.iInputManager = ServiceManager.getInputManager();
    }

    private Method getInjectMethod() throws Exception {
        if (injectMethod == null) {
            for (Method m : iInputManager.getClass().getMethods()) {
                if ("injectInputEvent".equals(m.getName())) {
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length >= 2 && InputEvent.class.isAssignableFrom(p[0])) {
                        injectMethod = m;
                        injectMethod.setAccessible(true);
                        break;
                    }
                }
            }
            if (injectMethod == null) throw new NoSuchMethodException("injectInputEvent");
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
            Ln.w("setDisplayId(" + displayId + ") failed: " + e.getMessage());
            return false;
        }
    }

    public boolean injectInputEvent(InputEvent event, int mode) {
        try {
            if (!loggedInjectMethod) {
                Ln.i("inject method: " + getInjectMethod().toGenericString());
                loggedInjectMethod = true;
            }
            Object result = getInjectMethod().invoke(iInputManager, event, mode);
            return !(result instanceof Boolean) || (Boolean) result;
        } catch (Exception e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            Ln.w("injectInputEvent exception: " + c);
            return false;
        }
    }

    public boolean injectEvent(InputEvent event, int displayId, int mode) {
        if (displayId > 0) setDisplayId(event, displayId);
        return injectInputEvent(event, mode);
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

    public synchronized boolean injectTouch(int action, float x, float y, int pointerId,
                                            float pressure, long downTime, int displayId) {
        int masked = action & MotionEvent.ACTION_MASK;
        long now = SystemClock.uptimeMillis();
        if (downTime <= 0) downTime = now;

        if (masked == MotionEvent.ACTION_DOWN) {
            pointers.clear();
            gestureDownTime = downTime;
            pointers.put(pointerId, new float[]{x, y, pressure > 0 ? pressure : 1f});
        } else if (masked == MotionEvent.ACTION_POINTER_DOWN) {
            if (pointers.isEmpty()) gestureDownTime = downTime;
            pointers.put(pointerId, new float[]{x, y, pressure > 0 ? pressure : 1f});
        } else if (masked == MotionEvent.ACTION_MOVE) {
            float[] c = pointers.get(pointerId);
            if (c != null) {
                c[0] = x; c[1] = y; c[2] = pressure > 0 ? pressure : 1f;
            } else {
                pointers.put(pointerId, new float[]{x, y, pressure > 0 ? pressure : 1f});
            }
        } else if (masked == MotionEvent.ACTION_UP || masked == MotionEvent.ACTION_CANCEL) {
            pointers.put(pointerId, new float[]{x, y, 0f});
        } else if (masked == MotionEvent.ACTION_POINTER_UP) {
            float[] c = pointers.get(pointerId);
            if (c != null) { c[0] = x; c[1] = y; c[2] = 0f; }
        }

        int count = Math.max(1, pointers.size());
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[count];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[count];
        int i = 0;
        int actionIndex = 0;
        if (pointers.isEmpty()) {
            props[0] = new MotionEvent.PointerProperties();
            props[0].id = pointerId;
            props[0].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[0] = new MotionEvent.PointerCoords();
            coords[0].x = x;
            coords[0].y = y;
            coords[0].pressure = pressure;
        } else {
            for (Map.Entry<Integer, float[]> e : pointers.entrySet()) {
                MotionEvent.PointerProperties pp = new MotionEvent.PointerProperties();
                pp.id = e.getKey();
                pp.toolType = MotionEvent.TOOL_TYPE_FINGER;
                props[i] = pp;
                MotionEvent.PointerCoords pc = new MotionEvent.PointerCoords();
                pc.x = e.getValue()[0];
                pc.y = e.getValue()[1];
                pc.pressure = e.getValue()[2];
                pc.size = 0.1f;
                coords[i] = pc;
                if (e.getKey() == pointerId) actionIndex = i;
                i++;
            }
        }

        int finalAction = masked;
        if (masked == MotionEvent.ACTION_POINTER_DOWN || masked == MotionEvent.ACTION_POINTER_UP) {
            finalAction = masked | (actionIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
        }

        MotionEvent event = MotionEvent.obtain(
                gestureDownTime, now, finalAction,
                count, props, coords,
                0, 0, 1f, 1f,
                -1, 0,
                InputDevice.SOURCE_TOUCHSCREEN, 0);

        boolean ok = injectEvent(event, displayId, INJECT_MODE_ASYNC);
        event.recycle();

        if (masked == MotionEvent.ACTION_UP || masked == MotionEvent.ACTION_CANCEL) {
            pointers.clear();
            gestureDownTime = 0;
        } else if (masked == MotionEvent.ACTION_POINTER_UP) {
            pointers.remove(pointerId);
            if (pointers.isEmpty()) gestureDownTime = 0;
        }

        Ln.i("touch action=" + masked + " id=" + pointerId
                + " (" + (int) x + "," + (int) y + ") n=" + count + " d=" + displayId + " ok=" + ok);
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
                0, 0, 1f, 1f, -1, 0,
                InputDevice.SOURCE_TOUCHSCREEN, 0);
        boolean ok = injectEvent(event, displayId, INJECT_MODE_ASYNC);
        event.recycle();
        return ok;
    }
}
