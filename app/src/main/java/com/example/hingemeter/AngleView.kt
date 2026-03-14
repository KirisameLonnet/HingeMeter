package com.example.hingemeter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Movie
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.atan2
import kotlin.math.roundToInt

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
    private val minStickerScale = 0.05f
    private val maxStickerScale = 10f
    private var angleDegrees = 0f
    private var overrideText: String? = null
    private var lastAngleDegrees: Float? = null
    private val stickers = mutableListOf<GifSticker>()
    private var activeSticker: GifSticker? = null
    private var isDragging = false
    private var isTransforming = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var lastSpan = 0f
    private var lastRotation = 0f
    private var requestGifPicker: (() -> Unit)? = null
    private val gestureDetector = GestureDetector(context, GestureListener())

    init {
        isClickable = true
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
        val newAngle = angle.coerceIn(0f, 180f)
        val previousAngle = lastAngleDegrees
        angleDegrees = newAngle
        if (previousAngle != null) {
            advanceGifByAngleDelta(newAngle - previousAngle)
        }
        lastAngleDegrees = newAngle
        invalidate()
    }

    fun setOverrideText(text: String?) {
        overrideText = text
        invalidate()
    }

    fun setOnRequestGifPicker(listener: (() -> Unit)?) {
        requestGifPicker = listener
    }

    fun setGif(uri: Uri) {
        val movie = context.contentResolver.openInputStream(uri)?.use { input ->
            Movie.decodeStream(input)
        }
        if (movie != null) {
            addSticker(movie)
        }
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

        drawGifSticker(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handledByGesture = gestureDetector.onTouchEvent(event)
        if (stickers.isEmpty()) {
            return handledByGesture || super.onTouchEvent(event)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val sticker = findStickerAt(event.x, event.y)
                if (sticker != null) {
                    setActiveSticker(sticker)
                    isDragging = true
                    isTransforming = false
                    lastTouchX = event.x
                    lastTouchY = event.y
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    val focusX = (event.getX(0) + event.getX(1)) / 2f
                    val focusY = (event.getY(0) + event.getY(1)) / 2f
                    val sticker = activeSticker ?: findStickerAt(focusX, focusY)
                    if (sticker != null) {
                        setActiveSticker(sticker)
                        isTransforming = true
                        isDragging = false
                        lastSpan = pointerSpan(event)
                        lastRotation = pointerRotation(event)
                        lastFocusX = focusX
                        lastFocusY = focusY
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isTransforming && event.pointerCount >= 2) {
                    val sticker = activeSticker
                    if (sticker != null) {
                        val focusX = (event.getX(0) + event.getX(1)) / 2f
                        val focusY = (event.getY(0) + event.getY(1)) / 2f
                        val span = pointerSpan(event)
                        val rotation = pointerRotation(event)
                        val scaleFactor = if (lastSpan > 0f) span / lastSpan else 1f
                        sticker.scale = (sticker.scale * scaleFactor).coerceIn(
                            minStickerScale,
                            maxStickerScale
                        )
                        val rotationDelta = normalizeRotationDelta(rotation - lastRotation)
                        sticker.rotationDegrees += rotationDelta
                        val centerX = stickerCenterX(sticker)
                        val centerY = stickerCenterY(sticker)
                        updateStickerCenterPercent(
                            sticker,
                            centerX + (focusX - lastFocusX),
                            centerY + (focusY - lastFocusY)
                        )
                        lastSpan = span
                        lastRotation = rotation
                        lastFocusX = focusX
                        lastFocusY = focusY
                        invalidate()
                        return true
                    }
                } else if (isDragging) {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    val sticker = activeSticker
                    if (sticker != null) {
                        val centerX = stickerCenterX(sticker)
                        val centerY = stickerCenterY(sticker)
                        updateStickerCenterPercent(sticker, centerX + dx, centerY + dy)
                    }
                    lastTouchX = event.x
                    lastTouchY = event.y
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                isTransforming = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount <= 2) {
                    isTransforming = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
        }
        return handledByGesture || super.onTouchEvent(event)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            stickers.filter { !it.initialized }.forEach { initializeSticker(it) }
        }
    }

    private fun textBaseline(centerY: Float): Float {
        val fm = textPaintWhite.fontMetrics
        return centerY - (fm.ascent + fm.descent) / 2f
    }

    private fun formatAngle(angle: Float): String {
        return String.format(Locale.getDefault(), "%.0f°", angle)
    }

    private fun drawGifSticker(canvas: Canvas) {
        if (stickers.isEmpty()) return
        for (sticker in stickers) {
            if (!sticker.initialized) {
                initializeSticker(sticker)
            }
            val duration = sticker.durationMs
            val loopDuration = if (duration > 0) duration else 1000
            val normalizedTime = ((sticker.timeMs % loopDuration) + loopDuration) % loopDuration
            sticker.movie.setTime(normalizedTime.toInt())

            val movieWidth = sticker.movie.width().toFloat()
            val movieHeight = sticker.movie.height().toFloat()
            val centerX = stickerCenterX(sticker)
            val centerY = stickerCenterY(sticker)

            canvas.save()
            canvas.translate(centerX, centerY)
            canvas.rotate(sticker.rotationDegrees)
            canvas.scale(sticker.scale, sticker.scale)
            sticker.movie.draw(canvas, -movieWidth / 2f, -movieHeight / 2f)
            canvas.restore()
        }
    }

    private fun addSticker(movie: Movie) {
        val sticker = GifSticker(
            movie = movie,
            durationMs = movie.duration(),
            timeMs = 0f,
            centerXPercent = 0.5f,
            centerYPercent = 0.5f,
            scale = 1f,
            initialized = false
        )
        if (width > 0 && height > 0) {
            initializeSticker(sticker)
        }
        stickers.add(sticker)
        setActiveSticker(sticker)
    }

    private fun initializeSticker(sticker: GifSticker) {
        val movieWidth = sticker.movie.width().toFloat().coerceAtLeast(1f)
        val targetWidth = width * 0.4f
        sticker.scale = (targetWidth / movieWidth).coerceIn(minStickerScale, maxStickerScale)
        sticker.centerXPercent = 0.5f
        sticker.centerYPercent = 0.5f
        sticker.initialized = true
    }

    private fun advanceGifByAngleDelta(deltaAngle: Float) {
        if (deltaAngle == 0f) return
        for (sticker in stickers) {
            val duration = sticker.durationMs
            val loopDuration = if (duration > 0) duration else 1000
            val msPerDegree = loopDuration / 30f
            sticker.timeMs += deltaAngle * msPerDegree
        }
    }

    private fun hitTestSticker(x: Float, y: Float, sticker: GifSticker): Boolean {
        val centerX = stickerCenterX(sticker)
        val centerY = stickerCenterY(sticker)
        val dx = x - centerX
        val dy = y - centerY
        val radians = Math.toRadians(sticker.rotationDegrees.toDouble())
        val cos = kotlin.math.cos(radians).toFloat()
        val sin = kotlin.math.sin(radians).toFloat()
        val localX = dx * cos + dy * sin
        val localY = -dx * sin + dy * cos
        val halfWidth = sticker.movie.width() * sticker.scale / 2f
        val halfHeight = sticker.movie.height() * sticker.scale / 2f
        return localX in -halfWidth..halfWidth && localY in -halfHeight..halfHeight
    }

    private fun findStickerAt(x: Float, y: Float): GifSticker? {
        for (index in stickers.indices.reversed()) {
            val sticker = stickers[index]
            if (hitTestSticker(x, y, sticker)) {
                return sticker
            }
        }
        return null
    }

    private fun setActiveSticker(sticker: GifSticker) {
        activeSticker = sticker
        if (stickers.lastOrNull() != sticker) {
            stickers.remove(sticker)
            stickers.add(sticker)
        }
    }

    private fun stickerCenterX(sticker: GifSticker): Float {
        return if (width > 0) width * sticker.centerXPercent else 0f
    }

    private fun stickerCenterY(sticker: GifSticker): Float {
        return if (height > 0) height * sticker.centerYPercent else 0f
    }

    private fun updateStickerCenterPercent(sticker: GifSticker, centerX: Float, centerY: Float) {
        if (width > 0) {
            sticker.centerXPercent = (centerX / width).coerceIn(0f, 1f)
        }
        if (height > 0) {
            sticker.centerYPercent = (centerY / height).coerceIn(0f, 1f)
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            requestGifPicker?.invoke()
            return true
        }
    }

    private fun pointerSpan(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val dx = event.getX(1) - event.getX(0)
        val dy = event.getY(1) - event.getY(0)
        return hypot(dx, dy)
    }

    private fun pointerRotation(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val dx = event.getX(1) - event.getX(0)
        val dy = event.getY(1) - event.getY(0)
        return Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
    }

    private fun normalizeRotationDelta(delta: Float): Float {
        var normalized = delta
        while (normalized > 180f) normalized -= 360f
        while (normalized < -180f) normalized += 360f
        return normalized
    }

    private data class GifSticker(
        val movie: Movie,
        var durationMs: Int,
        var timeMs: Float,
        var centerXPercent: Float,
        var centerYPercent: Float,
        var scale: Float,
        var rotationDegrees: Float = 0f,
        var initialized: Boolean
    )
}
