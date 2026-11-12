package com.example.ui.view

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.graphics.ColorUtils
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Subtle dynamic cinematic background view for Silvo Horizon.
 * Smoothly transitions gradient and horizon glow colors to match the centered app.
 */
class SilvoHorizonBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val baseColor = Color.parseColor("#070A0E")
    private val defaultAccent = Color.parseColor("#00E5FF")

    private var currentAccentColor: Int = defaultAccent
    private var targetAccentColor: Int = defaultAccent
    private var colorAnimator: ValueAnimator? = null
    private val argbEvaluator = ArgbEvaluator()

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val radialGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val horizonFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val horizonStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val horizonFillPath = Path()
    private val horizonLinePath = Path()

    private var lastWidth = 0
    private var lastHeight = 0

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun animateToAccentColor(newAccentColor: Int, durationMs: Long = 550L) {
        if (newAccentColor == targetAccentColor) return
        targetAccentColor = newAccentColor

        colorAnimator?.cancel()
        val startColor = currentAccentColor

        colorAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                val fraction = animator.animatedFraction
                currentAccentColor = argbEvaluator.evaluate(fraction, startColor, newAccentColor) as Int
                rebuildShaders(width, height)
                invalidate()
            }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            lastWidth = w
            lastHeight = h
            rebuildPaths(w, h)
            rebuildShaders(w, h)
        }
    }

    private fun rebuildPaths(w: Int, h: Int) {
        val wf = w.toFloat()
        val hf = h.toFloat()
        val horizonControlY = hf * 0.60f

        horizonFillPath.reset()
        horizonFillPath.moveTo(-wf * 0.1f, hf)
        horizonFillPath.quadTo(wf * 0.5f, horizonControlY, wf * 1.1f, hf)
        horizonFillPath.close()

        horizonLinePath.reset()
        horizonLinePath.moveTo(-wf * 0.05f, hf)
        horizonLinePath.quadTo(wf * 0.5f, horizonControlY + 8f, wf * 1.05f, hf)
    }

    private fun rebuildShaders(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val wf = w.toFloat()
        val hf = h.toFloat()

        // Subtle dark atmospheric vertical gradient tinted gently with the focused app's accent
        val topTint = ColorUtils.blendARGB(baseColor, currentAccentColor, 0.12f)
        val midTint = ColorUtils.blendARGB(Color.parseColor("#0B1017"), currentAccentColor, 0.18f)
        val bottomTint = ColorUtils.blendARGB(Color.parseColor("#06080C"), currentAccentColor, 0.06f)

        basePaint.shader = LinearGradient(
            0f, 0f, 0f, hf,
            intArrayOf(topTint, midTint, bottomTint),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )

        // Soft centered radial glow directly behind the focused carousel item
        val glowCenter = ColorUtils.setAlphaComponent(currentAccentColor, 58)
        val glowMid = ColorUtils.setAlphaComponent(currentAccentColor, 20)
        val glowEdge = Color.TRANSPARENT

        radialGlowPaint.shader = RadialGradient(
            wf * 0.5f,
            hf * 0.48f,
            wf * 0.45f,
            intArrayOf(glowCenter, glowMid, glowEdge),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )

        // Curved Aeon Nox Silvo horizon glow
        val horizonTop = ColorUtils.setAlphaComponent(currentAccentColor, 46)
        val horizonBottom = ColorUtils.setAlphaComponent(baseColor, 18)
        horizonFillPaint.shader = LinearGradient(
            0f, hf * 0.60f, 0f, hf,
            horizonTop,
            horizonBottom,
            Shader.TileMode.CLAMP
        )

        // Subtle horizon rim highlight fading toward left and right screen edges
        val rimTransparent = Color.TRANSPARENT
        val rimMid = ColorUtils.setAlphaComponent(currentAccentColor, 110)
        val rimCenter = ColorUtils.setAlphaComponent(currentAccentColor, 200)
        horizonStrokePaint.shader = LinearGradient(
            0f, hf * 0.75f, wf, hf * 0.75f,
            intArrayOf(rimTransparent, rimMid, rimCenter, rimMid, rimTransparent),
            floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val wf = width.toFloat()
        val hf = height.toFloat()
        if (wf <= 0f || hf <= 0f) return

        canvas.drawRect(0f, 0f, wf, hf, basePaint)
        canvas.drawCircle(wf * 0.5f, hf * 0.48f, wf * 0.45f, radialGlowPaint)
        canvas.drawPath(horizonFillPath, horizonFillPaint)
        canvas.drawPath(horizonLinePath, horizonStrokePaint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        colorAnimator?.cancel()
    }

    companion object {
        private val colorCache = ConcurrentHashMap<String, Int>()
        private val fallbackPalette = intArrayOf(
            Color.parseColor("#00E5FF"),
            Color.parseColor("#2979FF"),
            Color.parseColor("#00E676"),
            Color.parseColor("#FF5252"),
            Color.parseColor("#AA00FF"),
            Color.parseColor("#FFAB00")
        )

        /**
         * Fast, cached dominant-accent extraction from an app icon Drawable.
         * Prefers saturated mid-tone pixels so white/black backgrounds don't dominate.
         */
        fun extractAccentColor(key: String, drawable: Drawable?, isDrawerShortcut: Boolean): Int {
            if (isDrawerShortcut) {
                return Color.parseColor("#00E5FF")
            }
            colorCache[key]?.let { return it }

            val extracted = computeDominantColor(drawable) ?: fallbackColorForKey(key)
            colorCache[key] = extracted
            return extracted
        }

        private fun computeDominantColor(drawable: Drawable?): Int? {
            if (drawable == null) return null
            val sampleSize = 24
            var bitmap: Bitmap? = null
            var createdBitmap = false

            return try {
                val bmp = if (drawable is BitmapDrawable && drawable.bitmap != null && !drawable.bitmap.isRecycled) {
                    Bitmap.createScaledBitmap(drawable.bitmap, sampleSize, sampleSize, true).also {
                        createdBitmap = it != drawable.bitmap
                    }
                } else {
                    Bitmap.createBitmap(sampleSize, sampleSize, Bitmap.Config.ARGB_8888).also {
                        createdBitmap = true
                        val canvas = Canvas(it)
                        drawable.setBounds(0, 0, sampleSize, sampleSize)
                        drawable.draw(canvas)
                    }
                }
                bitmap = bmp

                val pixels = IntArray(sampleSize * sampleSize)
                bmp.getPixels(pixels, 0, sampleSize, 0, 0, sampleSize, sampleSize)

                val hsv = FloatArray(3)
                var bestScore = -1f
                var bestColor = 0
                var sumR = 0f
                var sumG = 0f
                var sumB = 0f
                var totalWeight = 0f

                for (pixel in pixels) {
                    val alpha = Color.alpha(pixel)
                    if (alpha < 140) continue

                    Color.colorToHSV(pixel, hsv)
                    val sat = hsv[1]
                    val value = hsv[2]

                    // Skip near-black, near-white, and flat grey pixels
                    if (sat < 0.20f || value < 0.16f) continue
                    if (value > 0.96f && sat < 0.25f) continue

                    val weight = sat * sat * (1.1f - abs(value - 0.58f))
                    sumR += Color.red(pixel) * weight
                    sumG += Color.green(pixel) * weight
                    sumB += Color.blue(pixel) * weight
                    totalWeight += weight

                    if (weight > bestScore) {
                        bestScore = weight
                        bestColor = pixel
                    }
                }

                if (totalWeight > 0.1f) {
                    val avgColor = Color.rgb(
                        (sumR / totalWeight).toInt().coerceIn(0, 255),
                        (sumG / totalWeight).toInt().coerceIn(0, 255),
                        (sumB / totalWeight).toInt().coerceIn(0, 255)
                    )
                    // Boost vibrancy slightly while keeping color faithful
                    ColorUtils.blendARGB(avgColor, bestColor, 0.35f)
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            } finally {
                if (createdBitmap) {
                    bitmap?.recycle()
                }
            }
        }

        private fun fallbackColorForKey(key: String): Int {
            val index = abs(key.hashCode()) % fallbackPalette.size
            return fallbackPalette[index]
        }
    }
}
