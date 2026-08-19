package com.vdcontroller.client

import android.media.MediaCodec
import android.media.MediaFormat
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

    fun start(surface: Surface) {
        stop()
        val format = MediaFormat.createVideoFormat(mime, width, height)
        val c = MediaCodec.createDecoderByType(mime)
        c.configure(format, surface, null, 0)
        c.start()
        codec = c
        configured = true
        Log.i(TAG, "started $mime ${width}x$height")
    }

    fun feed(data: ByteArray) {
        val c = codec ?: return
        if (!configured || data.isEmpty()) return
        try {
            val inIndex = c.dequeueInputBuffer(10_000)
            if (inIndex >= 0) {
                val buf = c.getInputBuffer(inIndex) ?: return
                buf.clear()
                buf.put(data)
                c.queueInputBuffer(inIndex, 0, data.size, System.nanoTime() / 1000, 0)
            }
            val info = MediaCodec.BufferInfo()
            var outIndex = c.dequeueOutputBuffer(info, 0)
            while (outIndex >= 0) {
                c.releaseOutputBuffer(outIndex, true)
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
