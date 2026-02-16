package com.nexa.demo.interpretation

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.content.ContextCompat
import kotlin.math.min

/**
 * Custom microphone button with fixed sizes and 2-layer ripple animation:
 * - Background circle: 48dp
 * - Microphone icon: 24dp
 * - 2-layer staggered expanding ripples (Lavender #E8D4F0) with soft transparency
 */
class MicrophoneButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * context.resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private var volume: Float = 0f
    private var isRecording: Boolean = false
    private val micPath = Path()
    private var animProgress = 0f

    private val pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2000
        repeatCount = ValueAnimator.INFINITE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener {
            animProgress = it.animatedValue as Float
            invalidate()
        }
    }

    fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0f, 1f)
        invalidate()
    }

    fun setRecording(recording: Boolean) {
        if (isRecording != recording) {
            isRecording = recording
            if (recording) {
                pulseAnimator.start()
            } else {
                pulseAnimator.cancel()
                animProgress = 0f
                volume = 0f
            }
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val centerX = width / 2f
        val centerY = height / 2f
        val density = context.resources.displayMetrics.density

        val radius = 24f * density
        val micIconSize = 24f * density

        // 1. Draw ripples
        if (isRecording) {
            paint.color = ContextCompat.getColor(context, R.color.pulse_circle)
            drawRipple(canvas, centerX, centerY, radius, animProgress)
            paint.alpha = 255
        }

        // 2. Draw background circle
        paint.color = ContextCompat.getColor(
            context,
            if (isRecording) R.color.mic_button_recording else R.color.mic_button_idle
        )
        canvas.drawCircle(centerX, centerY, radius, paint)

        // 3. Build mic path
        micPath.reset()
        buildMicPath(micPath, centerX, centerY, micIconSize)

        // 4. Draw mic stroke with gradient fill
        if (isRecording && volume > 0f) {
            val s = micIconSize / 24f
            val oy = centerY - 12f * s
            val micBottom = oy + 23f * s
            val fillY = micBottom - ((23f * s) * volume)

            // Draw pink stroke (bottom part)
            canvas.save()
            canvas.clipRect(0f, fillY, width.toFloat(), height.toFloat())
            strokePaint.color = ContextCompat.getColor(context, R.color.mic_green)
            canvas.drawPath(micPath, strokePaint)
            canvas.restore()

            // Draw white stroke (top part)
            canvas.save()
            canvas.clipRect(0f, 0f, width.toFloat(), fillY)
            strokePaint.color = ContextCompat.getColor(context, android.R.color.white)
            canvas.drawPath(micPath, strokePaint)
            canvas.restore()
        } else {
            strokePaint.color = ContextCompat.getColor(context, android.R.color.white)
            canvas.drawPath(micPath, strokePaint)
        }
    }

    private fun drawRipple(canvas: Canvas, cx: Float, cy: Float, baseRadius: Float, progress: Float) {
        val rippleRadius = baseRadius * (1.0f + (progress * 0.5f))
        paint.alpha = ((1.0f - progress) * 250).toInt()
        canvas.drawCircle(cx, cy, rippleRadius, paint)
    }

    private fun buildMicPath(path: Path, centerX: Float, centerY: Float, size: Float) {
        val s = size / 24f
        val ox = centerX - 12f * s
        val oy = centerY - 12f * s

        // Mic Head
        path.moveTo(ox + 12f * s, oy + 1f * s)
        path.cubicTo(ox + 10.34f * s, oy + 1f * s, ox + 9f * s, oy + 2.34f * s, ox + 9f * s, oy + 4f * s)
        path.lineTo(ox + 9f * s, oy + 12f * s)
        path.cubicTo(ox + 9f * s, oy + 13.66f * s, ox + 10.34f * s, oy + 15f * s, ox + 12f * s, oy + 15f * s)
        path.cubicTo(ox + 13.66f * s, oy + 15f * s, ox + 15f * s, oy + 13.66f * s, ox + 15f * s, oy + 12f * s)
        path.lineTo(ox + 15f * s, oy + 4f * s)
        path.cubicTo(ox + 15f * s, oy + 2.34f * s, ox + 13.66f * s, oy + 1f * s, ox + 12f * s, oy + 1f * s)
        path.close()

        // Mic Stand
        path.moveTo(ox + 19f * s, oy + 10f * s)
        path.lineTo(ox + 19f * s, oy + 12f * s)
        path.cubicTo(ox + 19f * s, oy + 15.87f * s, ox + 15.87f * s, oy + 19f * s, ox + 12f * s, oy + 19f * s)
        path.cubicTo(ox + 8.13f * s, oy + 19f * s, ox + 5f * s, oy + 15.87f * s, ox + 5f * s, oy + 12f * s)
        path.lineTo(ox + 5f * s, oy + 10f * s)

        // Vert line
        path.moveTo(ox + 12f * s, oy + 19f * s)
        path.lineTo(ox + 12f * s, oy + 23f * s)

        // Base
        path.moveTo(ox + 8f * s, oy + 23f * s)
        path.lineTo(ox + 16f * s, oy + 23f * s)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = min(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
        setMeasuredDimension(size, size)
    }
}