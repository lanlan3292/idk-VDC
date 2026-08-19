package com.vdcontroller.server;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.view.Surface;

import com.vdcontroller.server.wrappers.Ln;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

public final class VideoEncoder {

    private final String mime;
    private final int width;
    private final int height;
    private MediaCodec codec;
    private Surface inputSurface;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread drainThread;

    private final Object frameLock = new Object();
    private byte[] latestAccessUnit;
    private byte[] codecConfig;

    public VideoEncoder(int streamMode, int width, int height) {
        this.mime = streamMode == Protocol.STREAM_H265 ? MediaFormat.MIMETYPE_VIDEO_HEVC
                : MediaFormat.MIMETYPE_VIDEO_AVC;
        this.width = width;
        this.height = height;
    }

    public void start() throws Exception {
        MediaFormat format = MediaFormat.createVideoFormat(mime, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, Math.min(4_000_000, width * height * 4));
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        if (Build.VERSION.SDK_INT >= 29) {
            format.setInteger(MediaFormat.KEY_MAX_FPS_TO_ENCODER, 30);
        }

        codec = MediaCodec.createEncoderByType(mime);
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        inputSurface = codec.createInputSurface();
        codec.start();
        running.set(true);

        drainThread = new Thread(this::drainLoop, "VdEncoderDrain");
        drainThread.setDaemon(true);
        drainThread.start();
        Ln.i("VideoEncoder started mime=" + mime + " " + width + "x" + height);
    }

    public Surface getInputSurface() {
        return inputSurface;
    }

    private void drainLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (running.get()) {
            try {
                int idx = codec.dequeueOutputBuffer(info, 10_000);
                if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) continue;
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Ln.i("encoder format: " + codec.getOutputFormat());
                    continue;
                }
                if (idx < 0) continue;

                ByteBuffer buf = codec.getOutputBuffer(idx);
                if (buf != null && info.size > 0) {
                    byte[] data = new byte[info.size];
                    buf.position(info.offset);
                    buf.limit(info.offset + info.size);
                    buf.get(data);

                    boolean isConfig = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                    synchronized (frameLock) {
                        if (isConfig) {
                            codecConfig = data;
                        } else {
                            boolean key = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                            if (key && codecConfig != null) {
                                byte[] merged = new byte[codecConfig.length + data.length];
                                System.arraycopy(codecConfig, 0, merged, 0, codecConfig.length);
                                System.arraycopy(data, 0, merged, codecConfig.length, data.length);
                                latestAccessUnit = merged;
                            } else {
                                latestAccessUnit = data;
                            }
                        }
                    }
                }
                codec.releaseOutputBuffer(idx, false);
            } catch (Exception e) {
                if (running.get()) Ln.w("encoder drain: " + e.getMessage());
                break;
            }
        }
    }

    public byte[] getLatestFrame() {
        synchronized (frameLock) {
            return latestAccessUnit;
        }
    }

    public void stop() {
        running.set(false);
        if (drainThread != null) {
            try { drainThread.join(500); } catch (InterruptedException ignored) {}
            drainThread = null;
        }
        if (codec != null) {
            try { codec.stop(); } catch (Exception ignored) {}
            try { codec.release(); } catch (Exception ignored) {}
            codec = null;
        }
        if (inputSurface != null) {
            try { inputSurface.release(); } catch (Exception ignored) {}
            inputSurface = null;
        }
        Ln.i("VideoEncoder stopped");
    }
}
