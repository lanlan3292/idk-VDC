package com.vdcontroller.server;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import com.vdcontroller.server.wrappers.InputManagerWrapper;
import com.vdcontroller.server.wrappers.Ln;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

@SuppressLint({"PrivateApi", "BlockedPrivateApi", "DiscouragedPrivateApi"})
public class VirtualDisplayController {

    private static final String DISPLAY_NAME = "VD-Controller";

    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private int displayId = -1;
    private int width;
    private int height;
    private int dpi;

    private final InputManagerWrapper inputInjector;
    private HandlerThread callbackThread;
    private Handler callbackHandler;
    private Surface externalSurface;
    private volatile byte[] latestJpeg;
    private final Object frameLock = new Object();
    private int streamMode = Protocol.STREAM_JPEG;
    private VideoEncoder videoEncoder;

    public VirtualDisplayController() {
        inputInjector = new InputManagerWrapper();
    }

    public synchronized int create(int width, int height, int dpi, Surface surface) {
        return create(width, height, dpi, surface, Protocol.STREAM_JPEG);
    }

    public synchronized int create(int width, int height, int dpi, Surface surface, int streamMode) {
        if (virtualDisplay != null) {
            destroy();
        }
        this.width = width;
        this.height = height;
        this.dpi = dpi;
        this.externalSurface = surface;
        this.streamMode = streamMode;

        callbackThread = new HandlerThread("VD-Callback");
        callbackThread.start();
        callbackHandler = new Handler(callbackThread.getLooper());

        Surface targetSurface = surface;
        if (targetSurface == null) {
            if (streamMode == Protocol.STREAM_H264 || streamMode == Protocol.STREAM_H265) {
                try {
                    videoEncoder = new VideoEncoder(streamMode, width, height);
                    videoEncoder.start();
                    targetSurface = videoEncoder.getInputSurface();
                    Ln.i("VideoEncoder surface ready mode=" + streamMode);
                } catch (Exception e) {
                    Ln.e("VideoEncoder failed, fallback JPEG", e);
                    this.streamMode = Protocol.STREAM_JPEG;
                    videoEncoder = null;
                }
            }
            if (targetSurface == null) {
                imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
                imageReader.setOnImageAvailableListener(reader -> {
                    Image image = null;
                    try {
                        image = reader.acquireLatestImage();
                        if (image == null) return;
                        byte[] jpeg = imageToJpeg(image, 45);
                        if (jpeg != null) {
                            synchronized (frameLock) {
                                latestJpeg = jpeg;
                            }
                        }
                    } catch (Exception e) {
                        Ln.d("frame capture: " + e.getMessage());
                    } finally {
                        if (image != null) image.close();
                    }
                }, callbackHandler);
                targetSurface = imageReader.getSurface();
                Ln.i("ImageReader ready for frame capture");
            }
        }

        int flags = VirtualDisplayFactory.defaultFlags();
        Ln.i("create() flags=" + flags + " surface=" + (targetSurface != null)
                + " streamMode=" + this.streamMode);

        try {
            virtualDisplay = VirtualDisplayFactory.create(DISPLAY_NAME, width, height, dpi,
                    targetSurface, flags, callbackHandler);
            if (virtualDisplay != null) {
                displayId = virtualDisplay.getDisplay().getDisplayId();
                Ln.i("VirtualDisplay created: " + width + "x" + height + "/" + dpi
                        + " id=" + displayId);
            } else {
                Ln.e("createVirtualDisplay returned null");
                displayId = -1;
                releaseResources();
            }
        } catch (Exception e) {
            Ln.e("Failed to create VirtualDisplay", e);
            displayId = -1;
            releaseResources();
        }
        return displayId;
    }

    public byte[] getLatestJpegFrame() {
        return getLatestFrame();
    }

    public int getStreamMode() {
        return streamMode;
    }

    public byte[] getLatestFrame() {
        if (videoEncoder != null) {
            return videoEncoder.getLatestFrame();
        }
        synchronized (frameLock) {
            return latestJpeg;
        }
    }

    private static byte[] imageToJpeg(Image image, int quality) {
        try {
            int w = image.getWidth();
            int h = image.getHeight();
            Image.Plane[] planes = image.getPlanes();
            if (planes == null || planes.length == 0) return null;
            ByteBuffer buffer = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride();
            int rowStride = planes[0].getRowStride();
            int rowPadding = rowStride - pixelStride * w;

            Bitmap bitmap;
            if (rowPadding == 0) {
                bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                bitmap.copyPixelsFromBuffer(buffer);
            } else {
                bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                int[] pixels = new int[w * h];
                int offset = 0;
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int i = offset + x * pixelStride;
                        int r = buffer.get(i) & 0xff;
                        int g = buffer.get(i + 1) & 0xff;
                        int b = buffer.get(i + 2) & 0xff;
                        int a = pixelStride == 4 ? (buffer.get(i + 3) & 0xff) : 255;
                        pixels[y * w + x] = (a << 24) | (r << 16) | (g << 8) | b;
                    }
                    offset += rowStride;
                }
                bitmap.setPixels(pixels, 0, w, 0, 0, w, h);
            }

