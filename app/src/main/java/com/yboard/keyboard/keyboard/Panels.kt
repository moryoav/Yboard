package com.yboard.keyboard.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.yboard.keyboard.R
import com.yboard.keyboard.settings.KeyStyle
import com.yboard.keyboard.settings.KeyboardTheme
import com.yboard.keyboard.settings.Prefs
import com.yboard.keyboard.settings.ThemePresets
import com.yboard.keyboard.settings.YboardSettings
import com.yboard.keyboard.text.EmojiSearch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ---------------------------------------------------------------- helpers

private fun View.dp(v: Float): Int = (v * resources.displayMetrics.density).roundToInt()

private fun rounded(color: Int, radius: Float): Drawable =
    GradientDrawable().apply { cornerRadius = radius; setColor(color) }

private fun keyFill(t: KeyboardTheme, functional: Boolean): Int {
    if (t.keyStyle == KeyStyle.FLAT) return ThemeRender.withAlpha(t.textColor, 0.12f)
    val base = if (functional) t.specialKeyColor else t.keyColor
    return ThemeRender.withAlpha(base, max(t.keyOpacity, 0.18f))
}

/** Text size in sp, and the keyboard's UI font (Google Sans). */
private fun TextView.sp(v: Float) {
    setTextSize(TypedValue.COMPLEX_UNIT_SP, v)
    typeface = Fonts.regular(context)
}

// ---------------------------------------------------------------- emoji

@SuppressLint("ViewConstructor")
class EmojiPanel(
    context: Context,
    private val onEmoji: (String) -> Unit,
    private val onBackspace: () -> Unit,
    private val onSpace: () -> Unit,
    private val onClose: () -> Unit,
    private val onSearch: () -> Unit,
) : LinearLayout(context), ThemedPanel {

    private val tabs = LinearLayout(context)
    private val searchButton = ImageView(context)
    private val grid = GridView(context)
    private val bottom = LinearLayout(context)
    private val abc = TextView(context)
    private val space = View(context)
    private val back = ImageView(context)
    private val tabViews = ArrayList<TextView>()
    private var settings = Prefs.current
    private val recents = Prefs.recentEmoji()
    private val kaomojiTab = EmojiData.categories.size + 1
    private var current = 0
    private var items: List<String> = emptyList()
    private val handler = Handler(Looper.getMainLooper())
    private val repeat = object : Runnable {
        override fun run() { onBackspace(); handler.postDelayed(this, 60L) }
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val tv = (convertView as? TextView) ?: TextView(context).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48f))
            }
            val kao = current == kaomojiTab
            tv.sp(if (kao) 15f else 27f)
            tv.setTextColor(settings.theme.textColor)
            tv.text = items[position]
            return tv
        }
    }

    init {
        orientation = VERTICAL
        tabs.orientation = HORIZONTAL
        tabs.gravity = Gravity.CENTER_VERTICAL
        searchButton.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_search))
        searchButton.scaleType = ImageView.ScaleType.CENTER_INSIDE
        searchButton.setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
        searchButton.setOnClickListener { onSearch() }
        tabs.addView(searchButton, LayoutParams(dp(42f), dp(38f)).apply { setMargins(dp(4f), dp(4f), dp(2f), dp(2f)) })
        val icons = listOf("🕘") + EmojiData.categories.map { it.icon } + listOf("ツ")
        icons.forEachIndexed { i, icon ->
            val tv = TextView(context).apply {
                text = icon
                gravity = Gravity.CENTER
                sp(if (i == kaomojiTab) 16f else 19f)
                setOnClickListener { select(i) }
            }
            tabViews.add(tv)
            tabs.addView(tv, LayoutParams(0, dp(38f), 1f).apply { setMargins(dp(1f), dp(4f), dp(1f), dp(2f)) })
        }
        addView(tabs, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        grid.stretchMode = GridView.STRETCH_COLUMN_WIDTH
        grid.selector = ColorDrawable(0)
        grid.isVerticalScrollBarEnabled = false
        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, pos, _ ->
            val e = items.getOrNull(pos) ?: return@setOnItemClickListener
            onEmoji(e)
            if (current != kaomojiTab) {
                recents.remove(e); recents.add(0, e)
                while (recents.size > 32) recents.removeAt(recents.size - 1)
                Prefs.saveRecentEmoji(recents)
            }
        }
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        bottom.orientation = HORIZONTAL
        bottom.setPadding(dp(4f), dp(4f), dp(4f), dp(6f))
        abc.text = "ABC"
        abc.gravity = Gravity.CENTER
        abc.sp(14f)
        abc.setOnClickListener { onClose() }
        space.setOnClickListener { onSpace() }
        back.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_backspace))
        back.scaleType = ImageView.ScaleType.CENTER_INSIDE
        back.setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
        back.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { v.isPressed = true; onBackspace(); handler.postDelayed(repeat, 400L) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.isPressed = false; handler.removeCallbacks(repeat) }
            }
            true
        }
        bottom.addView(abc, LayoutParams(0, dp(44f), 1.6f).apply { setMargins(dp(3f), 0, dp(3f), 0) })
        bottom.addView(space, LayoutParams(0, dp(44f), 5f).apply { setMargins(dp(3f), 0, dp(3f), 0) })
        bottom.addView(back, LayoutParams(0, dp(44f), 1.6f).apply { setMargins(dp(3f), 0, dp(3f), 0) })
        addView(bottom, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        select(if (recents.isEmpty()) 1 else 0)
    }

    private fun select(i: Int) {
        current = i
        items = when (i) {
            0 -> recents.toList()
            kaomojiTab -> EmojiSearch.kaomoji(context)
            else -> EmojiData.categories[i - 1].emojis
        }
        grid.numColumns = if (i == kaomojiTab) 3 else 8
        adapter.notifyDataSetChanged()
        grid.setSelection(0)
        styleTabs()
    }

    private fun styleTabs() {
        val t = settings.theme
        tabViews.forEachIndexed { i, tv ->
            tv.alpha = if (i == current) 1f else 0.55f
            tv.setTextColor(t.textColor)
            tv.background = if (i == current) rounded(ThemeRender.withAlpha(t.accentColor, 0.3f), dp(19f).toFloat()) else null
        }
    }

    override fun applyTheme(s: YboardSettings) {
        settings = s
        val t = s.theme
        val r = dp(t.cornerRadius).toFloat()
        abc.setTextColor(t.textColor)
        abc.typeface = ThemeRender.typeface(context, t)
        abc.background = rounded(keyFill(t, true), r)
        space.background = rounded(keyFill(t, false), r)
        back.background = rounded(keyFill(t, true), r)
        back.imageTintList = ColorStateList.valueOf(t.textColor)
        searchButton.imageTintList = ColorStateList.valueOf(t.textColor)
        searchButton.background = rounded(keyFill(t, true), dp(19f).toFloat())
        styleTabs()
        adapter.notifyDataSetChanged()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(repeat)
    }
}

