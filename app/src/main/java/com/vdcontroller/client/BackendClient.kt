package com.vdcontroller.client

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Connects to the privileged server via TCP 127.0.0.1 (avoids SELinux abstract-socket blocks).
 */
class BackendClient(
    private val host: String = "127.0.0.1",
    private val port: Int = 27183
) {

    companion object {
        private const val TAG = "BackendClient"

        const val MSG_CREATE_VD = 1
        const val MSG_DESTROY_VD = 2
        const val MSG_INJECT_TOUCH = 3
        const val MSG_INJECT_SCROLL = 4
        const val MSG_INJECT_KEY = 5
        const val MSG_LAUNCH_APP = 6
        const val MSG_RESIZE_VD = 7
        const val MSG_PING = 9

        const val MSG_VD_CREATED = 20
        const val MSG_VD_DESTROYED = 21
        const val MSG_ERROR = 22
        const val MSG_PONG = 23
        const val MSG_OK = 24
    }

    data class VdInfo(val displayId: Int, val width: Int, val height: Int, val dpi: Int)

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private val connected = AtomicBoolean(false)

    val isConnected: Boolean get() = connected.get()

    @Synchronized
    fun connect(): Boolean {
        if (connected.get()) return true
        return try {
            val s = Socket()
            s.connect(InetSocketAddress(host, port), 3000)
            s.tcpNoDelay = true
            socket = s
            input = DataInputStream(s.getInputStream())
            output = DataOutputStream(s.getOutputStream())
            connected.set(true)
            Log.i(TAG, "Connected to $host:$port")
            true
        } catch (e: IOException) {
            Log.e(TAG, "Connect failed: ${e.message}")
            disconnect()
            false
        }
    }

    @Synchronized
    fun disconnect() {
        connected.set(false)
        try { input?.close() } catch (_: Exception) {}
        try { output?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        input = null
        output = null
        socket = null
    }

    suspend fun createVd(width: Int, height: Int, dpi: Int): Result<VdInfo> =
        withContext(Dispatchers.IO) {
            ensureConnected()
            val out = output ?: return@withContext Result.failure(IOException("not connected"))
            val inp = input ?: return@withContext Result.failure(IOException("not connected"))
            try {
                synchronized(this@BackendClient) {
                    out.writeByte(MSG_CREATE_VD)
                    out.writeInt(width)
                    out.writeInt(height)
                    out.writeInt(dpi)
                    out.flush()
                    when (val type = inp.readByte().toInt() and 0xFF) {
                        MSG_VD_CREATED -> {
                            val id = inp.readInt()
                            val w = inp.readInt()
                            val h = inp.readInt()
                            val d = inp.readInt()
                            Result.success(VdInfo(id, w, h, d))
                        }
                        MSG_ERROR -> Result.failure(IOException(readString(inp)))
                        else -> Result.failure(IOException("unexpected response $type"))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun destroyVd(): Result<Unit> = withContext(Dispatchers.IO) {
        ensureConnected()
        val out = output ?: return@withContext Result.failure(IOException("not connected"))
        val inp = input ?: return@withContext Result.failure(IOException("not connected"))
        try {
            synchronized(this@BackendClient) {
                out.writeByte(MSG_DESTROY_VD)
                out.flush()
                val type = inp.readByte().toInt() and 0xFF
                if (type == MSG_VD_DESTROYED || type == MSG_OK) Result.success(Unit)
                else Result.failure(IOException("destroy failed"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun injectTouch(action: Int, x: Float, y: Float, pointerId: Int = 0,
                    pressure: Float = 1f, downTime: Long = 0L) {
        if (!connected.get()) return
        try {
            synchronized(this) {
                val out = output ?: return
                out.writeByte(MSG_INJECT_TOUCH)
                out.writeInt(action)
                out.writeFloat(x)
                out.writeFloat(y)
                out.writeInt(pointerId)
                out.writeFloat(pressure)
                out.writeLong(downTime)
                out.flush()
                val inp = input ?: return
                val type = inp.readByte().toInt() and 0xFF
                if (type == MSG_ERROR) readString(inp)
            }
        } catch (e: Exception) {
            Log.w(TAG, "injectTouch error: ${e.message}")
        }
    }

    fun injectScroll(x: Float, y: Float, hScroll: Float, vScroll: Float) {
        if (!connected.get()) return
        try {
            synchronized(this) {
                val out = output ?: return
                out.writeByte(MSG_INJECT_SCROLL)
                out.writeFloat(x)
                out.writeFloat(y)
                out.writeFloat(hScroll)
                out.writeFloat(vScroll)
                out.flush()
                val inp = input ?: return
                val type = inp.readByte().toInt() and 0xFF
                if (type == MSG_ERROR) readString(inp)
            }
        } catch (e: Exception) {
            Log.w(TAG, "injectScroll error: ${e.message}")
        }
    }

    suspend fun launchApp(packageName: String): Result<Unit> = withContext(Dispatchers.IO) {
        ensureConnected()
        val out = output ?: return@withContext Result.failure(IOException("not connected"))
        val inp = input ?: return@withContext Result.failure(IOException("not connected"))
        try {
            synchronized(this@BackendClient) {
                out.writeByte(MSG_LAUNCH_APP)
                writeString(out, packageName)
                out.flush()
                when (val type = inp.readByte().toInt() and 0xFF) {
                    MSG_OK -> Result.success(Unit)
                    MSG_ERROR -> Result.failure(IOException(readString(inp)))
                    else -> Result.failure(IOException("unexpected $type"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
        if (!connect()) return@withContext false
        try {
            synchronized(this@BackendClient) {
                output?.writeByte(MSG_PING)
                output?.flush()
                val type = input?.readByte()?.toInt()?.and(0xFF)
                type == MSG_PONG
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun ensureConnected() {
        if (!connected.get()) {
            if (!connect()) throw IOException("Cannot connect to backend at $host:$port. Is the server running?")
        }
    }

    private fun writeString(out: DataOutputStream, s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun readString(inp: DataInputStream): String {
        val len = inp.readInt()
        if (len <= 0) return ""
        val bytes = ByteArray(len)
        inp.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }
}
