package com.vdcontroller.server;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Looper;

import com.vdcontroller.server.wrappers.Ln;
import com.vdcontroller.server.wrappers.Workarounds;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Entry point when launched via:
 *   CLASSPATH=vdserver.jar app_process / com.vdcontroller.server.Server [--name=xxx]
 *
 * Listens on a LocalServerSocket and handles control messages.
 */
public final class Server {

    private static final String DEFAULT_SOCKET_NAME = "vdcontroller";

    private final String socketName;
    private final VirtualDisplayController controller = new VirtualDisplayController();
    private volatile boolean running = true;

    public Server(String socketName) {
        this.socketName = socketName;
    }

    public void start() {
        Ln.i("VdServer starting, socket=" + socketName);
        if (Looper.myLooper() == null) {
            Looper.prepareMainLooper();
        }

        try (LocalServerSocket serverSocket = new LocalServerSocket(socketName)) {
            Ln.i("Listening on localabstract:" + socketName);
            while (running) {
                LocalSocket client = serverSocket.accept();
                Ln.i("Client connected");
                handleClient(client);
            }
        } catch (IOException e) {
            Ln.e("Server socket error", e);
        } finally {
            controller.destroy();
            Ln.i("Server stopped");
        }
    }

    private void handleClient(LocalSocket client) {
        try (DataInputStream in = new DataInputStream(client.getInputStream());
             DataOutputStream out = new DataOutputStream(client.getOutputStream())) {

            while (running) {
                int type = in.readByte() & 0xFF;
                switch (type) {
                    case Protocol.MSG_CREATE_VD: {
                        int w = in.readInt();
                        int h = in.readInt();
                        int dpi = in.readInt();
                        Ln.i("CREATE_VD " + w + "x" + h + "/" + dpi);
                        int id = controller.create(w, h, dpi, null);
                        if (id >= 0) {
                            Protocol.writeVdCreated(out, id, w, h, dpi);
                        } else {
                            Protocol.writeError(out, "Failed to create VirtualDisplay");
                        }
                        break;
                    }
                    case Protocol.MSG_DESTROY_VD: {
                        Ln.i("DESTROY_VD");
                        controller.destroy();
                        out.writeByte(Protocol.MSG_VD_DESTROYED);
                        out.flush();
                        break;
                    }
                    case Protocol.MSG_INJECT_TOUCH: {
                        int action = in.readInt();
                        float x = in.readFloat();
                        float y = in.readFloat();
                        int pointerId = in.readInt();
                        float pressure = in.readFloat();
                        long downTime = in.readLong();
                        boolean ok = controller.injectTouch(action, x, y, pointerId, pressure, downTime);
                        out.writeByte(ok ? Protocol.MSG_OK : Protocol.MSG_ERROR);
                        if (!ok) Protocol.writeString(out, "injectTouch failed");
                        out.flush();
                        break;
                    }
                    case Protocol.MSG_INJECT_SCROLL: {
                        float x = in.readFloat();
                        float y = in.readFloat();
                        float hScroll = in.readFloat();
                        float vScroll = in.readFloat();
                        boolean ok = controller.injectScroll(x, y, hScroll, vScroll);
                        out.writeByte(ok ? Protocol.MSG_OK : Protocol.MSG_ERROR);
                        if (!ok) Protocol.writeString(out, "injectScroll failed");
                        out.flush();
                        break;
                    }
                    case Protocol.MSG_INJECT_KEY: {
                        int keyCode = in.readInt();
                        boolean ok = controller.injectKey(keyCode);
                        out.writeByte(ok ? Protocol.MSG_OK : Protocol.MSG_ERROR);
                        if (!ok) Protocol.writeString(out, "injectKey failed");
                        out.flush();
                        break;
                    }
                    case Protocol.MSG_LAUNCH_APP: {
                        String pkg = Protocol.readString(in);
                        Ln.i("LAUNCH_APP " + pkg);
                        boolean ok = controller.launchApp(pkg);
                        out.writeByte(ok ? Protocol.MSG_OK : Protocol.MSG_ERROR);
                        if (!ok) Protocol.writeString(out, "launch failed");
                        out.flush();
                        break;
                    }
                    case Protocol.MSG_RESIZE_VD: {
                        int w = in.readInt();
                        int h = in.readInt();
                        int dpi = in.readInt();
                        controller.resize(w, h, dpi);
                        out.writeByte(Protocol.MSG_OK);
                        out.flush();
                        break;
                    }
                    case Protocol.MSG_PING: {
                        out.writeByte(Protocol.MSG_PONG);
                        out.flush();
                        break;
                    }
                    default:
                        Ln.w("Unknown message type: " + type);
                        Protocol.writeError(out, "Unknown type " + type);
                        break;
                }
            }
        } catch (IOException e) {
            Ln.i("Client disconnected: " + e.getMessage());
        } finally {
            try {
                client.close();
            } catch (IOException ignored) {}
        }
    }

    public static void main(String[] args) {
        Workarounds.apply();
        String name = DEFAULT_SOCKET_NAME;
        for (String arg : args) {
            if (arg.startsWith("--name=")) {
                name = arg.substring("--name=".length());
            }
        }
        new Server(name).start();
    }
}