/**
 * Shown above the keys while searching emoji: a search box (filled by typing on the keys)
 * and a row of matching emoji.
 */
@SuppressLint("ViewConstructor")
class EmojiSearchBar(
    context: Context,
    private val onPick: (String) -> Unit,
    private val onBack: () -> Unit,
) : LinearLayout(context), ThemedPanel {

    private val backButton = ImageView(context)
    private val queryView = TextView(context)
    private val results = LinearLayout(context)
    private val scroll = HorizontalScrollView(context)
    private val message = TextView(context)
    private var settings = Prefs.current
    private var query = ""
    private var found: List<String> = emptyList()

    init {
        orientation = VERTICAL
        val top = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6f), dp(6f), dp(10f), dp(2f))
        }
        backButton.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_back))
        backButton.scaleType = ImageView.ScaleType.CENTER_INSIDE
        backButton.setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
        backButton.setOnClickListener { onBack() }
        top.addView(backButton, LayoutParams(dp(40f), dp(40f)))
        queryView.sp(16f)
        queryView.maxLines = 1
        queryView.ellipsize = TextUtils.TruncateAt.START
        queryView.gravity = Gravity.CENTER_VERTICAL
        queryView.setPadding(dp(16f), 0, dp(16f), 0)
        top.addView(queryView, LayoutParams(0, dp(40f), 1f).apply { leftMargin = dp(6f) })
        addView(top, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        results.orientation = HORIZONTAL
        results.setPadding(dp(8f), 0, dp(8f), 0)
        scroll.isHorizontalScrollBarEnabled = false
        scroll.addView(results)
        val bottomRow = android.widget.FrameLayout(context)
        bottomRow.addView(scroll, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT, dp(50f)))
        message.sp(13f)
        message.gravity = Gravity.CENTER
        bottomRow.addView(message, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT, dp(50f)))
        addView(bottomRow, LayoutParams(LayoutParams.MATCH_PARENT, dp(50f)))
        update("", emptyList())
    }

    fun update(q: String, list: List<String>) {
        query = q
        found = list
        render()
    }

    private fun render() {
        val t = settings.theme
        val he = settings.activeLanguage == "he"
        queryView.text = if (query.isEmpty()) (if (he) "חיפוש אימוג'י" else "Search emoji") else query
        queryView.setTextColor(if (query.isEmpty()) ThemeRender.withAlpha(t.textColor, 0.55f) else t.textColor)
        results.removeAllViews()
        found.forEach { e ->
            results.addView(TextView(context).apply {
                text = e
                sp(26f)
                gravity = Gravity.CENTER
                setPadding(dp(7f), 0, dp(7f), 0)
                setOnClickListener { onPick(e) }
            }, LayoutParams(LayoutParams.WRAP_CONTENT, dp(50f)))
        }
        scroll.scrollTo(0, 0)
        message.visibility = if (found.isEmpty()) View.VISIBLE else View.GONE
        message.text = when {
            query.isBlank() -> if (he) "הקלידו מילה, למשל: לב" else "Type a word, like: heart"
            else -> if (he) "לא נמצאו אימוג'י" else "No emoji found"
        }
        message.setTextColor(ThemeRender.withAlpha(t.textColor, 0.6f))
    }

    override fun applyTheme(s: YboardSettings) {
        settings = s
        val t = s.theme
        backButton.imageTintList = ColorStateList.valueOf(t.textColor)
        queryView.background = rounded(keyFill(t, false), dp(20f).toFloat())
        render()
    }
}

