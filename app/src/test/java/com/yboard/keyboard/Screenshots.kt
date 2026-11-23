package com.yboard.keyboard

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.app.ActivityOptionsCompat
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.ide.common.rendering.api.SessionParams
import com.android.resources.NightMode
import com.yboard.keyboard.keyboard.ClipboardPanel
import com.yboard.keyboard.keyboard.EmojiPanel
import com.yboard.keyboard.keyboard.EnterType
import com.yboard.keyboard.keyboard.GridPadView
import com.yboard.keyboard.keyboard.KeyboardRootView
import com.yboard.keyboard.keyboard.Layouts
import com.yboard.keyboard.keyboard.Mode
import com.yboard.keyboard.keyboard.ShiftState
import com.yboard.keyboard.keyboard.ThemesPanel
import com.yboard.keyboard.settings.ToolbarShortcut
import com.yboard.keyboard.settings.KeyStyle
import com.yboard.keyboard.settings.OneHanded
import com.yboard.keyboard.settings.ThemePresets
import com.yboard.keyboard.settings.YboardSettings
import com.yboard.keyboard.ui.CustomizeScreen
import com.yboard.keyboard.ui.HomeScreen
import com.yboard.keyboard.ui.LayoutScreen
import com.yboard.keyboard.ui.ThemesScreen
import com.yboard.keyboard.ui.theme.YboardTheme
import org.junit.Rule
import org.junit.Test

private val fakeRegistryOwner = object : ActivityResultRegistryOwner {
    override val activityResultRegistry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(
            requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?,
        ) = Unit
    }
}

@Composable
private fun Wrap(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalActivityResultRegistryOwner provides fakeRegistryOwner) {
        YboardTheme { content() }
    }
}

class AppScreensLight {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6, theme = "android:Theme.Material.Light.NoActionBar")

    @Test fun home() = paparazzi.snapshot { Wrap { HomeScreen(YboardSettings(), onOpen = {}) } }

    @Test fun onlyYboardCard() = paparazzi.snapshot {
        Wrap { com.yboard.keyboard.ui.OnlyYboardCard(listOf("Gboard")) {} }
    }

    @Test fun themes() = paparazzi.snapshot {
        Wrap { ThemesScreen(YboardSettings(theme = ThemePresets.glass), onBack = {}, onCustomize = {}) }
    }

    @Test fun customizeTop() = paparazzi.snapshot {
        Wrap { CustomizeScreen(YboardSettings(), onBack = {}) }
    }

    @Test fun appIcon() {
        val ctx = paparazzi.context
        val d = ctx.resources.displayMetrics.density
        val row = android.widget.LinearLayout(ctx).apply {
            setBackgroundColor(0xFF20243A.toInt())
            setPadding((24 * d).toInt(), (24 * d).toInt(), (24 * d).toInt(), (24 * d).toInt())
        }
        fun add(v: android.view.View) = row.addView(v, android.widget.LinearLayout.LayoutParams((180 * d).toInt(), (180 * d).toInt()).apply { marginEnd = (20 * d).toInt() })
        // The full icon, cut to a circle like your phone's launcher does.
        add(android.widget.ImageView(ctx).apply {
            setImageDrawable(android.graphics.drawable.LayerDrawable(arrayOf(
                ctx.getDrawable(com.yboard.keyboard.R.drawable.ic_launcher_background)!!,
                ctx.getDrawable(com.yboard.keyboard.R.drawable.ic_launcher_foreground)!!,
            )))
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            scaleX = 1.5f; scaleY = 1.5f  // the launcher shows the middle 72 of the 108 units
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: android.view.View, outline: android.graphics.Outline) = outline.setOval(0, 0, view.width, view.height)
            }
        })
        // The one-color version used for themed icons.
        add(android.widget.ImageView(ctx).apply {
            setBackgroundColor(0xFFD7E3FF.toInt())
            setImageResource(com.yboard.keyboard.R.drawable.ic_launcher_monochrome)
            setColorFilter(0xFF1B2A5B.toInt())
        })
        paparazzi.snapshot(row)
    }

    @Test fun setupSendStep() = paparazzi.snapshot {
        Wrap {
            com.yboard.keyboard.ui.SetupCardForTests()
        }
    }

    @Test fun shortcutsScreen() = paparazzi.snapshot {
        Wrap { com.yboard.keyboard.ui.ShortcutsScreen(YboardSettings(), onBack = {}) }
    }

    @Test fun searchResults() = paparazzi.snapshot {
        Wrap {
            androidx.compose.foundation.layout.Column {
                com.yboard.keyboard.ui.SettingsSearchField("trail") {}
                com.yboard.keyboard.ui.SettingsSearchResults("trail") {}
            }
        }
    }

    @Test fun swipeTrailSettings() = paparazzi.snapshot {
        Wrap {
            androidx.compose.foundation.layout.Column {
                com.yboard.keyboard.ui.SwipeTrailSettings(
                    YboardSettings(theme = ThemePresets.dark, trailColor = com.yboard.keyboard.settings.TrailColor.CUSTOM, trailCustomColor = 0xFFFF4FA3.toInt())
                )
            }
        }
    }

    @Test fun myWords() = paparazzi.snapshot {
        Wrap {
            com.yboard.keyboard.ui.MyWordsScreen(
                YboardSettings(
                    myWords = listOf("Shmulik", "WhatsApp", "יואבי", "Tel-Aviv"),
                    myWordPairs = listOf("Shmulik" to "שמוליק", "WhatsApp" to "וואטסאפ", "Yoavi" to "יואבי"),
                ), onBack = {},
            )
        }
    }

    @Test fun emailAndPasswords() = paparazzi.snapshot {
        Wrap {
            androidx.compose.foundation.layout.Column {
                com.yboard.keyboard.ui.EmailAndPasswordsSection(YboardSettings(myEmail = "dana.levi@gmail.com"))
            }
        }
    }

    @Test fun voiceScreen() = paparazzi.snapshot {
        Wrap { com.yboard.keyboard.ui.VoiceScreen(YboardSettings(), onBack = {}) }
    }
}

