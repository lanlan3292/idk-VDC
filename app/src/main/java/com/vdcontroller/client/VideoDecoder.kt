package com.vdcontroller.client

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface

class VideoDecoder(
    private val mime: String,
    private val width: Int,
    private val height: Int
) {
    companion object {
        private const val TAG = "VideoDecoder"
    }

    private var codec: MediaCodec? = null
    private var configured = false
    private var ptsUs = 0L
    private val frameIntervalUs = 33_333L

    fun start(surface: Surface) {
        stop()
        val format = MediaFormat.createVideoFormat(mime, width, height)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, width * height)
        val c = MediaCodec.createDecoderByType(mime)
        c.configure(format, surface, null, 0)
        c.start()
        codec = c
        configured = true
        ptsUs = 0L
        Log.i(TAG, "started $mime ${width}x$height")
    }

    fun feed(data: ByteArray) {
        val c = codec ?: return
        if (!configured || data.isEmpty()) return
        try {
            val inIndex = c.dequeueInputBuffer(5_000)
            if (inIndex >= 0) {
                val buf = c.getInputBuffer(inIndex) ?: return
                buf.clear()
                if (data.size > buf.capacity()) {
                    Log.w(TAG, "AU too large ${data.size} > ${buf.capacity()}")
                    c.queueInputBuffer(inIndex, 0, 0, ptsUs, 0)
                } else {
                    buf.put(data)
                    c.queueInputBuffer(inIndex, 0, data.size, ptsUs, 0)
                    ptsUs += frameIntervalUs
                }
            }
            val info = MediaCodec.BufferInfo()
            var outIndex = c.dequeueOutputBuffer(info, 0)
            while (outIndex >= 0) {
                if (Build.VERSION.SDK_INT >= 21) {
                    c.releaseOutputBuffer(outIndex, info.presentationTimeUs * 1000L)
                } else {
                    c.releaseOutputBuffer(outIndex, true)
                }
                outIndex = c.dequeueOutputBuffer(info, 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "feed: ${e.message}")
        }
    }

    fun stop() {
        configured = false
        try {
            codec?.stop()
            codec?.release()
        } catch (_: Exception) {
        }
        codec = null
    }
}
