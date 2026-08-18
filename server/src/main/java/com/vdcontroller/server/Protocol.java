package com.vdcontroller.server;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Simple binary protocol between App and Server.
 *
 * Client -> Server:
 *   byte type
 *   payload...
 *
 * Server -> Client:
 *   byte type
 *   payload...
 */
public final class Protocol {

    // Client -> Server
    public static final byte MSG_CREATE_VD     = 1;
    public static final byte MSG_DESTROY_VD    = 2;
    public static final byte MSG_INJECT_TOUCH  = 3;
    public static final byte MSG_INJECT_SCROLL = 4;
    public static final byte MSG_INJECT_KEY    = 5;
    public static final byte MSG_LAUNCH_APP    = 6;
    public static final byte MSG_RESIZE_VD     = 7;
    public static final byte MSG_SET_SURFACE   = 8; // not used over socket
    public static final byte MSG_PING          = 9;

    // Server -> Client
    public static final byte MSG_VD_CREATED    = 20;
    public static final byte MSG_VD_DESTROYED  = 21;
    public static final byte MSG_ERROR         = 22;
    public static final byte MSG_PONG          = 23;
    public static final byte MSG_OK            = 24;

    private Protocol() {}

    public static void writeString(DataOutputStream out, String s) throws IOException {
        if (s == null) {
            out.writeInt(0);
            return;
        }
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    public static String readString(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len <= 0) return "";
        byte[] bytes = new byte[len];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static void writeCreateVd(DataOutputStream out, int w, int h, int dpi) throws IOException {
        out.writeByte(MSG_CREATE_VD);
        out.writeInt(w);
        out.writeInt(h);
        out.writeInt(dpi);
        out.flush();
    }

    public static void writeInjectTouch(DataOutputStream out, int action, float x, float y,
                                        int pointerId, float pressure, long downTime) throws IOException {
        out.writeByte(MSG_INJECT_TOUCH);
        out.writeInt(action);
        out.writeFloat(x);
        out.writeFloat(y);
        out.writeInt(pointerId);
        out.writeFloat(pressure);
        out.writeLong(downTime);
        out.flush();
    }

    public static void writeInjectScroll(DataOutputStream out, float x, float y,
                                         float h, float v) throws IOException {
        out.writeByte(MSG_INJECT_SCROLL);
        out.writeFloat(x);
        out.writeFloat(y);
        out.writeFloat(h);
        out.writeFloat(v);
        out.flush();
    }

    public static void writeLaunchApp(DataOutputStream out, String packageName) throws IOException {
        out.writeByte(MSG_LAUNCH_APP);
        writeString(out, packageName);
        out.flush();
    }

    public static void writeError(DataOutputStream out, String msg) throws IOException {
        out.writeByte(MSG_ERROR);
        writeString(out, msg);
        out.flush();
    }

    public static void writeVdCreated(DataOutputStream out, int displayId, int w, int h, int dpi)
            throws IOException {
        out.writeByte(MSG_VD_CREATED);
        out.writeInt(displayId);
        out.writeInt(w);
        out.writeInt(h);
        out.writeInt(dpi);
        out.flush();
    }
}