// ------------------------------------------------------------- clipboard

@SuppressLint("ViewConstructor")
class ClipboardPanel(
    context: Context,
    private val onPick: (String) -> Unit,
    private val onDelete: (String) -> Unit,
    private val onClear: () -> Unit,
) : LinearLayout(context), ThemedPanel {

    private val title = TextView(context)
    private val subtitle = TextView(context)
    private val clear = TextView(context)
    private val list = LinearLayout(context)
    private var settings = Prefs.current
    private var items: List<String> = emptyList()

    init {
        orientation = VERTICAL
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(8f), dp(8f), dp(4f))
        }
        val titles = LinearLayout(context).apply { orientation = VERTICAL }
        title.text = "Clipboard"
        title.sp(16f)
        title.typeface = Fonts.medium(context)
        subtitle.text = "Tap to paste · hold to remove"
        subtitle.sp(12f)
        titles.addView(title); titles.addView(subtitle)
        header.addView(titles, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        clear.text = "Clear all"
        clear.sp(13f)
        clear.setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
        clear.setOnClickListener { onClear() }
        header.addView(clear)
        addView(header)

        list.orientation = VERTICAL
        list.setPadding(dp(10f), dp(4f), dp(10f), dp(10f))
        val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(list) }
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    fun setItems(newItems: List<String>) {
        items = newItems.toList()
        rebuild()
    }

    private fun rebuild() {
        val t = settings.theme
        list.removeAllViews()
        if (items.isEmpty()) {
            list.addView(TextView(context).apply {
                text = "Copy any text and it will show up here."
                gravity = Gravity.CENTER
                sp(14f)
                setTextColor(ThemeRender.withAlpha(t.textColor, 0.7f))
                setPadding(dp(16f), dp(40f), dp(16f), dp(16f))
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            return
        }
        items.forEach { text ->
            val tv = TextView(context).apply {
                this.text = text
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                sp(14f)
                setTextColor(t.textColor)
                typeface = ThemeRender.typeface(context, t)
                setPadding(dp(14f), dp(11f), dp(14f), dp(11f))
                background = rounded(keyFill(t, false), dp(max(t.cornerRadius, 6f)).toFloat())
                setOnClickListener { onPick(text) }
                setOnLongClickListener { onDelete(text); true }
            }
            list.addView(tv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(7f)
            })
        }
    }

    override fun applyTheme(s: YboardSettings) {
        settings = s
        val t = s.theme
        title.setTextColor(t.textColor)
        subtitle.setTextColor(ThemeRender.withAlpha(t.textColor, 0.6f))
        clear.setTextColor(t.textColor)
        clear.background = rounded(keyFill(t, true), dp(18f).toFloat())
        rebuild()
    }
}

// ---------------------------------------------------------------- themes

