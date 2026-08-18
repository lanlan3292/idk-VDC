package com.vdcontroller.server.wrappers;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.ContextWrapper;

/**
 * Context whose getPackageName() matches the shell UID, required by DisplayManager.
 */
@SuppressLint("PrivateApi")
public final class FakeContext extends ContextWrapper {

    private final String packageName;

    public FakeContext(Context base, String packageName) {
        super(base);
        this.packageName = packageName;
    }

    @Override
    public String getPackageName() {
        return packageName;
    }

    @Override
    public String getOpPackageName() {
        return packageName;
    }

    @Override
    public String getAttributionTag() {
        return null;
    }
}
