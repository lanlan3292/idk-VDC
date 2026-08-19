package com.vdcontroller.server;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.view.Surface;

import com.vdcontroller.server.wrappers.Ln;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class VideoEncoder {

    private static final int MAX_QUEUED = 3;

    private final String mime;
    private final int width;
    private final int height;
    private MediaCodec codec;
    private Surface inputSurface;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread drainThread;

    private final ConcurrentLinkedQueue<byte[]> frameQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queued = new AtomicInteger(0);
    private volatile byte[] codecConfig;

    public VideoEncoder(int streamMode, int width, int height) {
        this.mime = streamMode == Protocol.STREAM_H265
                ? MediaFormat.MIMETYPE_VIDEO_HEVC
                : MediaFormat.MIMETYPE_VIDEO_AVC;
        this.width = width;
        this.height = height;
    }

    public void start() throws Exception {
        MediaFormat format = MediaFormat.createVideoFormat(mime, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        int bitrate = Math.min(6_000_000, Math.max(1_500_000, width * height));
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        if (Build.VERSION.SDK_INT >= 29) {
            format.setInteger(MediaFormat.KEY_MAX_FPS_TO_ENCODER, 30);
        }
        if (Build.VERSION.SDK_INT >= 23) {
            format.setInteger(MediaFormat.KEY_PRIORITY, 0);
        }

        codec = MediaCodec.createEncoderByType(mime);
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        inputSurface = codec.createInputSurface();
        codec.start();
        running.set(true);

        drainThread = new Thread(this::drainLoop, "VdEncoderDrain");
        drainThread.setDaemon(true);
        drainThread.start();
        Ln.i("VideoEncoder started mime=" + mime + " " + width + "x" + height + " br=" + bitrate);
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
                    if (isConfig) {
                        codecConfig = data;
                    } else {
                        boolean key = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                        byte[] au;
                        if (key && codecConfig != null) {
                            au = new byte[codecConfig.length + data.length];
                            System.arraycopy(codecConfig, 0, au, 0, codecConfig.length);
                            System.arraycopy(data, 0, au, codecConfig.length, data.length);
                        } else {
                            au = data;
                        }
                        offer(au);
                    }
                }
                codec.releaseOutputBuffer(idx, false);
            } catch (Exception e) {
                if (running.get()) Ln.w("encoder drain: " + e.getMessage());
                break;
            }
        }
    }

    private void offer(byte[] au) {
        frameQueue.offer(au);
        int n = queued.incrementAndGet();
        while (n > MAX_QUEUED) {
            if (frameQueue.poll() != null) n = queued.decrementAndGet();
            else break;
        }
    }

    public byte[] getLatestFrame() {
        byte[] au = frameQueue.poll();
        if (au != null) queued.decrementAndGet();
        return au;
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
        frameQueue.clear();
        queued.set(0);
        Ln.i("VideoEncoder stopped");
    }
}