@SuppressLint("ViewConstructor")
class ThemesPanel(
    context: Context,
    private val onPick: (KeyboardTheme) -> Unit,
    private val onBgOpacity: (Float) -> Unit,
    private val onKeyOpacity: (Float) -> Unit,
    private val onMore: () -> Unit,
) : LinearLayout(context), ThemedPanel {

    private val title = TextView(context)
    private val more = TextView(context)
    private val row = LinearLayout(context)
    private val tiles = ArrayList<Pair<ThemeTileView, TextView>>()
    private var tileThemes: List<KeyboardTheme> = emptyList()
    private val bgLabel = TextView(context)
    private val keyLabel = TextView(context)
    private val bgSeek = SeekBar(context)
    private val keySeek = SeekBar(context)
    private var tracking = false

    init {
        orientation = VERTICAL
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(6f), dp(8f), dp(2f))
        }
        title.text = "Themes"
        title.sp(16f)
        title.typeface = Fonts.medium(context)
        header.addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        more.text = "All settings"
        more.sp(13f)
        more.setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
        more.setOnClickListener { onMore() }
        header.addView(more)
        addView(header)

        row.orientation = HORIZONTAL
        row.setPadding(dp(10f), dp(4f), dp(10f), dp(4f))
        val hs = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(row) }
        addView(hs, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        fun sliderRow(label: TextView, text: String, seek: SeekBar, cb: (Float) -> Unit) {
            val r = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16f), 0, dp(8f), 0)
            }
            label.text = text
            label.sp(13f)
            r.addView(label, LayoutParams(dp(96f), LayoutParams.WRAP_CONTENT))
            seek.max = 100
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) cb(p / 100f)
                }
                override fun onStartTrackingTouch(sb: SeekBar) { tracking = true }
                override fun onStopTrackingTouch(sb: SeekBar) { tracking = false }
            })
            r.addView(seek, LayoutParams(0, dp(40f), 1f))
            addView(r, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        sliderRow(bgLabel, "Background", bgSeek, onBgOpacity)
        sliderRow(keyLabel, "Keys", keySeek, onKeyOpacity)
    }

    private fun buildTiles(s: YboardSettings) {
        val themes = ThemePresets.visible + s.savedThemes
        if (themes.map { it.id } == tileThemes.map { it.id }) return
        tileThemes = themes
        row.removeAllViews(); tiles.clear()
        themes.forEach { theme ->
            val col = LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(4f), 0, dp(4f), 0)
            }
            val tile = ThemeTileView(context).apply { this.theme = theme }
            tile.setOnClickListener { onPick(theme) }
            val name = TextView(context).apply {
                text = theme.name
                sp(11f)
                gravity = Gravity.CENTER
                maxLines = 1
            }
            col.addView(tile, LayoutParams(dp(100f), dp(64f)))
            col.addView(name, LayoutParams(dp(100f), LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3f) })
            row.addView(col)
            tiles.add(tile to name)
        }
    }

    override fun applyTheme(s: YboardSettings) {
        buildTiles(s)
        val t = s.theme
        title.setTextColor(t.textColor)
        more.setTextColor(t.textColor)
        more.background = rounded(keyFill(t, true), dp(18f).toFloat())
        bgLabel.setTextColor(t.textColor)
        keyLabel.setTextColor(t.textColor)
        tiles.forEachIndexed { i, (tile, name) ->
            tile.selectedTile = tileThemes[i].id == t.id
            tile.selectionColor = t.accentColor
            name.setTextColor(ThemeRender.withAlpha(t.textColor, 0.85f))
        }
        val tint = ColorStateList.valueOf(t.accentColor)
        listOf(bgSeek, keySeek).forEach {
            it.progressTintList = tint
            it.thumbTintList = tint
            it.progressBackgroundTintList = ColorStateList.valueOf(ThemeRender.withAlpha(t.textColor, 0.35f))
        }
        if (!tracking) {
            bgSeek.progress = (t.bgOpacity * 100).roundToInt()
            keySeek.progress = (t.keyOpacity * 100).roundToInt()
        }
    }
}

// --------------------------------------------------------- text editing

