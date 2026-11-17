package com.yboard.keyboard.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.os.Build
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.yboard.keyboard.settings.YboardSettings
import kotlin.math.roundToInt

/** Panels shown in place of the keys (emoji, clipboard, themes, text editing). */
interface ThemedPanel {
    fun applyTheme(s: YboardSettings)
}

/** How long switching themes takes to fade over. */
private const val THEME_FADE_MS = 380L

/**
 * The whole keyboard: themed background + toolbar + keys (or a panel).
 * Also used inside the settings app as a live preview.
 */
@SuppressLint("ViewConstructor")
class KeyboardRootView(context: Context, private val preview: Boolean) : LinearLayout(context) {

    val toolbar = ToolbarView(context)
    /** Logins and codes from autofill, shown instead of the toolbar while there are some. */
    val autofillBar = AutofillBar(context)
    val keyboard = KeyboardView(context)
    private val content = FrameLayout(context)
    private val background = BackgroundPainter()
    private val motion = MotionPainter(resources.displayMetrics.density)
    /** The phone is blurring the app behind the keyboard right now (set by the keyboard service). */
    var blurActive = false
        set(v) { if (field != v) { field = v; invalidate() } }
    /** Fixed time for screenshots (null = real time). */
    var motionTimeForTests: Long? = null
    var settings = YboardSettings()
        private set
    var panelView: View? = null
        private set
    private var navInset = 0
    private val density = resources.displayMetrics.density

    init {
        orientation = VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        setWillNotDraw(false)
        addView(toolbar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        autofillBar.visibility = View.GONE
        addView(autofillBar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        content.addView(keyboard, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        if (!preview) {
            ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
                val (pad, extra) = bottomSpace(insets)
                navInset = pad
                keyboard.extraHeight = extra
                updateBottomPadding()
                insets
            }
        }
    }

    /**
     * How much room to leave under the keys for the phone's bottom bar (gesture handle,
     * back / switch-keyboard buttons). Android reports it differently depending on version
     * and navigation mode, so take the largest of the possible sources.
     */
    private fun bottomBarHeight(insets: WindowInsetsCompat): Int {
        // Gesture navigation (Android 13+): the keyboard's own nav bar arrives as a "caption bar".
        // 3-button navigation / Android 11-12: it arrives as the navigation bar.
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.captionBar() or
                WindowInsetsCompat.Type.displayCutout()
        ).bottom
        val tappable = insets.getInsets(WindowInsetsCompat.Type.tappableElement()).bottom
        var bottom = maxOf(bars, tappable)
        if (bottom == 0 && Build.VERSION.SDK_INT >= 30) {
            // Some phones draw their own bar under keyboards; fall back to the nav bar's size.
            bottom = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars()).bottom
        }
        return bottom
    }

