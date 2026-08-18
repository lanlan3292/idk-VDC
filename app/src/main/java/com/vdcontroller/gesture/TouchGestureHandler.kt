package com.vdcontroller.gesture

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import com.vdcontroller.client.BackendClient
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Maps touchpad gestures to VirtualDisplay input events.
 *
 * Rules:
 *  - Single finger move          → move virtual cursor (ACTION_MOVE while down, or hover)
 *  - Single finger tap           → click (DOWN + UP)
 *  - Single finger long-press    → long-press (DOWN held)
 *  - Press then move             → drag (DOWN + MOVE)
 *  - Two fingers move together   → scroll
 */
class TouchGestureHandler(
    private val client: BackendClient,
    private val vdWidth: () -> Int,
    private val vdHeight: () -> Int,
    private val onCursorMove: (normX: Float, normY: Float) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.getTouchSlop().toFloat()
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()

    // Normalized cursor position [0,1]
    private var cursorX = 0.5f
    private var cursorY = 0.5f

    private var downTime = 0L
    private var isDown = false
    private var isLongPress = false
    private var isDragging = false
    private var pointerCount = 0

    private var lastX = 0f
    private var lastY = 0f
    private var downRawX = 0f
    private var downRawY = 0f

    // Two-finger scroll tracking
    private var lastScrollX = 0f
    private var lastScrollY = 0f

    private val longPressRunnable = Runnable {
        if (isDown && !isDragging) {
            isLongPress = true
            // Keep the finger down on the virtual display
            inject(MotionEvent.ACTION_DOWN, cursorX, cursorY, pressure = 1f)
        }
    }

    fun onTouchEvent(event: MotionEvent, viewWidth: Float, viewHeight: Float): Boolean {
        if (viewWidth <= 0 || viewHeight <= 0) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerCount = 1
                downRawX = event.x
                downRawY = event.y
                lastX = event.x
                lastY = event.y
                downTime = SystemClock.uptimeMillis()
                isDown = true
                isLongPress = false
                isDragging = false

                // Move cursor to touch position (normalized)
                updateCursorFromTouch(event.x, event.y, viewWidth, viewHeight)
                handler.postDelayed(longPressRunnable, longPressTimeout)
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                pointerCount = event.pointerCount
                handler.removeCallbacks(longPressRunnable)
                // Switch to scroll mode
                if (pointerCount == 2) {
                    if (isDown && !isLongPress && !isDragging) {
                        // Cancel potential click
                        isDown = false
                    }
                    lastScrollX = (event.getX(0) + event.getX(1)) / 2f
                    lastScrollY = (event.getY(0) + event.getY(1)) / 2f
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    // Two-finger scroll
                    val cx = (event.getX(0) + event.getX(1)) / 2f
                    val cy = (event.getY(0) + event.getY(1)) / 2f
                    val dx = cx - lastScrollX
                    val dy = cy - lastScrollY
                    lastScrollX = cx
                    lastScrollY = cy

                    // Convert pixel delta to scroll units (negative = natural)
                    val scale = 0.02f
                    val hScroll = -dx * scale
                    val vScroll = -dy * scale
                    val vx = cursorX * vdWidth()
                    val vy = cursorY * vdHeight()
                    client.injectScroll(vx, vy, hScroll, vScroll)
                    return true
                }

                // Single finger
                val dx = event.x - lastX
                val dy = event.y - lastY
                lastX = event.x
                lastY = event.y

                val totalDx = event.x - downRawX
                val totalDy = event.y - downRawY
                val dist = sqrt(totalDx * totalDx + totalDy * totalDy)

                if (dist > touchSlop) {
                    handler.removeCallbacks(longPressRunnable)
                    if (!isDragging && isDown) {
                        isDragging = true
                        // Start drag: send DOWN at current cursor then MOVE
                        inject(MotionEvent.ACTION_DOWN, cursorX, cursorY, pressure = 1f)
                    }
                }

                // Always move the virtual cursor
                // Relative movement based on touchpad delta
                val sensX = 1.5f / viewWidth
                val sensY = 1.5f / viewHeight
                cursorX = (cursorX + dx * sensX).coerceIn(0f, 1f)
                cursorY = (cursorY + dy * sensY).coerceIn(0f, 1f)
                onCursorMove(cursorX, cursorY)

                if (isDragging || isLongPress) {
                    inject(MotionEvent.ACTION_MOVE, cursorX, cursorY, pressure = 1f)
                }
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                pointerCount = event.pointerCount - 1
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                val wasDragging = isDragging
                val wasLong = isLongPress
                val wasDown = isDown

                if (wasDragging || wasLong) {
                    // Release
                    inject(MotionEvent.ACTION_UP, cursorX, cursorY, pressure = 0f)
                } else if (wasDown) {
                    // Tap = click
                    inject(MotionEvent.ACTION_DOWN, cursorX, cursorY, pressure = 1f)
                    inject(MotionEvent.ACTION_UP, cursorX, cursorY, pressure = 0f)
                }

                isDown = false
                isLongPress = false
                isDragging = false
                pointerCount = 0
                return true
            }
        }
        return false
    }

    private fun updateCursorFromTouch(x: Float, y: Float, vw: Float, vh: Float) {
        // Absolute positioning mode on first touch (optional).
        // For a classic touchpad feel we use relative; absolute can be toggled.
        // Here: keep relative, just record start.
        onCursorMove(cursorX, cursorY)
    }

    private fun inject(action: Int, nx: Float, ny: Float, pressure: Float) {
        val x = nx * vdWidth()
        val y = ny * vdHeight()
        client.injectTouch(action, x, y, 0, pressure, downTime)
    }

    fun getCursorNormX() = cursorX
    fun getCursorNormY() = cursorY

    fun setCursor(nx: Float, ny: Float) {
        cursorX = nx.coerceIn(0f, 1f)
        cursorY = ny.coerceIn(0f, 1f)
        onCursorMove(cursorX, cursorY)
    }
}