/** A small grid of key-like buttons. Used for the text-editing panel. */
@SuppressLint("ViewConstructor")
class GridPadView(
    context: Context,
    private val cols: Int,
    private val rows: Int,
    private val cells: List<Cell>,
) : View(context), ThemedPanel {

    data class Cell(
        val id: Int, val col: Float, val row: Float, val w: Float = 1f, val h: Float = 1f,
        val label: String? = null, val icon: Int = 0, val repeat: Boolean = false, val functional: Boolean = false,
    )

    var onCell: ((Int) -> Unit)? = null
    var toggled: Set<Int> = emptySet()
        set(v) { field = v; invalidate() }

    private var settings = Prefs.current
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val feedback = Feedback(context)
    private val handler = Handler(Looper.getMainLooper())
    private var pressed: Cell? = null
    private val icons = HashMap<Int, Drawable>()
    private val rect = RectF()
    private val repeatRunnable = object : Runnable {
        override fun run() {
            pressed?.let { onCell?.invoke(it.id) }
            handler.postDelayed(this, 55L)
        }
    }

    private fun cellRect(c: Cell, out: RectF) {
        val pad = dp(6f).toFloat()
        val gap = dp(5f).toFloat()
        val cw = (width - pad * 2) / cols
        val ch = (height - pad * 2) / rows
        out.set(
            pad + c.col * cw + gap / 2, pad + c.row * ch + gap / 2,
            pad + (c.col + c.w) * cw - gap / 2, pad + (c.row + c.h) * ch - gap / 2
        )
    }

    override fun onDraw(canvas: Canvas) {
        val t = settings.theme
        val rad = min(dp(t.cornerRadius).toFloat(), dp(20f).toFloat())
        text.typeface = ThemeRender.typeface(context, t)
        for (c in cells) {
            cellRect(c, rect)
            val on = c.id in toggled
            paint.color = if (on) t.accentColor else keyFill(t, c.functional)
            canvas.drawRoundRect(rect, rad, rad, paint)
            if (c === pressed) {
                paint.color = ThemeRender.withAlpha(t.textColor, 0.16f)
                canvas.drawRoundRect(rect, rad, rad, paint)
            }
            val fg = if (on) t.accentTextColor else t.textColor
            if (c.icon != 0) {
                val d = icons.getOrPut(c.icon) { ContextCompat.getDrawable(context, c.icon)!!.mutate() }
                val s = dp(24f) / 2
                d.setBounds((rect.centerX() - s).toInt(), (rect.centerY() - s).toInt(), (rect.centerX() + s).toInt(), (rect.centerY() + s).toInt())
                d.setTint(fg)
                d.draw(canvas)
            } else if (c.label != null) {
                text.textSize = dp(14f) * t.fontScale
                text.color = fg
                val fm = text.fontMetrics
                canvas.drawText(c.label, rect.centerX(), rect.centerY() - (fm.ascent + fm.descent) / 2, text)
            }
        }
    }

    private fun cellAt(x: Float, y: Float): Cell? = cells.firstOrNull { cellRect(it, rect); rect.contains(x, y) }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val c = cellAt(ev.x, ev.y) ?: return true
                pressed = c
                feedback.keyDown(null, settings)
                if (c.repeat) {
                    onCell?.invoke(c.id)
                    handler.postDelayed(repeatRunnable, 400L)
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val c = pressed
                handler.removeCallbacks(repeatRunnable)
                pressed = null
                if (c != null && !c.repeat && cellAt(ev.x, ev.y) === c) onCell?.invoke(c.id)
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(repeatRunnable)
                pressed = null
                invalidate()
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(repeatRunnable)
    }

    override fun applyTheme(s: YboardSettings) {
        settings = s
        invalidate()
    }

    companion object {
        const val UP = 1; const val DOWN = 2; const val LEFT = 3; const val RIGHT = 4
        const val SELECT = 5; const val SELECT_ALL = 6; const val COPY = 7; const val CUT = 8
        const val PASTE = 9; const val HOME = 10; const val END = 11; const val BACKSPACE = 12
        const val UNDO = 13; const val REDO = 14

        fun editPanel(context: Context): GridPadView = GridPadView(
            context, cols = 4, rows = 4, cells = listOf(
                Cell(UNDO, 0f, 0f, label = "Undo", functional = true),
                Cell(UP, 1f, 0f, icon = R.drawable.ic_chevron_up, repeat = true),
                Cell(REDO, 2f, 0f, label = "Redo", functional = true),
                Cell(SELECT_ALL, 3f, 0f, label = "Select all", functional = true),
                Cell(LEFT, 0f, 1f, icon = R.drawable.ic_chevron_left, repeat = true),
                Cell(SELECT, 1f, 1f, label = "Select"),
                Cell(RIGHT, 2f, 1f, icon = R.drawable.ic_chevron_right, repeat = true),
                Cell(COPY, 3f, 1f, label = "Copy", functional = true),
                Cell(HOME, 0f, 2f, label = "Home", functional = true),
                Cell(DOWN, 1f, 2f, icon = R.drawable.ic_chevron_down, repeat = true),
                Cell(END, 2f, 2f, label = "End", functional = true),
                Cell(CUT, 3f, 2f, label = "Cut", functional = true),
                Cell(PASTE, 0f, 3f, w = 3f, label = "Paste"),
                Cell(BACKSPACE, 3f, 3f, icon = R.drawable.ic_backspace, repeat = true, functional = true),
            )
        )
    }
}
