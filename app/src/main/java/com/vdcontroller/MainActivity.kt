package com.vdcontroller

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vdcontroller.client.BackendClient
import com.vdcontroller.client.VideoDecoder
import com.vdcontroller.databinding.ActivityMainBinding
import com.vdcontroller.gesture.TouchGestureHandler
import com.vdcontroller.launcher.AppListAdapter
import com.vdcontroller.launcher.AppLoader
import com.vdcontroller.ui.FloatingTouchpadView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity(), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "MainActivity"
        private const val OVERLAY_PERMISSION_REQ = 1001
    }

    private lateinit var binding: ActivityMainBinding
    private val client = BackendClient()

    private var vdInfo: BackendClient.VdInfo? = null
    private var touchpadView: FloatingTouchpadView? = null
    private var touchpadShown = false
    private var gestureHandler: TouchGestureHandler? = null
    private var frameJob: Job? = null
    private var previewDownTime = 0L
    private var videoDecoder: VideoDecoder? = null
    private lateinit var prefs: SharedPreferences

    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            if (grantResult == 0) toast("Shizuku 已授权") else toast("Shizuku 授权被拒绝")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = getSharedPreferences("vd", MODE_PRIVATE)

        val modes = arrayOf(
            getString(R.string.stream_jpeg),
            getString(R.string.stream_h264),
            getString(R.string.stream_h265)
        )
        binding.streamModeSpinner.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, modes)
        binding.streamModeSpinner.setSelection(prefs.getInt("stream_mode", 0).coerceIn(0, 2))

        binding.previewSurface.holder.addCallback(this)
        binding.previewSurface.isClickable = false
        binding.touchOverlay.isClickable = true
        binding.touchOverlay.setOnTouchListener { v, event -> handlePreviewTouch(v, event) }

        binding.btnCreate.setOnClickListener { createVirtualDisplay() }
        binding.btnDestroy.setOnClickListener { destroyVirtualDisplay() }
        binding.btnLaunch.setOnClickListener { showAppPicker() }
        binding.btnTouchpad.setOnClickListener { toggleTouchpad() }

        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        lifecycleScope.launch { ensureBackend() }
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        stopFrameLoop()
        hideTouchpad()
        client.disconnect()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {}
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) {}

    private fun layoutPreviewSurface(vdW: Int, vdH: Int) {
        val container = binding.previewContainer
        container.post {
            val cw = container.width
            val ch = container.height
            if (cw <= 0 || ch <= 0 || vdW <= 0 || vdH <= 0) return@post
            val scale = minOf(cw / vdW.toFloat(), ch / vdH.toFloat())
            val w = (vdW * scale).toInt().coerceAtLeast(1)
            val h = (vdH * scale).toInt().coerceAtLeast(1)
            binding.previewSurface.layoutParams = FrameLayout.LayoutParams(w, h).apply {
                gravity = Gravity.CENTER
            }
            binding.previewSurface.requestLayout()
        }
    }

    private fun setupDecoderIfNeeded(info: BackendClient.VdInfo) {
        videoDecoder?.stop()
        videoDecoder = null
        if (info.streamMode == BackendClient.STREAM_H264 || info.streamMode == BackendClient.STREAM_H265) {
            val mime = if (info.streamMode == BackendClient.STREAM_H265) "video/hevc" else "video/avc"
            try {
                val surface = binding.previewSurface.holder.surface
                if (surface != null && surface.isValid) {
                    val dec = VideoDecoder(mime, info.width, info.height)
                    dec.start(surface)
                    videoDecoder = dec
                } else toast("Surface 未就绪，可改用 JPEG")
            } catch (e: Exception) {
                toast("解码器启动失败: ${e.message}")
            }
        }
    }

    private fun startFrameLoop() {
        frameJob?.cancel()
        frameJob = lifecycleScope.launch {
            while (isActive) {
                try {
                    if (client.isConnected && vdInfo != null) {
                        val dec = videoDecoder
                        if (dec != null) {
                            repeat(4) {
                                val frame = client.getFrame() ?: return@repeat
                                dec.feed(frame)
                            }
                        } else {
                            val frame = client.getFrame()
                            if (frame != null) {
                                val bmp = BitmapFactory.decodeByteArray(frame, 0, frame.size)
                                if (bmp != null) {
                                    val holder = binding.previewSurface.holder
                                    val canvas = holder.lockCanvas()
                                    if (canvas != null) {
                                        try {
                                            canvas.drawColor(Color.BLACK)
                                            val scale = minOf(
                                                canvas.width / bmp.width.toFloat(),
                                                canvas.height / bmp.height.toFloat()
                                            )
                                            val dw = bmp.width * scale
                                            val dh = bmp.height * scale
                                            val left = (canvas.width - dw) / 2f
                                            val top = (canvas.height - dh) / 2f
                                            canvas.drawBitmap(
                                                bmp, null,
                                                RectF(left, top, left + dw, top + dh), null
                                            )
                                        } finally {
                                            holder.unlockCanvasAndPost(canvas)
                                        }
                                    }
                                    bmp.recycle()
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "frame: ${e.message}")
                }
                delay(if (videoDecoder != null) 8 else 50)
            }
        }
    }

    private fun stopFrameLoop() {
        frameJob?.cancel()
        frameJob = null
        videoDecoder?.stop()
        videoDecoder = null
    }

    private suspend fun ensureBackend(): Boolean {
        if (client.ping()) {
            updateStatus("已连接到 Backend (ADB)")
            return true
        }
        updateStatus("未连接 Backend，请先用 adb 启动 server")
        return false
    }

    private fun createVirtualDisplay() {
        val w = binding.inputWidth.text?.toString()?.toIntOrNull() ?: 1080
        val h = binding.inputHeight.text?.toString()?.toIntOrNull() ?: 1920
        val dpi = binding.inputDpi.text?.toString()?.toIntOrNull() ?: 420
        lifecycleScope.launch {
            updateStatus("正在创建…")
            if (!ensureBackend()) {
                toast("无法连接 Backend")
                updateStatus("未连接 Backend")
                return@launch
            }
            val streamMode = binding.streamModeSpinner.selectedItemPosition.coerceIn(0, 2)
            prefs.edit().putInt("stream_mode", streamMode).apply()
            val result = client.createVd(w, h, dpi, streamMode)
            result.onSuccess { info ->
                vdInfo = info
                gestureHandler = TouchGestureHandler(
                    client,
                    vdWidth = { info.width },
                    vdHeight = { info.height },
                    onCursorMove = { nx, ny -> updateCursorOverlay(nx, ny) }
                )
                touchpadView?.gestureHandler = gestureHandler
                binding.btnDestroy.isEnabled = true
                binding.btnLaunch.isEnabled = true
                binding.btnTouchpad.isEnabled = true
                binding.emptyHint.visibility = View.GONE
                binding.cursorView.visibility = View.GONE
                updateStatus(getString(R.string.vd_created, info.displayId))
                layoutPreviewSurface(info.width, info.height)
                setupDecoderIfNeeded(info)
                startFrameLoop()
                toast("创建成功 id=${info.displayId}")
            }.onFailure {
                updateStatus("创建失败: ${it.message}")
                toast("创建失败: ${it.message}")
            }
        }
    }

    private fun destroyVirtualDisplay() {
        lifecycleScope.launch {
            stopFrameLoop()
            client.destroyVd()
            vdInfo = null
            gestureHandler = null
            touchpadView?.gestureHandler = null
            hideTouchpad()
            binding.btnDestroy.isEnabled = false
            binding.btnLaunch.isEnabled = false
            binding.btnTouchpad.isEnabled = false
            binding.emptyHint.visibility = View.VISIBLE
            binding.cursorView.visibility = View.GONE
            updateStatus(getString(R.string.vd_destroyed))
        }
    }

    private fun showAppPicker() {
        val view = layoutInflater.inflate(R.layout.dialog_app_list, null)
        val rv = view.findViewById<RecyclerView>(R.id.appList)
        val search = view.findViewById<EditText>(R.id.appSearch)
        val progress = view.findViewById<ProgressBar>(R.id.appListProgress)
        rv.layoutManager = LinearLayoutManager(this)
        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setNegativeButton("取消", null)
            .create()
        val adapter = AppListAdapter(packageManager) { item ->
            dialog.dismiss()
            lifecycleScope.launch {
                val r = client.launchApp(item.packageName)
                r.onSuccess { toast("已启动 ${item.label}") }
                    .onFailure { toast("启动失败: ${it.message}") }
            }
        }
        rv.adapter = adapter
        progress.visibility = View.VISIBLE
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        dialog.show()
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) { AppLoader.loadLaunchableApps(packageManager) }
            progress.visibility = View.GONE
            adapter.submit(apps)
        }
    }

    private fun toggleTouchpad() {
        if (touchpadShown) hideTouchpad() else showTouchpad()
    }

    private fun showTouchpad() {
        if (!Settings.canDrawOverlays(this)) {
            toast(getString(R.string.overlay_permission))
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                OVERLAY_PERMISSION_REQ
            )
            return
        }
        if (touchpadView != null) return
        if (gestureHandler == null) {
            toast("请先创建 Virtual Display")
            return
        }
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            600, 400,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50; y = 200
        }
        val tp = FloatingTouchpadView(this).apply {
            gestureHandler = this@MainActivity.gestureHandler
            attachToWindow(wm, params)
        }
        wm.addView(tp, params)
        touchpadView = tp
        touchpadShown = true
        binding.cursorView.visibility = View.VISIBLE
        binding.btnTouchpad.text = getString(R.string.hide_touchpad)
    }

    private fun hideTouchpad() {
        touchpadView?.let {
            try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } catch (_: Exception) {}
        }
        touchpadView = null
        touchpadShown = false
        binding.cursorView.visibility = View.GONE
        binding.btnTouchpad.text = getString(R.string.show_touchpad)
    }

    private fun handlePreviewTouch(v: View, event: MotionEvent): Boolean {
        val info = vdInfo ?: return false
        val vw = v.width.toFloat()
        val vh = v.height.toFloat()
        if (vw <= 0 || vh <= 0) return false

        val scale = minOf(vw / info.width, vh / info.height)
        val contentW = info.width * scale
        val contentH = info.height * scale
        val left = (vw - contentW) / 2f
        val top = (vh - contentH) / 2f

        fun mapPointer(index: Int): Pair<Float, Float>? {
            val lx = event.getX(index) - left
            val ly = event.getY(index) - top
            if (lx < 0 || ly < 0 || lx > contentW || ly > contentH) return null
            return (lx / contentW) * info.width to (ly / contentH) * info.height
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                previewDownTime = SystemClock.uptimeMillis()
                val mapped = mapPointer(0) ?: return true
                val (vdX, vdY) = mapped
                client.injectTouch(
                    MotionEvent.ACTION_DOWN, vdX, vdY,
                    event.getPointerId(0), 1f, previewDownTime
                )
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                val mapped = mapPointer(idx) ?: return true
                val (vdX, vdY) = mapped
                client.injectTouch(
                    MotionEvent.ACTION_POINTER_DOWN, vdX, vdY,
                    event.getPointerId(idx), 1f, previewDownTime
                )
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val mapped = mapPointer(i) ?: continue
                    val (vdX, vdY) = mapped
                    client.injectTouch(
                        MotionEvent.ACTION_MOVE, vdX, vdY,
                        event.getPointerId(i), 1f, previewDownTime
                    )
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val idx = event.actionIndex
                val mapped = mapPointer(idx)
                client.injectTouch(
                    MotionEvent.ACTION_POINTER_UP,
                    mapped?.first ?: 0f, mapped?.second ?: 0f,
                    event.getPointerId(idx), 0f, previewDownTime
                )
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val mapped = mapPointer(0)
                val action = if (event.actionMasked == MotionEvent.ACTION_CANCEL)
                    MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP
                client.injectTouch(
                    action, mapped?.first ?: 0f, mapped?.second ?: 0f,
                    event.getPointerId(0), 0f, previewDownTime
                )
            }
        }
        return true
    }

    private fun updateCursorOverlay(nx: Float, ny: Float) {
        val container = binding.previewContainer
        val info = vdInfo
        val cw = container.width
        val ch = container.height
        if (cw <= 0 || ch <= 0 || info == null) return
        val scale = minOf(cw / info.width.toFloat(), ch / info.height.toFloat())
        val contentW = info.width * scale
        val contentH = info.height * scale
        val left = (cw - contentW) / 2f
        val top = (ch - contentH) / 2f
        val cursor = binding.cursorView
        cursor.x = left + nx * contentW - cursor.width / 2f
        cursor.y = top + ny * contentH - cursor.height / 2f
    }

    private fun updateStatus(msg: String) {
        runOnUiThread { binding.statusText.text = msg }
    }

    private fun toast(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_REQ) {
            if (Settings.canDrawOverlays(this)) showTouchpad() else toast("未授予悬浮窗权限")
        }
    }
}
