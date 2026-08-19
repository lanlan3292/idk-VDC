package com.vdcontroller.gesture

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import com.vdcontroller.client.BackendClient
import kotlin.math.sqrt

/**
 * Relative touchpad -> VirtualDisplay:
 *  - Finger move (without staying still) = move virtual cursor only
 *  - Tap                                 = click at cursor
 *  - Stay still for long-press timeout   = long-press (ACTION_DOWN)
 *  - After long-press, then move         = drag
 *  - Two fingers move                    = scroll
 *
 * Long-press is cancelled as soon as movement exceeds touchSlop,
 * so normal cursor sliding never becomes "press and drag".
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

    private var cursorX = 0.5f
    private var cursorY = 0.5f

    private var downTime = 0L
    private var isFingerDown = false
    private var isLongPressActive = false
    private var longPressCancelled = false
    private var pointerCount = 0

    private var downRawX = 0f
    private var downRawY = 0f
    private var lastX = 0f
    private var lastY = 0f

    private var lastScrollX = 0f
    private var lastScrollY = 0f

    private val longPressRunnable = Runnable {
        if (isFingerDown && !longPressCancelled && !isLongPressActive) {
            isLongPressActive = true
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
                isFingerDown = true
                isLongPressActive = false
                longPressCancelled = false
                onCursorMove(cursorX, cursorY)
                handler.postDelayed(longPressRunnable, longPressTimeout)
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                pointerCount = event.pointerCount
                handler.removeCallbacks(longPressRunnable)
                longPressCancelled = true
                if (event.pointerCount == 2) {
                    lastScrollX = (event.getX(0) + event.getX(1)) / 2f
                    lastScrollY = (event.getY(0) + event.getY(1)) / 2f
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (pointerCount >= 2 && event.pointerCount >= 2) {
                    val cx = (event.getX(0) + event.getX(1)) / 2f
                    val cy = (event.getY(0) + event.getY(1)) / 2f
                    val dx = cx - lastScrollX
                    val dy = cy - lastScrollY
                    lastScrollX = cx
                    lastScrollY = cy
                    val x = cursorX * vdWidth()
                    val y = cursorY * vdHeight()
                    client.injectScroll(x, y, -dx / 48f, -dy / 48f)
                    return true
                }

                val dx = event.x - lastX
                val dy = event.y - lastY
                lastX = event.x
                lastY = event.y

                if (!longPressCancelled && !isLongPressActive) {
                    val totalDx = event.x - downRawX
                    val totalDy = event.y - downRawY
                    if (sqrt(totalDx * totalDx + totalDy * totalDy) > touchSlop) {
                        longPressCancelled = true
                        handler.removeCallbacks(longPressRunnable)
                    }
                }

                val sensX = 1.2f / viewWidth
                val sensY = 1.2f / viewHeight
                cursorX = (cursorX + dx * sensX).coerceIn(0f, 1f)
                cursorY = (cursorY + dy * sensY).coerceIn(0f, 1f)
                onCursorMove(cursorX, cursorY)

                if (isLongPressActive) {
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
                val wasLong = isLongPressActive
                val wasDown = isFingerDown

                if (wasLong) {
                    inject(MotionEvent.ACTION_UP, cursorX, cursorY, pressure = 0f)
                } else if (wasDown && !longPressCancelled) {
                    inject(MotionEvent.ACTION_DOWN, cursorX, cursorY, pressure = 1f)
                    inject(MotionEvent.ACTION_UP, cursorX, cursorY, pressure = 0f)
                }

                isFingerDown = false
                isLongPressActive = false
                longPressCancelled = false
                pointerCount = 0
                return true
            }
        }
        return false
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