    /**
     * Room to leave under the keys, and extra key height. When Yboard has hidden Android's bar
     * under the keyboard (Android 16+), the keyboard keeps the size it had: a small gap stays
     * for the swipe-up area and the rest of the bar's space goes to the keys.
     */
    private fun bottomSpace(insets: WindowInsetsCompat): Pair<Int, Int> {
        val shown = bottomBarHeight(insets)
        if (Build.VERSION.SDK_INT < 30) return shown to 0
        val caption = WindowInsetsCompat.Type.captionBar()
        val bar = insets.getInsetsIgnoringVisibility(caption).bottom
        if (bar <= 0 || insets.isVisible(caption)) return shown to 0
        val gestures = insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures()).bottom
        val pad = maxOf(shown, gestures, (14 * density).roundToInt()).coerceAtMost(bar)
        Log.i("YboardInsets", "bar=$bar shown=$shown gestures=$gestures -> gap=$pad, keys +${bar - pad}")
        return pad to (bar - pad)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!preview) ViewCompat.requestApplyInsets(this)
    }

    // Picking another theme fades from the old look to the new one instead of jumping.
    private var fadeFrom: android.graphics.Bitmap? = null
    private var fadeAlpha = 0f
    private var fadeAnim: android.animation.ValueAnimator? = null
    private val fadePaint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)

    /** A picture of the keyboard as it looks right now, or null if it isn't on screen. */
    private fun snapshot(): android.graphics.Bitmap? {
        if (!isAttachedToWindow || !isShown || width <= 0 || height <= 0) return null
        return try {
            android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                .also { draw(android.graphics.Canvas(it)) }
        } catch (e: Throwable) {
            null  // out of memory or similar: just switch without the fade
        }
    }

    private fun startFade(from: android.graphics.Bitmap) {
        fadeAnim?.cancel()
        fadeFrom = from
        fadeAlpha = 1f
        fadeAnim = android.animation.ValueAnimator.ofFloat(1f, 0f).apply {
            duration = THEME_FADE_MS
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { fadeAlpha = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (fadeAnim === animation) { fadeFrom = null; fadeAnim = null; invalidate() }
                }
            })
            start()
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val from = fadeFrom ?: return
        fadePaint.alpha = (fadeAlpha * 255).roundToInt().coerceIn(0, 255)
        canvas.drawBitmap(from, 0f, 0f, fadePaint)
    }

    fun apply(raw: YboardSettings) {
        // "Wallpaper colors" themes get their real colors here, so every part agrees.
        val s = raw.copy(theme = DynamicColors.resolve(context, raw.theme))
        val from = if (s.theme.id != settings.theme.id) snapshot() else null
        settings = s
        updateTopBar()
        toolbar.applySettings(s)
        keyboard.applySettings(s)
        (panelView as? ThemedPanel)?.applyTheme(s)
        (searchBar as? ThemedPanel)?.applyTheme(s)
        panelView?.let { p ->
            val lp = p.layoutParams
            if (lp != null && lp.height != keyboard.desiredHeight()) {
                lp.height = keyboard.desiredHeight(); p.layoutParams = lp
            }
        }
        updateBottomPadding()
        invalidate()
        if (from != null) startFade(from)
    }

    private fun updateBottomPadding() {
        val extra = (settings.bottomOffset * density).roundToInt()
        val bottom = (if (preview) 0 else navInset) + extra
        if (paddingBottom != bottom) setPadding(0, 0, 0, bottom)
    }

    override fun onDraw(canvas: Canvas) {
        background.draw(canvas, width, height, settings.theme, blurWorks = preview || blurActive)
        val m = settings.theme.bgMotion
        if (m != com.yboard.keyboard.settings.BgMotion.NONE) {
            motion.draw(canvas, width, height, settings.theme, motionTimeForTests ?: android.os.SystemClock.uptimeMillis())
            // Keep moving only while the keyboard is on screen.
            if (motionTimeForTests == null && isShown) postInvalidateDelayed(MotionPainter.frameDelay(m))
        }
    }

    fun showPanel(view: View, action: ToolbarView.Action?) {
        closePanel()
        panelView = view
        (view as? ThemedPanel)?.applyTheme(settings)
        content.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, keyboard.desiredHeight()))
        keyboard.cancelAllPointers()
        keyboard.visibility = View.INVISIBLE
        toolbar.active = action
    }

    fun closePanel() {
        panelView?.let { content.removeView(it) }
        panelView = null
        keyboard.visibility = View.VISIBLE
        toolbar.active = null
    }

    /** A bar shown above the keys in place of the toolbar (used by emoji search). */
    var searchBar: View? = null
        private set

    fun showSearchBar(view: View) {
        hideSearchBar()
        searchBar = view
        (view as? ThemedPanel)?.applyTheme(settings)
        addView(view, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        updateTopBar()
    }

    fun hideSearchBar() {
        searchBar?.let { removeView(it) }
        searchBar = null
        updateTopBar()
    }

    /** Shows logins / codes from autofill in place of the toolbar (empty lists = back to the toolbar). */
    fun showAutofill(chips: List<View>, pinned: List<View>) {
        autofillBar.setChips(chips, pinned)
        updateTopBar()
    }

    /** One bar above the keys: emoji search, else autofill chips, else the toolbar. */
    private fun updateTopBar() {
        val autofill = searchBar == null && autofillBar.hasChips
        autofillBar.visibility = if (autofill) View.VISIBLE else View.GONE
        toolbar.visibility = if (settings.toolbar && searchBar == null && !autofill) View.VISIBLE else View.GONE
    }
}
