package ai.opencap.android.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import ai.opencap.android.R

class QrOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, android.R.color.white)
        strokeWidth = resources.displayMetrics.density * 2f
        style = Paint.Style.STROKE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val corner = minOf(w, h) * 0.15f

        canvas.drawLine(0f, 0f, corner, 0f, linePaint)
        canvas.drawLine(0f, 0f, 0f, corner, linePaint)

        canvas.drawLine(w, 0f, w - corner, 0f, linePaint)
        canvas.drawLine(w, 0f, w, corner, linePaint)

        canvas.drawLine(0f, h, corner, h, linePaint)
        canvas.drawLine(0f, h, 0f, h - corner, linePaint)

        canvas.drawLine(w, h, w - corner, h, linePaint)
        canvas.drawLine(w, h, w, h - corner, linePaint)
    }
}
