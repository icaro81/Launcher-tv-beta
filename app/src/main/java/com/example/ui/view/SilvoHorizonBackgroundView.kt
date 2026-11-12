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
 * Subtle dynamic 3D Glass ("Cristal 3D") cinematic background view for Silvo Horizon.
 * Combines multi-layered perspective glass planes, beveled specular edges, and a curved
 * refractive glass horizon stage that smoothly adapts to the centered app's color.
 */
class SilvoHorizonBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val baseColor = Color.parseColor("#06090D")
    private val defaultAccent = Color.parseColor("#00E5FF")

    private var currentAccentColor: Int = defaultAccent
    private var targetAccentColor: Int = defaultAccent
    private var colorAnimator: ValueAnimator? = null
    private val argbEvaluator = ArgbEvaluator()

    // Paints
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val radialGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // 3D Glass Facet Planes
    private val leftGlassFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val leftGlassBevelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f
    }
    private val rightGlassFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rightGlassBevelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f
    }
    private val centerGlassPanelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val centerGlassBevelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val specularSheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // 3D Curved Glass Horizon Stage
    private val horizonFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val horizonDepthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f
    }
    private val horizonStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val horizonInnerRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f
    }

    // Pre-allocated Paths
    private val leftGlassPath = Path()
    private val rightGlassPath = Path()
    private val centerGlassPath = Path()
    private val specularSheenPath = Path()
    private val horizonFillPath = Path()
    private val horizonLinePath = Path()
    private val horizonDepthPath = Path()
    private val horizonInnerRimPath = Path()

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
            rebuildPaths(w, h)
            rebuildShaders(w, h)
        }
    }

    private fun rebuildPaths(w: Int, h: Int) {
        val wf = w.toFloat()
        val hf = h.toFloat()

        // 1. Left angled 3D glass prism pane
        leftGlassPath.reset()
        leftGlassPath.moveTo(-wf * 0.04f, hf * 0.10f)
        leftGlassPath.lineTo(wf * 0.34f, hf * 0.04f)
        leftGlassPath.lineTo(wf * 0.24f, hf * 0.78f)
        leftGlassPath.lineTo(-wf * 0.06f, hf * 0.68f)
        leftGlassPath.close()

        // 2. Right angled 3D glass prism pane
        rightGlassPath.reset()
        rightGlassPath.moveTo(wf * 0.66f, hf * 0.06f)
        rightGlassPath.lineTo(wf * 1.04f, hf * 0.14f)
        rightGlassPath.lineTo(wf * 1.06f, hf * 0.70f)
        rightGlassPath.lineTo(wf * 0.75f, hf * 0.78f)
        rightGlassPath.close()

        // 3. Center floating 3D frosted glass card behind carousel
        val cLeft = wf * 0.14f
        val cRight = wf * 0.86f
        val cTop = hf * 0.24f
        val cBottom = hf * 0.72f
        val corner = minOf(wf, hf) * 0.045f
        centerGlassPath.reset()
        centerGlassPath.addRoundRect(cLeft, cTop, cRight, cBottom, corner, corner, Path.Direction.CW)

        // 4. Diagonal glossy light reflection sweep across the glass surface
        specularSheenPath.reset()
        specularSheenPath.moveTo(wf * 0.18f, 0f)
        specularSheenPath.lineTo(wf * 0.42f, 0f)
        specularSheenPath.lineTo(wf * 0.14f, hf)
        specularSheenPath.lineTo(-wf * 0.05f, hf)
        specularSheenPath.close()

        // 5. 3D Curved Glass Horizon Stage
        val horizonControlY = hf * 0.60f

        horizonFillPath.reset()
        horizonFillPath.moveTo(-wf * 0.1f, hf)
        horizonFillPath.quadTo(wf * 0.5f, horizonControlY, wf * 1.1f, hf)
        horizonFillPath.close()

        horizonLinePath.reset()
        horizonLinePath.moveTo(-wf * 0.05f, hf)
        horizonLinePath.quadTo(wf * 0.5f, horizonControlY + 6f, wf * 1.05f, hf)

        // Glass thickness & inner bevel reflection beneath the main curve
        horizonDepthPath.reset()
        horizonDepthPath.moveTo(-wf * 0.05f, hf + 6f)
        horizonDepthPath.quadTo(wf * 0.5f, horizonControlY + 14f, wf * 1.05f, hf + 6f)

        horizonInnerRimPath.reset()
        horizonInnerRimPath.moveTo(wf * 0.04f, hf)
        horizonInnerRimPath.quadTo(wf * 0.5f, horizonControlY + 24f, wf * 0.96f, hf)
    }

    private fun rebuildShaders(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val wf = w.toFloat()
        val hf = h.toFloat()

        // Deep atmospheric base gradient
        val topTint = ColorUtils.blendARGB(baseColor, currentAccentColor, 0.13f)
        val midTint = ColorUtils.blendARGB(Color.parseColor("#0B1119"), currentAccentColor, 0.19f)
        val bottomTint = ColorUtils.blendARGB(Color.parseColor("#05070B"), currentAccentColor, 0.07f)

        basePaint.shader = LinearGradient(
            0f, 0f, 0f, hf,
            intArrayOf(topTint, midTint, bottomTint),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP
        )

        // Centered refractive radial spotlight behind the carousel
        val glowCenter = ColorUtils.setAlphaComponent(currentAccentColor, 72)
        val glowMid = ColorUtils.setAlphaComponent(currentAccentColor, 26)
        radialGlowPaint.shader = RadialGradient(
            wf * 0.5f,
            hf * 0.47f,
            wf * 0.46f,
            intArrayOf(glowCenter, glowMid, Color.TRANSPARENT),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP
        )

        // Left & right 3D glass prism planes
        val glassBright = ColorUtils.setAlphaComponent(
            ColorUtils.blendARGB(Color.WHITE, currentAccentColor, 0.45f), 26
        )
        val glassMid = ColorUtils.setAlphaComponent(currentAccentColor, 14)
        val glassFade = ColorUtils.setAlphaComponent(baseColor, 4)

        leftGlassFillPaint.shader = LinearGradient(
            0f, 0f, wf * 0.34f, hf * 0.78f,
            intArrayOf(glassBright, glassMid, glassFade),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        leftGlassBevelPaint.shader = LinearGradient(
            0f, 0f, wf * 0.34f, hf * 0.78f,
            intArrayOf(
                ColorUtils.setAlphaComponent(Color.WHITE, 64),
                ColorUtils.setAlphaComponent(currentAccentColor, 44),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )

        rightGlassFillPaint.shader = LinearGradient(
            wf, 0f, wf * 0.66f, hf * 0.78f,
            intArrayOf(glassBright, glassMid, glassFade),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        rightGlassBevelPaint.shader = LinearGradient(
            wf * 0.66f, 0f, wf, hf * 0.78f,
            intArrayOf(
                ColorUtils.setAlphaComponent(currentAccentColor, 44),
                ColorUtils.setAlphaComponent(Color.WHITE, 68),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )

        // Center floating 3D frosted glass card
        centerGlassPanelPaint.shader = LinearGradient(
            wf * 0.14f, hf * 0.24f, wf * 0.86f, hf * 0.72f,
            intArrayOf(
                ColorUtils.setAlphaComponent(Color.WHITE, 22),
                ColorUtils.setAlphaComponent(currentAccentColor, 18),
                ColorUtils.setAlphaComponent(Color.BLACK, 28)
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        centerGlassBevelPaint.shader = LinearGradient(
            wf * 0.14f, hf * 0.24f, wf * 0.86f, hf * 0.72f,
            intArrayOf(
                ColorUtils.setAlphaComponent(Color.WHITE, 82),
                ColorUtils.setAlphaComponent(currentAccentColor, 58),
                ColorUtils.setAlphaComponent(Color.BLACK, 45)
            ),
            floatArrayOf(0f, 0.48f, 1f),
            Shader.TileMode.CLAMP
        )

        // Diagonal glossy light sheen across the glass scene
        specularSheenPaint.shader = LinearGradient(
            0f, 0f, wf * 0.42f, hf,
            intArrayOf(
                ColorUtils.setAlphaComponent(Color.WHITE, 22),
                ColorUtils.setAlphaComponent(Color.WHITE, 7),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )

        // Curved 3D Glass Horizon Stage
        val horizonTop = ColorUtils.setAlphaComponent(
            ColorUtils.blendARGB(currentAccentColor, Color.WHITE, 0.22f), 62
        )
        val horizonMid = ColorUtils.setAlphaComponent(currentAccentColor, 24)
        val horizonBottom = ColorUtils.setAlphaComponent(baseColor, 20)
        horizonFillPaint.shader = LinearGradient(
            0f, hf * 0.60f, 0f, hf,
            intArrayOf(horizonTop, horizonMid, horizonBottom),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )

        // 3D Glass slab thickness shadow beneath horizon curve
        horizonDepthPaint.shader = LinearGradient(
            0f, hf * 0.75f, wf, hf * 0.75f,
            intArrayOf(
                Color.TRANSPARENT,
                ColorUtils.setAlphaComponent(currentAccentColor, 34),
                ColorUtils.setAlphaComponent(currentAccentColor, 65),
                ColorUtils.setAlphaComponent(currentAccentColor, 34),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f),
            Shader.TileMode.CLAMP
        )

        // Crisp specular rim on the 3D curved glass edge
        val rimMid = ColorUtils.setAlphaComponent(currentAccentColor, 130)
        val rimCenter = ColorUtils.setAlphaComponent(
            ColorUtils.blendARGB(currentAccentColor, Color.WHITE, 0.45f), 225
        )
        horizonStrokePaint.shader = LinearGradient(
            0f, hf * 0.75f, wf, hf * 0.75f,
            intArrayOf(Color.TRANSPARENT, rimMid, rimCenter, rimMid, Color.TRANSPARENT),
            floatArrayOf(0f, 0.24f, 0.5f, 0.76f, 1f),
            Shader.TileMode.CLAMP
        )

        // Inner refractive glass reflection arc
        horizonInnerRimPaint.shader = LinearGradient(
            0f, hf * 0.8f, wf, hf * 0.8f,
            intArrayOf(
                Color.TRANSPARENT,
                ColorUtils.setAlphaComponent(Color.WHITE, 42),
                ColorUtils.setAlphaComponent(Color.WHITE, 82),
                ColorUtils.setAlphaComponent(Color.WHITE, 42),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val wf = width.toFloat()
        val hf = height.toFloat()
        if (wf <= 0f || hf <= 0f) return

        // Base atmospheric gradient + adaptive radial refraction
        canvas.drawRect(0f, 0f, wf, hf, basePaint)
        canvas.drawCircle(wf * 0.5f, hf * 0.47f, wf * 0.46f, radialGlowPaint)

        // 3D Glass facet planes & central frosted glass pane
        canvas.drawPath(leftGlassPath, leftGlassFillPaint)
        canvas.drawPath(leftGlassPath, leftGlassBevelPaint)

        canvas.drawPath(rightGlassPath, rightGlassFillPaint)
        canvas.drawPath(rightGlassPath, rightGlassBevelPaint)

        canvas.drawPath(centerGlassPath, centerGlassPanelPaint)
        canvas.drawPath(centerGlassPath, centerGlassBevelPaint)

        // Specular diagonal reflection sweep
        canvas.drawPath(specularSheenPath, specularSheenPaint)

        // 3D Curved Glass Horizon Stage
        canvas.drawPath(horizonFillPath, horizonFillPaint)
        canvas.drawPath(horizonDepthPath, horizonDepthPaint)
        canvas.drawPath(horizonInnerRimPath, horizonInnerRimPaint)
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
