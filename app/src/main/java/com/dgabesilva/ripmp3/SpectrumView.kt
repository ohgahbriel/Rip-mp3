package com.dgabesilva.ripmp3

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.random.Random

/**
 * Winamp-classic style spectrum analyzer: segmented bars (green low, yellow mid,
 * red top) with slowly falling peak caps. Bars are animation-driven (no audio
 * capture permission needed) — they dance while [active] and decay to zero when not.
 */
class SpectrumView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var active = false
        set(value) {
            field = value
            if (value) postInvalidateOnAnimation()
        }

    private val bars = 19
    private val segments = 14
    private val heights = FloatArray(bars)
    private val targets = FloatArray(bars)
    private val peaks = FloatArray(bars)
    private var lastRetarget = 0L
    private val paint = Paint()
    private val density = resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val now = SystemClock.uptimeMillis()
        if (active && now - lastRetarget > 90) {
            retarget()
            lastRetarget = now
        }

        var anyLit = false
        for (i in 0 until bars) {
            heights[i] += if (active) (targets[i] - heights[i]) * 0.35f else -0.05f
            heights[i] = heights[i].coerceIn(0f, 1f)
            if (heights[i] > peaks[i]) peaks[i] = heights[i]
            else peaks[i] = (peaks[i] - 0.010f).coerceAtLeast(0f)
            if (heights[i] > 0.005f || peaks[i] > 0.005f) anyLit = true
        }

        val inset = 5 * density
        val w = width - inset * 2
        val h = height - inset * 2
        if (w > 0 && h > 0) {
            val barW = w / bars
            val cellH = h / segments
            val gap = 1 * density
            for (i in 0 until bars) {
                val x = inset + i * barW
                val lit = (heights[i] * segments).toInt()
                for (s in 0 until lit) {
                    val frac = s / segments.toFloat()
                    paint.color = when {
                        frac < 0.55f -> Color.rgb(0, 232, 0)
                        frac < 0.80f -> Color.rgb(232, 216, 0)
                        else -> Color.rgb(240, 40, 40)
                    }
                    val bottom = inset + h - s * cellH
                    canvas.drawRect(x, bottom - cellH + gap, x + barW - gap, bottom, paint)
                }
                if (peaks[i] > 0.01f) {
                    paint.color = Color.rgb(190, 190, 210)
                    val py = inset + h - peaks[i] * h
                    canvas.drawRect(x, py, x + barW - gap, py + 2 * density, paint)
                }
            }
        }

        if (active || anyLit) postInvalidateOnAnimation()
    }

    private fun retarget() {
        val mid = (bars - 1) / 2f
        for (i in 0 until bars) {
            // Center-weighted so it looks like real program material, not noise
            val falloff = 1f - 0.45f * abs(i - mid) / mid
            val r = Random.nextFloat()
            targets[i] = (r * r) * falloff
        }
    }
}
