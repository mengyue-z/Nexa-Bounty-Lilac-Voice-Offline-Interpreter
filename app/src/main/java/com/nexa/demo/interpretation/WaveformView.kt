package com.nexa.demo.interpretation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

/**
 * Custom view for the Waveform Icon in the header.
 * - Idle: White stroke lines
 * - Active: Yellow fill (#FFD93D) that follows audio volume
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        // Kuromi Star Yellow
        color = ContextCompat.getColor(context, R.color.waveform_orange)
    }
    
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * context.resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = ContextCompat.getColor(context, android.R.color.white)
    }
    
    private var volume: Float = 0f // 0.0 to 1.0
    private var isActive: Boolean = false

    fun setRecording(active: Boolean) {
        this.isActive = active
        if (!active) volume = 0f
        invalidate()
    }

    fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val scale = height / 24f // viewBox 24x24
        
        // Bar specs from React design
        val barPositions = floatArrayOf(2f, 6f, 10f, 14f, 18f, 22f)
        val barTopY = floatArrayOf(10f, 6f, 3f, 8f, 5f, 10f)
        val barBottomY = floatArrayOf(13f, 17f, 21f, 15f, 18f, 13f)
        
        if (isActive && volume > 0f) {
            // Draw filled bars using current volume as a "water level"
            val fillLevelY = 24f * scale - (24f * scale * volume)
            
            barPositions.forEachIndexed { index, x ->
                val xPos = x * scale
                val tY = barTopY[index] * scale
                val bY = barBottomY[index] * scale
                
                // Draw background/unfilled part (stroke)
                canvas.drawLine(xPos, tY, xPos, bY, strokePaint)
                
                // Draw filled part (Yellow) inside the bar
                val currentFillTop = maxOf(tY, fillLevelY)
                if (bY > currentFillTop) {
                    canvas.save()
                    // Draw a thick line or small rect for the fill
                    fillPaint.strokeWidth = strokePaint.strokeWidth
                    canvas.drawLine(xPos, currentFillTop, xPos, bY, fillPaint)
                    canvas.restore()
                }
            }
        } else {
            // Draw idle state (White strokes only)
            barPositions.forEachIndexed { index, x ->
                canvas.drawLine(x * scale, barTopY[index] * scale, x * scale, barBottomY[index] * scale, strokePaint)
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = context.resources.displayMetrics.density
        val size = (24f * density).toInt()
        setMeasuredDimension(resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec))
    }
}
