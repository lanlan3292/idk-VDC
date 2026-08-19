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

    private final Object iInputManager;
    private Method injectMethod;
    private Method setDisplayIdMethod;
    private boolean loggedInjectMethod;

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
        if (displayId > 0) {
            setDisplayId(event, displayId);
        }
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
        event.recycle();

        if (!ok) {
            ok = shellMotionEvent(action, x, y, displayId);
        }

        Ln.i("touch action=" + actionName(action)
                + " (" + (int) x + "," + (int) y + ") displayId=" + displayId + " ok=" + ok);
        return ok;
    }

    private static String actionName(int action) {
        switch (action) {
            case MotionEvent.ACTION_DOWN: return "DOWN";
            case MotionEvent.ACTION_UP: return "UP";
            case MotionEvent.ACTION_MOVE: return "MOVE";
            case MotionEvent.ACTION_CANCEL: return "CANCEL";
            default: return String.valueOf(action);
        }
    }

    private boolean shellMotionEvent(int action, float x, float y, int displayId) {
        String name;
        switch (action) {
            case MotionEvent.ACTION_DOWN: name = "DOWN"; break;
            case MotionEvent.ACTION_UP: name = "UP"; break;
            case MotionEvent.ACTION_MOVE: name = "MOVE"; break;
            default: return false;
        }
        try {
            String[] cmd = new String[]{
                    "input", "-d", String.valueOf(displayId),
                    "motionevent", name,
                    String.valueOf(Math.round(x)), String.valueOf(Math.round(y))
            };
            Process p = Runtime.getRuntime().exec(cmd);
            int code = p.waitFor();
            if (code != 0) {
                Ln.w("shell input motionevent exit=" + code);
                if (action == MotionEvent.ACTION_UP) {
                    return shellTap(x, y, displayId);
                }
                return false;
            }
            return true;
        } catch (Exception e) {
            Ln.w("shellMotionEvent: " + e.getMessage());
            return false;
        }
    }

    private boolean shellTap(float x, float y, int displayId) {
        try {
            String[] cmd = new String[]{
                    "input", "-d", String.valueOf(displayId),
                    "tap",
                    String.valueOf(Math.round(x)), String.valueOf(Math.round(y))
            };
            Process p = Runtime.getRuntime().exec(cmd);
            int code = p.waitFor();
            Ln.i("shell tap (" + (int) x + "," + (int) y + ") d=" + displayId + " exit=" + code);
            return code == 0;
        } catch (Exception e) {
            Ln.w("shellTap: " + e.getMessage());
            return false;
        }
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
        if (!ok) {
            try {
                int x1 = Math.round(x);
                int y1 = Math.round(y);
                int x2 = Math.round(x - hScroll * 80);
                int y2 = Math.round(y - vScroll * 80);
                Process p = Runtime.getRuntime().exec(new String[]{
                        "input", "-d", String.valueOf(displayId),
                        "swipe", String.valueOf(x1), String.valueOf(y1),
                        String.valueOf(x2), String.valueOf(y2), "80"
                });
                ok = p.waitFor() == 0;
            } catch (Exception e) {
                Ln.w("shell swipe: " + e.getMessage());
            }
        }
        return ok;
    }
}
