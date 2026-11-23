package com.yboard.keyboard.keyboard

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
        fun addChips(container: LinearLayout, views: List<View>) {
            for (chip in views) {
                // InlineContentView has no intrinsic size. Keep the dimensions Android gives it.
                val width = chip.layoutParams?.width ?: LayoutParams.WRAP_CONTENT
                val height = chip.layoutParams?.height ?: LayoutParams.WRAP_CONTENT
                if (width == 0 || height == 0 || chip.visibility != View.VISIBLE) continue
                (chip.parent as? ViewGroup)?.removeView(chip)
                container.addView(chip, LayoutParams(width, height).apply { marginEnd = dp(6f) })
            }
        }
        addChips(row, chips)
        addChips(pinnedRow, pinned)
        scroll.scrollTo(0, 0)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Same height as the toolbar, so the keys don't jump.
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(46f), MeasureSpec.EXACTLY))
    }
}
