package com.vdcontroller

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vdcontroller.client.BackendClient
import com.vdcontroller.databinding.ActivityMainBinding
import com.vdcontroller.gesture.TouchGestureHandler
import com.vdcontroller.launcher.AppListAdapter
import com.vdcontroller.launcher.AppLoader
import com.vdcontroller.ui.FloatingTouchpadView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            if (grantResult == 0) {
                toast("Shizuku 已授权")
                tryStartServerViaShizuku()
            } else {
                toast("Shizuku 授权被拒绝")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.previewSurface.holder.addCallback(this)
        binding.previewContainer.isClickable = true
        binding.previewContainer.setOnTouchListener { v, event ->
            handlePreviewTouch(v, event)
        }
        binding.previewSurface.isClickable = false

        binding.btnCreate.setOnClickListener { createVirtualDisplay() }
        binding.btnDestroy.setOnClickListener { destroyVirtualDisplay() }
        binding.btnLaunch.setOnClickListener { showAppPicker() }
        binding.btnTouchpad.setOnClickListener { toggleTouchpad() }

        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)

        lifecycleScope.launch {
            ensureBackend()
        }
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

    private fun startFrameLoop() {
        frameJob?.cancel()
        frameJob = lifecycleScope.launch {
            while (isActive) {
                try {
                    if (client.isConnected && vdInfo != null) {
                        val jpeg = client.getFrame()
                        if (jpeg != null) {
                            val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
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
                                        canvas.drawBitmap(bmp, null, RectF(left, top, left + dw, top + dh), null)
                                    } finally {
                                        holder.unlockCanvasAndPost(canvas)
                                    }
                                }
                                bmp.recycle()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "frame: ${e.message}")
                }
                delay(80)
            }
        }
    }

    private fun stopFrameLoop() {
        frameJob?.cancel()
        frameJob = null
    }

    private suspend fun ensureBackend(): Boolean {
        if (client.ping()) {
            updateStatus("已连接到 Backend (ADB)")
            return true
        }
        if (Shizuku.pingBinder()) {
            if (Shizuku.checkSelfPermission() != 0) {
                Shizuku.requestPermission(0)
                updateStatus("请授权 Shizuku")
                return false
            }
            tryStartServerViaShizuku()
            repeat(10) {
                delay(300)
                if (client.ping()) {
                    updateStatus("已连接到 Backend (Shizuku)")
                    return true
                }
            }
        }
        updateStatus("未连接 Backend，请先用 adb 启动 server")
        return false
    }

    private fun tryStartServerViaShizuku() {
        toast("请用 adb 启动 vdserver（当前推荐）")
    }

    private fun createVirtualDisplay() {
        val w = binding.inputWidth.text?.toString()?.toIntOrNull() ?: 1080
        val h = binding.inputHeight.text?.toString()?.toIntOrNull() ?: 1920
        val dpi = binding.inputDpi.text?.toString()?.toIntOrNull() ?: 420

        lifecycleScope.launch {
            updateStatus("正在创建…")
            if (!ensureBackend()) {
                toast("无法连接 Backend")
                return@launch
            }
            val result = client.createVd(w, h, dpi)
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
                binding.cursorView.visibility = View.VISIBLE
                updateStatus(getString(R.string.vd_created, info.displayId))
                startFrameLoop()
                toast("Virtual Display 创建成功 id=${info.displayId}")
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
        val apps = AppLoader.loadLaunchableApps(packageManager)
        val view = layoutInflater.inflate(R.layout.dialog_app_list, null)
        val rv = view.findViewById<RecyclerView>(R.id.appList)
        rv.layoutManager = LinearLayoutManager(this)
        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setNegativeButton("取消", null)
            .create()
        rv.adapter = AppListAdapter(packageManager) { item ->
            dialog.dismiss()
            lifecycleScope.launch {
                val r = client.launchApp(item.packageName)
                r.onSuccess { toast("已启动 ${item.label}") }
                    .onFailure { toast("启动失败: ${it.message}") }
            }
        }.also { it.submit(apps) }
        dialog.show()
    }

    private fun toggleTouchpad() {
        if (touchpadShown) hideTouchpad() else showTouchpad()
    }

    private fun showTouchpad() {
        if (!Settings.canDrawOverlays(this)) {
            toast(getString(R.string.overlay_permission))
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, OVERLAY_PERMISSION_REQ)
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
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 200
        }

        val tp = FloatingTouchpadView(this).apply {
            gestureHandler = this@MainActivity.gestureHandler
            attachToWindow(wm, params)
        }
        wm.addView(tp, params)
        touchpadView = tp
        touchpadShown = true
        binding.btnTouchpad.text = getString(R.string.hide_touchpad)
    }

    private fun hideTouchpad() {
        touchpadView?.let {
            try {
                (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it)
            } catch (_: Exception) {}
        }
        touchpadView = null
        touchpadShown = false
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

        val lx = event.x - left
        val ly = event.y - top
        if (lx < 0 || ly < 0 || lx > contentW || ly > contentH) {
            return true
        }
        val vdX = (lx / contentW) * info.width
        val vdY = (ly / contentH) * info.height

        val nx = (vdX / info.width).coerceIn(0f, 1f)
        val ny = (vdY / info.height).coerceIn(0f, 1f)
        gestureHandler?.setCursor(nx, ny)
        updateCursorOverlay(nx, ny)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                previewDownTime = SystemClock.uptimeMillis()
                client.injectTouch(MotionEvent.ACTION_DOWN, vdX, vdY, 0, 1f, previewDownTime)
            }
            MotionEvent.ACTION_MOVE -> {
                client.injectTouch(MotionEvent.ACTION_MOVE, vdX, vdY, 0, 1f, previewDownTime)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                client.injectTouch(MotionEvent.ACTION_UP, vdX, vdY, 0, 0f, previewDownTime)
            }
        }
        return true
    }

    private fun updateCursorOverlay(nx: Float, ny: Float) {
        val container = binding.previewContainer
        val cw = container.width
        val ch = container.height
        if (cw <= 0 || ch <= 0) return
        val cursor = binding.cursorView
        cursor.x = nx * cw - cursor.width / 2f
        cursor.y = ny * ch - cursor.height / 2f
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
            if (Settings.canDrawOverlays(this)) showTouchpad()
        }
    }
}
