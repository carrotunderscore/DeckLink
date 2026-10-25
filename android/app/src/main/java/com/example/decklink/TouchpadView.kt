package com.example.decklink

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pekplatta: ett finger flyttar muspekaren, ett tryck klickar och två fingrar
 * rullar. Precis som en fysisk styrplatta, fast över Bluetooth HID.
 */
class TouchpadView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var onMove: ((Int, Int) -> Unit)? = null
    var onClick: (() -> Unit)? = null
    var onScroll: ((Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF17161F.toInt() }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = 0x26FFFFFF
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x4DFFFFFF
        textSize = 13f * density
        textAlign = Paint.Align.CENTER
    }
    private val bounds = RectF()

    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var travelled = false
    private var scrollAt = 0f
    private var scrollLeftover = 0f

    init {
        isClickable = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = 22f * density
        bounds.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(bounds, radius, radius, fill)
        bounds.inset(stroke.strokeWidth / 2, stroke.strokeWidth / 2)
        canvas.drawRoundRect(bounds, radius, radius, stroke)
        canvas.drawText("Touchpad", width / 2f, height / 2f + 5f * density, label)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                downX = event.x
                downY = event.y
                downAt = SystemClock.uptimeMillis()
                travelled = false
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                scrollAt = averageY(event)
                scrollLeftover = 0f
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    val y = averageY(event)
                    scrollLeftover += (scrollAt - y) / (26f * density)
                    scrollAt = y
                    val steps = scrollLeftover.toInt()
                    if (steps != 0) {
                        scrollLeftover -= steps
                        onScroll?.invoke(steps)
                    }
                    travelled = true
                    return true
                }
                val dx = (event.x - lastX) * SENSITIVITY
                val dy = (event.y - lastY) * SENSITIVITY
                lastX = event.x
                lastY = event.y
                if (abs(event.x - downX) > 8f * density || abs(event.y - downY) > 8f * density) {
                    travelled = true
                }
                if (dx != 0f || dy != 0f) onMove?.invoke(dx.roundToInt(), dy.roundToInt())
            }

            MotionEvent.ACTION_UP -> {
                if (!travelled && SystemClock.uptimeMillis() - downAt < 260) {
                    performClick()
                }
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        onClick?.invoke()
        return true
    }

    private fun averageY(event: MotionEvent): Float {
        var sum = 0f
        for (index in 0 until event.pointerCount) sum += event.getY(index)
        return sum / event.pointerCount
    }

    private companion object {
        const val SENSITIVITY = 1.55f
    }
}
