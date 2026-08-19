package com.vdcontroller.server;

import android.os.Looper;

import com.vdcontroller.server.wrappers.Ln;
import com.vdcontroller.server.wrappers.Workarounds;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

public final class Server {

    private static final int DEFAULT_PORT = 27183;

    private final int port;
    private final VirtualDisplayController controller = new VirtualDisplayController();
    private volatile boolean running = true;

    public Server(int port) {
        this.port = port;
    }

    public void start() {
        Ln.i("VdServer starting, tcp 127.0.0.1:" + port);
        if (Looper.myLooper() == null) {
            Looper.prepareMainLooper();
        }

        try (ServerSocket serverSocket = new ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))) {
            Ln.i("Listening on 127.0.0.1:" + port);
            while (running) {
                Socket client = serverSocket.accept();
                Ln.i("Client connected from " + client.getRemoteSocketAddress());
                handleClient(client);
            }
        } catch (IOException e) {
            Ln.e("Server socket error", e);
        } finally {
            controller.destroy();
            Ln.i("Server stopped");
        }
    }

    private void handleClient(Socket client) {
        try (DataInputStream in = new DataInputStream(client.getInputStream());
             DataOutputStream out = new DataOutputStream(client.getOutputStream())) {

            while (running) {
                int type = in.readByte() & 0xFF;
                switch (type) {
                    case Protocol.MSG_CREATE_VD: {
                        int w = in.readInt();
                        int h = in.readInt();
                        int dpi = in.readInt();
                        int streamMode = in.readInt();
                        Ln.i("CREATE_VD " + w + "x" + h + "/" + dpi + " stream=" + streamMode);
                        int id = controller.create(w, h, dpi, null, streamMode);
                        if (id >= 0) {
                            Protocol.writeVdCreated(out, id, w, h, dpi, controller.getStreamMode());
                        } else {
                            String err = controller.getLastError();
                            if (err == null || err.isEmpty()) err = "Failed to create VirtualDisplay";
                            Protocol.writeError(out, err);
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
                        controller.injectTouch(action, x, y, pointerId, pressure, downTime);
                        break;
                    }
                    case Protocol.MSG_INJECT_SCROLL: {
                        float x = in.readFloat();
                        float y = in.readFloat();
                        float hScroll = in.readFloat();
                        float vScroll = in.readFloat();
                        controller.injectScroll(x, y, hScroll, vScroll);
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
                    case Protocol.MSG_GET_FRAME: {
                        byte[] frame = controller.getLatestFrame();
                        if (frame == null || frame.length == 0) {
                            out.writeByte(Protocol.MSG_ERROR);
                            Protocol.writeString(out, "no frame");
                        } else {
                            out.writeByte(Protocol.MSG_FRAME);
                            out.writeInt(frame.length);
                            out.write(frame);
                        }
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
        if (Looper.myLooper() == null) {
            Looper.prepareMainLooper();
        }
        Workarounds.apply();
        int port = DEFAULT_PORT;
        for (String arg : args) {
            if (arg.startsWith("--port=")) {
                try {
                    port = Integer.parseInt(arg.substring("--port=".length()));
                } catch (NumberFormatException ignored) {}
            }
        }
        new Server(port).start();
    }
}
