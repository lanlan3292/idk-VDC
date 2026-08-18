package com.vdcontroller

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
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
                binding.btnDestroy.isEnabled = true
                binding.btnLaunch.isEnabled = true
                binding.btnTouchpad.isEnabled = true
                binding.emptyHint.visibility = View.GONE
                binding.cursorView.visibility = View.VISIBLE
                updateStatus(getString(R.string.vd_created, info.displayId))
                startFrameLoop()
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
        val apps = AppLoader.loadLauncherApps(this)
        val recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("启动应用到 Virtual Display")
            .setView(recycler)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        recycler.adapter = AppListAdapter(apps) { item ->
            dialog.dismiss()
            lifecycleScope.launch {
                val r = client.launchApp(item.packageName)
                r.onSuccess { toast("已启动 ${item.label}") }
                r.onFailure { toast("启动失败: ${it.message}") }
            }
        }
        dialog.show()
    }

    private fun toggleTouchpad() {
        if (touchpadShown) hideTouchpad() else showTouchpad()
    }

    private fun showTouchpad() {
        if (!Settings.canDrawOverlays(this)) {
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                OVERLAY_PERMISSION_REQ
            )
            toast("需要悬浮窗权限")
            return
        }
        if (touchpadView != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = FloatingTouchpadView(this)
        view.onGesture = { ev -> gestureHandler?.onTouchEvent(ev) ?: false }
        val params = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.45f).toInt(),
            (resources.displayMetrics.heightPixels * 0.35f).toInt(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.BOTTOM or Gravity.END
        params.x = 24
        params.y = 120
        wm.addView(view, params)
        touchpadView = view
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

    private fun updateCursorOverlay(nx: Float, ny: Float) {
        val container = binding.previewContainer
        if (container.width == 0 || container.height == 0) return
        binding.cursorView.x = nx * container.width - binding.cursorView.width / 2f
        binding.cursorView.y = ny * container.height - binding.cursorView.height / 2f
    }

    private fun updateStatus(msg: String) {
        binding.statusText.text = msg
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
