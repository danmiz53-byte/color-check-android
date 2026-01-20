package com.example.colorcheckpro

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class LassoOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        fun onLassoUpdated(points: List<PointF>)
    }

    var listener: Listener? = null

    private val points = ArrayList<PointF>(512)
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.WHITE
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x33FFFFFF
    }

    fun clear() {
        points.clear()
        path.reset()
        invalidate()
    }

    fun getPoints(): List<PointF> = points.toList()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (points.size < 2) return
        canvas.drawPath(path, fillPaint)
        canvas.drawPath(path, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                points.clear()
                path.reset()
                points.add(PointF(x, y))
                path.moveTo(x, y)
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                listener?.onLassoUpdated(points)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                // Add points with simple distance decimation
                val last = points.lastOrNull()
                if (last == null || (x - last.x) * (x - last.x) + (y - last.y) * (y - last.y) > 36f) {
                    points.add(PointF(x, y))
                    path.lineTo(x, y)
                    invalidate()
                    listener?.onLassoUpdated(points)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (points.size >= 3) {
                    path.close()
                    invalidate()
                }
                listener?.onLassoUpdated(points)
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
