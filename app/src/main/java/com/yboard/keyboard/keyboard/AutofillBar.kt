package com.yboard.keyboard.keyboard

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import kotlin.math.roundToInt

/**
 * The strip of logins and codes from the phone's autofill service (password manager, codes
 * from texts). It takes the toolbar's place while there's something to offer. The chips are
 * drawn by Android itself, so Yboard never sees the passwords.
 */
class AutofillBar(context: Context) : LinearLayout(context) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = (v * density).roundToInt()

    private val row = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val scroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }
    /** Chips the service wants always visible (like Google's key icon), kept at the end. */
    private val pinnedRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8f), 0, dp(4f), 0)
        addView(scroll, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(pinnedRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }

    val hasChips: Boolean get() = row.childCount + pinnedRow.childCount > 0

    fun setChips(chips: List<View>, pinned: List<View>) {
        row.removeAllViews()
        pinnedRow.removeAllViews()
        fun lp() = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6f) }
        chips.forEach { (it.parent as? android.view.ViewGroup)?.removeView(it); row.addView(it, lp()) }
        pinned.forEach { (it.parent as? android.view.ViewGroup)?.removeView(it); pinnedRow.addView(it, lp()) }
        scroll.scrollTo(0, 0)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Same height as the toolbar, so the keys don't jump.
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(46f), MeasureSpec.EXACTLY))
    }
}
