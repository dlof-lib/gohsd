package com.gohsd.app

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** أندرويد 15 يفرض العرض خلف أشرطة النظام؛ نضيف الحشوة المناسبة (الشريط العلوي/السفلي/القطع/الكيبورد). */
fun View.applySystemBarsPadding() {
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val b = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout() or
                WindowInsetsCompat.Type.ime()
        )
        v.setPadding(b.left, b.top, b.right, b.bottom)
        insets
    }
}
