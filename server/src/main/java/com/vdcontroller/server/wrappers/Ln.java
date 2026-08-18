package com.vdcontroller.server.wrappers;

import android.util.Log;

/** Minimal logger used by server process. */
public final class Ln {
    private static final String TAG = "VdServer";

    private Ln() {}

    public static void i(String msg) {
        Log.i(TAG, msg);
        System.out.println("[I] " + msg);
    }

    public static void d(String msg) {
        Log.d(TAG, msg);
        System.out.println("[D] " + msg);
    }

    public static void w(String msg) {
        Log.w(TAG, msg);
        System.out.println("[W] " + msg);
    }

    public static void e(String msg) {
        Log.e(TAG, msg);
        System.err.println("[E] " + msg);
    }

    public static void e(String msg, Throwable t) {
        Log.e(TAG, msg, t);
        System.err.println("[E] " + msg);
        t.printStackTrace(System.err);
    }
}