            int maxW = 400;
            if (bitmap.getWidth() > maxW) {
                float scale = maxW / (float) bitmap.getWidth();
                int nh = Math.max(1, Math.round(bitmap.getHeight() * scale));
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap, maxW, nh, false);
                bitmap.recycle();
                bitmap = scaled;
            }

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, bos);
            bitmap.recycle();
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    public synchronized void setSurface(Surface surface) {
        this.externalSurface = surface;
        if (virtualDisplay != null && surface != null) {
            virtualDisplay.setSurface(surface);
            Ln.i("Surface updated");
        }
    }

    public synchronized void resize(int width, int height, int dpi) {
        if (virtualDisplay == null) return;
        this.width = width;
        this.height = height;
        this.dpi = dpi;
        virtualDisplay.resize(width, height, dpi);
        Ln.i("Resized to " + width + "x" + height + "/" + dpi);
    }

    public synchronized void destroy() {
        if (virtualDisplay != null) {
            try {
                virtualDisplay.release();
            } catch (Exception e) {
                Ln.w("release error: " + e.getMessage());
            }
            virtualDisplay = null;
        }
        releaseResources();
        displayId = -1;
        Ln.i("VirtualDisplay destroyed");
    }

    private void releaseResources() {
        if (videoEncoder != null) {
            try { videoEncoder.stop(); } catch (Exception ignored) {}
            videoEncoder = null;
        }
        if (imageReader != null) {
            try { imageReader.close(); } catch (Exception ignored) {}
            imageReader = null;
        }
        if (callbackThread != null) {
            callbackThread.quitSafely();
            callbackThread = null;
            callbackHandler = null;
        }
        externalSurface = null;
        synchronized (frameLock) { latestJpeg = null; }
    }

    public int getDisplayId() { return displayId; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public int getDpi() { return dpi; }

    public boolean isActive() {
        return virtualDisplay != null && displayId >= 0;
    }

    public boolean injectTouch(int action, float x, float y, int pointerId,
                               float pressure, long downTime) {
        if (!isActive()) return false;
        x = Math.max(0, Math.min(width - 1, x));
        y = Math.max(0, Math.min(height - 1, y));
        return inputInjector.injectTouch(action, x, y, pointerId, pressure, downTime, displayId);
    }

    public boolean injectScroll(float x, float y, float hScroll, float vScroll) {
        if (!isActive()) return false;
        return inputInjector.injectScroll(x, y, hScroll, vScroll, displayId);
    }

    public boolean injectKey(int keyCode) {
        if (!isActive()) return false;
        return inputInjector.pressKey(keyCode, displayId);
    }

    public boolean launchApp(String packageName) {
        if (!isActive()) return false;
        try {
            String resolve = "cmd package resolve-activity --brief "
                    + "-a android.intent.action.MAIN "
                    + "-c android.intent.category.LAUNCHER "
                    + packageName;
            Process rp = Runtime.getRuntime().exec(new String[]{"sh", "-c", resolve});
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(rp.getInputStream()));
            String line;
            String component = null;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.contains("/") && !line.startsWith("priority=") && !line.startsWith("No activity")) {
                    component = line;
                }
            }
            rp.waitFor();

            String cmd;
            if (component != null && component.contains("/")) {
                cmd = "am start --display " + displayId + " -n " + component;
                Ln.i("launchApp component=" + component);
            } else {
                cmd = "am start --display " + displayId
                        + " -a android.intent.action.MAIN"
                        + " -c android.intent.category.LAUNCHER"
                        + " -p " + packageName;
                Ln.i("launchApp package fallback " + packageName);
            }

            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            int code = p.waitFor();
            Ln.i("launchApp " + packageName + " exit=" + code);

            if (code != 0) {
                String cmd2 = "monkey --display-id " + displayId
                        + " -p " + packageName
                        + " -c android.intent.category.LAUNCHER 1";
                Process p2 = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd2});
                code = p2.waitFor();
                Ln.i("launchApp via monkey exit=" + code);
            }
            return code == 0;
        } catch (Exception e) {
            Ln.e("launchApp failed", e);
            return false;
        }
    }

    public boolean launchAppComponent(String component) {
        if (!isActive()) return false;
        try {
            String cmd = "am start --display " + displayId + " -n " + component;
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            int code = p.waitFor();
            Ln.i("launchComponent " + component + " exit=" + code);
            return code == 0;
        } catch (Exception e) {
            Ln.e("launchComponent failed", e);
            return false;
        }
    }
}