class AppScreensTall {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6.copy(screenHeight = 6200),
        theme = "android:Theme.Material.Light.NoActionBar",
    )

    @Test fun customize() = paparazzi.snapshot {
        Wrap { CustomizeScreen(YboardSettings(theme = ThemePresets.glass), onBack = {}) }
    }

    @Test fun themesFull() = paparazzi.snapshot {
        Wrap { ThemesScreen(YboardSettings(theme = ThemePresets.ocean), onBack = {}, onCustomize = {}) }
    }
}

class AppScreensDark {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6.copy(nightMode = NightMode.NIGHT),
        theme = "android:Theme.Material.NoActionBar",
    )

    @Test fun homeDark() = paparazzi.snapshot { Wrap { HomeScreen(YboardSettings(theme = ThemePresets.aurora), onOpen = {}) } }

    @Test fun layoutDark() = paparazzi.snapshot {
        Wrap { LayoutScreen(YboardSettings(numberRow = true, oneHanded = OneHanded.RIGHT), onBack = {}, onToolbar = {}) }
    }
}

class KeyboardShots {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6,
        theme = "android:Theme.Material.NoActionBar",
        renderingMode = SessionParams.RenderingMode.SHRINK,
    )

    private fun board(
        s: YboardSettings,
        mode: Mode = Mode.LETTERS,
        shift: ShiftState = ShiftState.OFF,
        enter: EnterType = EnterType.NEWLINE,
        panel: ((KeyboardRootView) -> Pair<View, ToolbarShortcut?>)? = null,
    ): View {
        val ctx = paparazzi.context
        val density = ctx.resources.displayMetrics.density
        val frame = FrameLayout(ctx)
        frame.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFF8EC5FC.toInt(), 0xFFB79CF7.toInt(), 0xFFF7A8C4.toInt()),
        )
        val root = KeyboardRootView(ctx, preview = true)
        root.apply(s)
        root.keyboard.setLayout(Layouts.build(mode, s.activeLanguage, s.numberRow, s.enabledLanguages.size > 1))
        root.keyboard.shiftState = shift
        root.keyboard.enterType = enter
        frame.addView(root, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
        ).apply { topMargin = (90 * density).toInt() })
        panel?.invoke(root)?.let { (v, a) -> root.showPanel(v, a) }
        return frame
    }

    @Test fun dark() = paparazzi.snapshot(board(YboardSettings(theme = ThemePresets.dark)))
    @Test fun darkShift() = paparazzi.snapshot(board(YboardSettings(theme = ThemePresets.dark), shift = ShiftState.ON, enter = EnterType.SEND))
    @Test fun light() = paparazzi.snapshot(board(YboardSettings(theme = ThemePresets.light), enter = EnterType.SEARCH))
    @Test fun glass() = paparazzi.snapshot(board(YboardSettings(theme = ThemePresets.glass)))
    @Test fun frost() = paparazzi.snapshot(board(YboardSettings(theme = ThemePresets.frost)))
    @Test fun hebrew() = paparazzi.snapshot(board(YboardSettings(theme = ThemePresets.dark, language = "he")))
    @Test fun gboardMatch() = paparazzi.snapshot(board(YboardSettings(theme = ThemePresets.dark), enter = EnterType.NEXT))
    @Test fun midnightBlue() = paparazzi.snapshot(board(YboardSettings(theme = ThemePresets.midnight)))

    private fun moving(s: YboardSettings): View {
        val f = board(s) as FrameLayout
        (f.getChildAt(0) as KeyboardRootView).motionTimeForTests = 12_345L
        return f
    }
    @Test fun liquidGlass() = paparazzi.snapshot(board(YboardSettings(theme = ThemePresets.liquidGlass)))
    @Test fun liquidGlassDark() = paparazzi.snapshot(board(YboardSettings(theme = ThemePresets.liquidGlassDark)))
    @Test fun snowfall() = paparazzi.snapshot(moving(YboardSettings(theme = ThemePresets.snowfall)))
    @Test fun starryNight() = paparazzi.snapshot(moving(YboardSettings(theme = ThemePresets.starry)))
    @Test fun colorFlow() = paparazzi.snapshot(moving(YboardSettings(theme = ThemePresets.flowing)))
    @Test fun bubblePop() = paparazzi.snapshot(moving(YboardSettings(theme = ThemePresets.bubblePop)))
    @Test fun pebbles() = paparazzi.snapshot(board(YboardSettings(theme = ThemePresets.pebbles)))
    @Test fun squareKeys() = paparazzi.snapshot(board(YboardSettings(theme = ThemePresets.dark.copy(keyShape = com.yboard.keyboard.settings.KeyShape.SQUARE))))
    @Test fun noGapKeys() = paparazzi.snapshot(board(YboardSettings(theme = ThemePresets.light.copy(keyShape = com.yboard.keyboard.settings.KeyShape.GRID))))
    @Test fun galaxy() = paparazzi.snapshot(board(YboardSettings(theme = ThemePresets.galaxy)))
    @Test fun candy() = paparazzi.snapshot(board(YboardSettings(theme = ThemePresets.candy, language = "he")))
    @Test fun sunsetGlow() = paparazzi.snapshot(board(YboardSettings(theme = ThemePresets.glow)))
    @Test fun deepSea() = paparazzi.snapshot(board(YboardSettings(theme = ThemePresets.deepSea)))
    @Test fun emerald() = paparazzi.snapshot(board(YboardSettings(theme = ThemePresets.emerald)))
    @Test fun cherry() = paparazzi.snapshot(board(YboardSettings(theme = ThemePresets.cherry)))
    @Test fun gold() = paparazzi.snapshot(board(YboardSettings(theme = ThemePresets.gold)))
    @Test fun voiceListening() {
        val frame = board(YboardSettings(theme = ThemePresets.dark)) as FrameLayout
        (frame.getChildAt(0) as KeyboardRootView).toolbar.setVoice("Listening…", 0.7f, true)
        paparazzi.snapshot(frame)
    }
    @Test fun hebrewLight() = paparazzi.snapshot(board(YboardSettings(theme = ThemePresets.light, language = "he"), enter = EnterType.NEXT))
    @Test fun symbols() = paparazzi.snapshot(board(YboardSettings(theme = ThemePresets.ocean), mode = Mode.SYMBOLS))
    @Test fun symbols2Hebrew() = paparazzi.snapshot(board(YboardSettings(theme = ThemePresets.sunset, language = "he"), mode = Mode.SYMBOLS2))
    @Test fun numpad() = paparazzi.snapshot(board(YboardSettings(theme = ThemePresets.mint), mode = Mode.NUMBER, enter = EnterType.DONE))
    @Test fun neon() = paparazzi.snapshot(board(YboardSettings(theme = ThemePresets.neon)))
    @Test fun paperNumberRow() = paparazzi.snapshot(board(YboardSettings(theme = ThemePresets.paper, numberRow = true)))
    @Test fun oneHanded() = paparazzi.snapshot(board(YboardSettings(theme = ThemePresets.lavender, oneHanded = OneHanded.RIGHT)))
    @Test fun borderless() = paparazzi.snapshot(board(YboardSettings(theme = ThemePresets.minimal.copy(keyStyle = KeyStyle.FLAT))))

    @Test fun themesPanel() = paparazzi.snapshot(board(YboardSettings(theme = ThemePresets.glass)) { r ->
        ThemesPanel(r.context, {}, {}, {}, {}) to ToolbarShortcut.THEMES
    })

    @Test fun clipboardPanel() = paparazzi.snapshot(board(YboardSettings(theme = ThemePresets.dark)) { r ->
        ClipboardPanel(r.context, {}, {}, {}).apply {
            setItems(listOf("See you at 8 tonight!", "https://example.com/recipe", "שלום, מה שלומך?"))
        } to ToolbarShortcut.CLIPBOARD
    })

    @Test fun emojiPanel() = paparazzi.snapshot(board(YboardSettings(theme = ThemePresets.aurora)) { r ->
        EmojiPanel(r.context, {}, {}, {}, {}, {}) to null
    })

    @Test fun editPanel() = paparazzi.snapshot(board(YboardSettings(theme = ThemePresets.light)) { r ->
        GridPadView.editPanel(r.context).apply { toggled = setOf(GridPadView.SELECT) } to ToolbarShortcut.EDIT
    })
}
