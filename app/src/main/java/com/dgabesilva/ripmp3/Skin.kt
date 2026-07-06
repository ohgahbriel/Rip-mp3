package com.dgabesilva.ripmp3

import android.content.Context
import android.util.TypedValue

/** Persisted skin selection. Call [apply] before setContentView in every activity. */
object Skin {
    val NAMES = listOf("CLASSIC GREEN", "ICE BLUE", "HELLFIRE")

    fun get(ctx: Context) =
        ctx.getSharedPreferences("skin", Context.MODE_PRIVATE).getInt("idx", 0).coerceIn(0, 2)

    fun set(ctx: Context, idx: Int) {
        ctx.getSharedPreferences("skin", Context.MODE_PRIVATE).edit().putInt("idx", idx).apply()
    }

    fun apply(activity: android.app.Activity) {
        activity.setTheme(
            when (get(activity)) {
                1 -> R.style.Theme_RipMp3_Ice
                2 -> R.style.Theme_RipMp3_Hellfire
                else -> R.style.Theme_RipMp3
            }
        )
    }

    /** Spectrum analyzer palette per skin: [low, mid, high, peakCap]. */
    fun spectrum(ctx: Context): IntArray = when (get(ctx)) {
        1 -> intArrayOf(0xFF00D8FF.toInt(), 0xFF40A0FF.toInt(), 0xFFC8F4FF.toInt(), 0xFFA8C8E8.toInt())
        2 -> intArrayOf(0xFFC81818.toInt(), 0xFFFF5020.toInt(), 0xFFFFC030.toInt(), 0xFFFFE0A0.toInt())
        else -> intArrayOf(0xFF00E800.toInt(), 0xFFE8D800.toInt(), 0xFFF02828.toInt(), 0xFFBEBED2.toInt())
    }
}

/** Resolve a skin attr (R.attr.skinLcd etc.) to its color in the current theme. */
fun Context.skinColor(attr: Int): Int {
    val tv = TypedValue()
    theme.resolveAttribute(attr, tv, true)
    return tv.data
}
