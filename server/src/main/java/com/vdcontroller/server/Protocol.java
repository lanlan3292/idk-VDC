package com.vdcontroller.server;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class Protocol {

    public static final byte MSG_CREATE_VD     = 1;
    public static final byte MSG_DESTROY_VD    = 2;
    public static final byte MSG_INJECT_TOUCH  = 3;
    public static final byte MSG_INJECT_SCROLL = 4;
    public static final byte MSG_INJECT_KEY    = 5;
    public static final byte MSG_LAUNCH_APP    = 6;
    public static final byte MSG_RESIZE_VD     = 7;
    public static final byte MSG_SET_SURFACE   = 8;
    public static final byte MSG_PING          = 9;
    public static final byte MSG_GET_FRAME     = 10;

    public static final byte MSG_VD_CREATED    = 20;
    public static final byte MSG_VD_DESTROYED  = 21;
    public static final byte MSG_ERROR         = 22;
    public static final byte MSG_PONG          = 23;
    public static final byte MSG_OK            = 24;
    public static final byte MSG_FRAME         = 25;

    public static final int STREAM_JPEG = 0;
    public static final int STREAM_H264 = 1;
    public static final int STREAM_H265 = 2;

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

    public static void writeError(DataOutputStream out, String msg) throws IOException {
        out.writeByte(MSG_ERROR);
        writeString(out, msg);
        out.flush();
    }

    public static void writeVdCreated(DataOutputStream out, int displayId, int w, int h, int dpi, int streamMode)
            throws IOException {
        out.writeByte(MSG_VD_CREATED);
        out.writeInt(displayId);
        out.writeInt(w);
        out.writeInt(h);
        out.writeInt(dpi);
        out.writeInt(streamMode);
        out.flush();
    }
}
