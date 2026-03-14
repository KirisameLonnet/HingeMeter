package com.example.hingemeter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import java.util.Locale
import kotlin.math.hypot

class AngleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val textPaintWhite = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaintBlack = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wedgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wedgePath = Path()
    private val wedgeRect = RectF()
    private val textScaleY = 1.12f
    private var angleDegrees = 0f
    private var overrideText: String? = null

    init {
        val textSizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            72f,
            resources.displayMetrics
        )
        val typeface = ResourcesCompat.getFont(context, R.font.iosevka_thin)
        val white = ContextCompat.getColor(context, R.color.hinge_white)
        val black = ContextCompat.getColor(context, R.color.hinge_black)

        textPaintWhite.color = white
        textPaintWhite.textSize = textSizePx
        textPaintWhite.letterSpacing = 0f
        textPaintWhite.typeface = typeface

        textPaintBlack.color = black
        textPaintBlack.textSize = textSizePx
        textPaintBlack.letterSpacing = 0f
        textPaintBlack.typeface = typeface

        wedgePaint.color = white
    }

    fun setAngle(angle: Float) {
        angleDegrees = angle.coerceIn(0f, 180f)
        if (overrideText == null) {
            invalidate()
        }
    }

    fun setOverrideText(text: String?) {
        overrideText = text
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(ContextCompat.getColor(context, R.color.hinge_black))

        val centerX = width / 2f
        val centerY = height / 2f
        val sweepAngle = angleDegrees.coerceIn(0f, 180f)
        if (sweepAngle > 0f) {
            val radius = hypot(width.toFloat(), height.toFloat())
            wedgeRect.set(
                centerX - radius,
                centerY - radius,
                centerX + radius,
                centerY + radius
            )
            wedgePath.reset()
            wedgePath.moveTo(centerX, centerY)
            val startAngle = 270f + sweepAngle / 2f
            wedgePath.arcTo(wedgeRect, startAngle, -sweepAngle)
            wedgePath.close()
            canvas.drawPath(wedgePath, wedgePaint)
        }

        val text = overrideText ?: formatAngle(angleDegrees)
        val baseline = textBaseline(centerY)
        val textWidth = textPaintWhite.measureText(text)
        val textX = centerX - textWidth / 2f

        canvas.save()
        canvas.scale(1f, textScaleY, centerX, centerY)
        canvas.drawText(text, textX, baseline, textPaintWhite)
        canvas.restore()

        if (sweepAngle > 0f) {
            canvas.save()
            canvas.clipPath(wedgePath)
            canvas.scale(1f, textScaleY, centerX, centerY)
            canvas.drawText(text, textX, baseline, textPaintBlack)
            canvas.restore()
        }
    }

    private fun textBaseline(centerY: Float): Float {
        val fm = textPaintWhite.fontMetrics
        return centerY - (fm.ascent + fm.descent) / 2f
    }

    private fun formatAngle(angle: Float): String {
        return String.format(Locale.getDefault(), "%.0f°", angle)
    }
}
