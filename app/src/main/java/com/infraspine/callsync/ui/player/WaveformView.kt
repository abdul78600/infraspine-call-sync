package com.infraspine.callsync.ui.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.infraspine.callsync.R

class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var amplitudes = FloatArray(0)
    private var progress = 0f

    private val playedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.primary)
        strokeCap = Paint.Cap.ROUND
    }

    private val unplayedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.divider)
        strokeCap = Paint.Cap.ROUND
    }

    var onSeek: ((Float) -> Unit)? = null

    fun setAmplitudes(data: FloatArray) {
        amplitudes = data
        invalidate()
    }

    fun setProgress(fraction: Float) {
        progress = fraction.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (amplitudes.isEmpty()) return
        val w = width.toFloat()
        val h = height.toFloat()
        val midY = h / 2f
        val count = amplitudes.size
        val barWidth = w / count
        val strokeWidth = (barWidth * 0.55f).coerceAtLeast(2f)
        playedPaint.strokeWidth = strokeWidth
        unplayedPaint.strokeWidth = strokeWidth

        for (i in 0 until count) {
            val x = i * barWidth + barWidth / 2f
            val half = amplitudes[i] * h * 0.44f
            val paint = if (x / w <= progress) playedPaint else unplayedPaint
            canvas.drawLine(x, midY - half, x, midY + half, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val fraction = (event.x / width).coerceIn(0f, 1f)
                progress = fraction
                onSeek?.invoke(fraction)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
