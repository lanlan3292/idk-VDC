package com.vdcontroller.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.vdcontroller.gesture.TouchGestureHandler

/**
 * Floating, resizable, movable touchpad overlay.
 * Touches on this view are mapped to VirtualDisplay input.
 * Touches outside go to the normal phone UI.
 */
class FloatingTouchpadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66000000
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAAFFFFFF.toInt()
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt()
        textSize = 28f
        textAlign = Paint.Align.CENTER
    }

    private val rect = RectF()
    private val handleSize = 48f

    var gestureHandler: TouchGestureHandler? = null

    // Resize / drag state for the window itself
    private var mode = Mode.NONE
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    private var windowManager: WindowManager? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    enum class Mode { NONE, DRAG, RESIZE_BR, TOUCHPAD }

    fun attachToWindow(wm: WindowManager, params: WindowManager.LayoutParams) {
        windowManager = wm
        layoutParams = params
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val w = width.toFloat()
        val h = height.toFloat()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.rawX
                lastTouchY = event.rawY
                mode = when {
                    isInResizeHandle(event.x, event.y, w, h) -> Mode.RESIZE_BR
                    isInDragHandle(event.x, event.y, w, h) -> Mode.DRAG
                    else -> Mode.TOUCHPAD
                }
                if (mode == Mode.TOUCHPAD) {
                    return gestureHandler?.onTouchEvent(event, w, h) ?: false
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                when (mode) {
                    Mode.DRAG -> {
                        val dx = (event.rawX - lastTouchX).toInt()
                        val dy = (event.rawY - lastTouchY).toInt()
                        lastTouchX = event.rawX
                        lastTouchY = event.rawY
                        layoutParams?.let { lp ->
                            lp.x += dx
                            lp.y += dy
                            windowManager?.updateViewLayout(this, lp)
                        }
                        return true
                    }
                    Mode.RESIZE_BR -> {
                        val dx = (event.rawX - lastTouchX).toInt()
                        val dy = (event.rawY - lastTouchY).toInt()
                        lastTouchX = event.rawX
                        lastTouchY = event.rawY
                        layoutParams?.let { lp ->
                            lp.width = (lp.width + dx).coerceAtLeast(200)
                            lp.height = (lp.height + dy).coerceAtLeast(150)
                            windowManager?.updateViewLayout(this, lp)
                        }
                        return true
                    }
                    Mode.TOUCHPAD -> {
                        return gestureHandler?.onTouchEvent(event, w, h) ?: false
                    }
                    else -> return false
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL,
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                if (mode == Mode.TOUCHPAD) {
                    val handled = gestureHandler?.onTouchEvent(event, w, h) ?: false
                    mode = Mode.NONE
                    return handled
                }
                mode = Mode.NONE
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun isInResizeHandle(x: Float, y: Float, w: Float, h: Float): Boolean {
        return x > w - handleSize && y > h - handleSize
    }

    private fun isInDragHandle(x: Float, y: Float, w: Float, h: Float): Boolean {
        // Top bar area for dragging
        return y < handleSize
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, 16f, 16f, bgPaint)
        canvas.drawRoundRect(rect, 16f, 16f, borderPaint)

        // Drag bar
        canvas.drawRoundRect(0f, 0f, w, handleSize, 16f, 16f, handlePaint)
        canvas.drawText("Touchpad  ·  拖动此处移动", w / 2, handleSize * 0.7f, textPaint)

        // Resize handle (bottom-right triangle-ish)
        canvas.drawCircle(w - handleSize / 2, h - handleSize / 2, handleSize / 3, handlePaint)
    }
}
