package com.vdcontroller

import android.content.Intent
import android.graphics.PixelFormat
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

        // Try connect / start backend
        lifecycleScope.launch {
            ensureBackend()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        hideTouchpad()
        client.disconnect()
    }

    // ---- Surface ----
    override fun surfaceCreated(holder: SurfaceHolder) {
        // Surface is ready; if VD already exists we could setSurface,
        // but our current server creates its own ImageReader surface.
        // For a tighter integration one would pass this surface to the server.
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) {}

    // ---- Backend ----
    private suspend fun ensureBackend(): Boolean {
        // 1) Try direct connect (server already running via adb)
        if (client.ping()) {
            updateStatus("已连接到 Backend (ADB)")
            return true
        }
        // 2) Try Shizuku
        if (Shizuku.pingBinder()) {
            if (Shizuku.checkSelfPermission() != 0) {
                Shizuku.requestPermission(0)
                updateStatus("等待 Shizuku 授权…")
                return false
            }
            tryStartServerViaShizuku()
            // Give it a moment then reconnect
            kotlinx.coroutines.delay(800)
            if (client.ping()) {
                updateStatus("已连接到 Backend (Shizuku)")
                return true
            }
        }
        updateStatus("Backend 未运行。请用 ADB 启动服务，或开启 Shizuku")
        return false
    }

    /**
     * Launch the server jar via Shizuku (shell).
     * Requires the jar to be pushed to /data/local/tmp/vdserver.jar
     * or packaged in the app and extracted.
     *
     * Shizuku.newProcess is not public in recent API versions, so we call it
     * via reflection. If that fails, user can still start the server with ADB:
     *   adb shell CLASSPATH=/data/local/tmp/vdserver.jar app_process /system/bin \
     *       com.vdcontroller.server.Server --name=vdcontroller
     */
    private fun tryStartServerViaShizuku() {
        try {
            // Extract server jar from assets if present
            val jarFile = java.io.File(filesDir, "vdserver.jar")
            if (!jarFile.exists()) {
                try {
                    assets.open("vdserver.jar").use { input ->
                        jarFile.outputStream().use { output -> input.copyTo(output) }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "No embedded vdserver.jar in assets")
                }
            }
            val jarPath = if (jarFile.exists()) jarFile.absolutePath
            else "/data/local/tmp/vdserver.jar"

            val cmd = arrayOf(
                "sh", "-c",
                "CLASSPATH=$jarPath app_process /system/bin com.vdcontroller.server.Server --name=vdcontroller &"
            )

            // newProcess is package-private / hidden in some Shizuku versions → reflection
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            val process = method.invoke(null, cmd, null, null) as? Process
            Log.i(TAG, "Started server via Shizuku")
            process?.inputStream?.close()
            process?.errorStream?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku start failed", e)
            toast("Shizuku 自动启动失败，请用 ADB 手动启动 Server")
        }
    }

    // ---- VD control ----
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
                toast("Virtual Display 创建成功 id=${info.displayId}")
            }.onFailure {
                updateStatus("创建失败: ${it.message}")
                toast("创建失败: ${it.message}")
            }
        }
    }

    private fun destroyVirtualDisplay() {
        lifecycleScope.launch {
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

    // ---- Floating touchpad ----
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
            else toast("未授予悬浮窗权限")
        }
    }
}
