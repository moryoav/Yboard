package com.yboard.keyboard.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.yboard.keyboard.settings.YboardSettings
import com.yboard.keyboard.stickers.StickerImage
import kotlin.math.roundToInt

/** A review step inside the keyboard. Generating never sends a message. */
class StickerPanel(context: Context, source: String, model: String,
    onClose: () -> Unit, onRecreate: () -> Unit, onSend: () -> Unit,
) : LinearLayout(context), ThemedPanel {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private val title = label("Create sticker", 17f)
    private val close = button("Back", onClose)
    private val caption = label(source, 13f).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
    private val modelLabel = label(model, 11f).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val image = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = "Generated sticker preview"
        setPadding(dp(8), dp(8), dp(8), dp(8))
    }
    private val progress = ProgressBar(context)
    private val status = label("Creating sticker…", 14f).apply { gravity = Gravity.CENTER; maxLines = 3 }
    private val recreate = button("Recreate", onRecreate)
    private val send = button("Send", onSend)
    private val stage = FrameLayout(context)
    private var busy = true
    private var hasImage = false

    init {
        orientation = VERTICAL
        setPadding(dp(8), dp(2), dp(8), dp(4))
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(title, LayoutParams(0, dp(44), 1f))
        header.addView(close, LayoutParams(dp(64), dp(44)))
        addView(header)
        addView(caption, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(modelLabel, LayoutParams(LayoutParams.MATCH_PARENT, dp(20)))
        stage.addView(image, FrameLayout.LayoutParams(-1, -1))
        stage.addView(progress, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
        stage.addView(status, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(8), 0, dp(8), dp(4)) })
        addView(stage, LayoutParams(-1, 0, 1f))
        val actions = LinearLayout(context)
        actions.addView(recreate, LayoutParams(0, dp(48), 1f).apply { setMargins(0, dp(4), dp(4), 0) })
        actions.addView(send, LayoutParams(0, dp(48), 1f).apply { setMargins(dp(4), dp(4), 0, 0) })
        addView(actions)
        updateButtons()
    }
    private fun label(value: String, size: Float) = TextView(context).apply {
        text = value; setTextSize(TypedValue.COMPLEX_UNIT_SP, size); typeface = Fonts.regular(context)
        gravity = Gravity.CENTER_VERTICAL
    }
    private fun button(value: String, action: () -> Unit) = label(value, 15f).apply {
        gravity = Gravity.CENTER; isClickable = true; isFocusable = true; setOnClickListener { action() }
    }
    fun loading(model: String) {
        busy = true; modelLabel.text = model
        image.alpha = if (hasImage) .3f else 1f
        progress.visibility = View.VISIBLE; status.visibility = View.VISIBLE; status.text = "Creating sticker…"
        updateButtons()
    }
    fun show(result: StickerImage) {
        hasImage = true; busy = false; modelLabel.text = result.model
        image.setImageBitmap(result.bitmap); image.alpha = 1f
        progress.visibility = View.GONE; status.visibility = View.GONE
        updateButtons()
    }
    fun error(message: String) {
        busy = false; image.alpha = 1f; progress.visibility = View.GONE
        status.visibility = View.VISIBLE; status.text = message
        updateButtons()
    }
    fun clearPreview() { image.setImageDrawable(null) }
    private fun updateButtons() {
        recreate.isEnabled = !busy; send.isEnabled = !busy && hasImage
        recreate.alpha = if (recreate.isEnabled) 1f else .45f; send.alpha = if (send.isEnabled) 1f else .45f
    }
    override fun applyTheme(s: YboardSettings) {
        val t = s.theme
        listOf(title, caption, modelLabel, status).forEach { it.setTextColor(t.textColor) }
        modelLabel.alpha = .75f
        status.setBackgroundColor(ThemeRender.withAlpha(t.bgColor, .96f))
        image.background = TransparencyGrid(t.keyColor, t.specialKeyColor, dp(12))
        for (view in listOf(close, recreate)) {
            view.setTextColor(t.textColor)
            view.background = GradientDrawable().apply { setColor(ThemeRender.withAlpha(t.keyColor, .9f)); cornerRadius = dp(12).toFloat() }
        }
        send.setTextColor(t.accentTextColor)
        send.background = GradientDrawable().apply { setColor(t.accentColor); cornerRadius = dp(12).toFloat() }
        progress.indeterminateTintList = android.content.res.ColorStateList.valueOf(t.accentColor)
    }
}

private class TransparencyGrid(private val first: Int, private val second: Int, private val size: Int) : Drawable() {
    private val paint = Paint()
    override fun draw(canvas: Canvas) {
        for (y in bounds.top until bounds.bottom step size) for (x in bounds.left until bounds.right step size) {
            paint.color = if ((x / size + y / size) % 2 == 0) first else second
            canvas.drawRect(x.toFloat(), y.toFloat(), (x + size).toFloat(), (y + size).toFloat(), paint)
        }
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.OPAQUE
}
