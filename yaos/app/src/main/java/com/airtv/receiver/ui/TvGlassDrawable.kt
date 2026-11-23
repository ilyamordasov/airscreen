package com.airtv.receiver.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.core.graphics.ColorUtils

internal class TvGlassDrawable(
    private val scale: Float,
    private val tint: Int = VIOLET,
    private val radiusDp: Int = 22,
    private val focused: Boolean = false,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val surface = RectF()
    private var fill: Shader? = null
    private var reflection: Shader? = null
    private var edge: Shader? = null
    private var opacity = 255

    override fun onBoundsChange(bounds: Rect) {
        if (bounds.isEmpty) {
            surface.setEmpty()
            return
        }
        val width = bounds.width().toFloat()
        val height = bounds.height().toFloat()
        val stroke = scale * if (focused) 2f else 1f
        surface.set(bounds)
        surface.inset(stroke / 2, stroke / 2)
        fill = LinearGradient(surface.left, surface.top, surface.right, surface.bottom,
            intArrayOf(ColorUtils.blendARGB(0xEF23223A.toInt(), tint, .17f),
                0xE5161729.toInt(), 0xE21A2242.toInt()), floatArrayOf(0f, .52f, 1f), Shader.TileMode.CLAMP)
        reflection = RadialGradient(surface.left + width * .16f, surface.top - height * .08f,
            maxOf(width * .66f, height * .9f),
            intArrayOf(ColorUtils.setAlphaComponent(tint, if (focused) 84 else 52), 0x00FFFFFF),
            null, Shader.TileMode.CLAMP)
        edge = LinearGradient(surface.left, surface.top, surface.right, surface.top + height * .6f,
            intArrayOf(if (focused) 0xFFFFD7FA.toInt() else 0xB7CBAFFF.toInt(),
                ColorUtils.setAlphaComponent(tint, if (focused) 255 else 154),
                if (focused) 0xFF7954FF.toInt() else 0x90606DB0.toInt()), null, Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        if (surface.isEmpty) return
        val radius = radiusDp * scale
        paint.alpha = opacity
        paint.style = Paint.Style.FILL
        paint.shader = fill
        canvas.drawRoundRect(surface, radius, radius, paint)
        paint.shader = reflection
        canvas.drawRoundRect(surface, radius, radius, paint)
        paint.shader = edge
        paint.style = Paint.Style.STROKE
        if (focused) {
            for (width in 14 downTo 4 step 4) {
                paint.alpha = (22 - width) * opacity / 255
                paint.strokeWidth = width * scale
                canvas.drawRoundRect(surface, radius, radius, paint)
            }
        }
        paint.alpha = opacity
        paint.strokeWidth = scale * if (focused) 2f else 1f
        canvas.drawRoundRect(surface, radius, radius, paint)
        paint.shader = null
    }

    override fun setAlpha(alpha: Int) {
        opacity = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Required by Drawable")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        const val VIOLET = 0xFF9C79F1.toInt()
        const val PINK = 0xFFEB50DC.toInt()
        const val BLUE = 0xFF5889FF.toInt()
    }
}
