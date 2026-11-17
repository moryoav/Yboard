package com.yboard.keyboard.settings

import org.json.JSONArray
import org.json.JSONObject

enum class BgType { COLOR, GRADIENT, IMAGE }
enum class KeyStyle { FILLED, OUTLINED, FLAT }
/** DEFAULT is Google Sans (the Gboard font). SYSTEM is whatever font the phone uses. */
enum class FontStyle { DEFAULT, LIGHT, CONDENSED, SERIF, MONO, CASUAL, SYSTEM }
enum class OneHanded { OFF, LEFT, RIGHT }
enum class PressEffect { NONE, POP, GLOW, RIPPLE }
/** Moving background behind the keys. */
enum class BgMotion { NONE, FLOW, SNOW, STARS, BUBBLES }
/** Swipe trail color: the theme's accent, a moving rainbow, or a color you pick. */
enum class TrailColor { THEME, RAINBOW, CUSTOM }
/** ROUNDED uses the corner slider; ROUND = circle keys; BUBBLE = shiny; GRID = keys touching. */
enum class KeyShape { ROUNDED, ROUND, BUBBLE, SQUARE, GRID }

/** Everything about how the keyboard looks. */
data class KeyboardTheme(
    val id: String,
    val name: String,
    val bgType: BgType = BgType.COLOR,
    val bgColor: Int,
    val bgColor2: Int = bgColor,
    /** Optional third gradient color, in the middle (null = two-color gradient). */
    val bgColorMid: Int? = null,
    val gradientAngle: Int = 135,
    val bgImagePath: String? = null,
    val imageDim: Float = 0.25f,
    val imageBlur: Boolean = false,
    /** 0 = fully see-through, 1 = solid. */
    val bgOpacity: Float = 1f,
    val keyColor: Int,
    val specialKeyColor: Int,
    val accentColor: Int,
    val accentTextColor: Int,
    val textColor: Int,
    val keyOpacity: Float = 1f,
    val keyStyle: KeyStyle = KeyStyle.FILLED,
    val cornerRadius: Float = 8f,
    val keyGapH: Float = 5f,
    val keyGapV: Float = 9f,
    val borderWidth: Float = 0f,
    val borderColor: Int = textColor,
    val keyShadow: Boolean = false,
    val fontScale: Float = 1f,
    val boldLabels: Boolean = false,
    val fontStyle: FontStyle = FontStyle.DEFAULT,
    /** Android 12+: take colors from the phone's wallpaper (Material You). */
    val dynamicColors: Boolean = false,
    val pressEffect: PressEffect = PressEffect.NONE,
    /** Animated rainbow light around every key. */
    val rgbLights: Boolean = false,
    /** Enter key in the accent color (off = same gray as shift/backspace, like Gboard dark). */
    val accentEnter: Boolean = true,
    val bgMotion: BgMotion = BgMotion.NONE,
    val keyShape: KeyShape = KeyShape.ROUNDED,
    /** Blur the app behind the keyboard (dp, 0 = off). Android 12+. */
    val blur: Float = 0f,
    /** Liquid-glass keys: see-through, with a shiny edge that moves when you tilt the phone. */
    val glassKeys: Boolean = false,
) {
    val isCustom: Boolean get() = id.startsWith("custom")

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name)
        put("bgType", bgType.name); put("bgColor", bgColor); put("bgColor2", bgColor2)
        bgColorMid?.let { put("bgColorMid", it) }
        put("gradientAngle", gradientAngle)
        put("bgImagePath", bgImagePath ?: "")
        put("imageDim", imageDim.toDouble()); put("imageBlur", imageBlur)
        put("bgOpacity", bgOpacity.toDouble())
        put("keyColor", keyColor); put("specialKeyColor", specialKeyColor)
        put("accentColor", accentColor); put("accentTextColor", accentTextColor)
        put("textColor", textColor); put("keyOpacity", keyOpacity.toDouble())
        put("keyStyle", keyStyle.name); put("cornerRadius", cornerRadius.toDouble())
        put("keyGapH", keyGapH.toDouble()); put("keyGapV", keyGapV.toDouble())
        put("borderWidth", borderWidth.toDouble()); put("borderColor", borderColor)
        put("keyShadow", keyShadow); put("fontScale", fontScale.toDouble())
        put("boldLabels", boldLabels); put("fontStyle", fontStyle.name)
        put("dynamicColors", dynamicColors); put("pressEffect", pressEffect.name)
        put("rgbLights", rgbLights); put("accentEnter", accentEnter)
        put("bgMotion", bgMotion.name); put("keyShape", keyShape.name)
        put("blur", blur.toDouble()); put("glassKeys", glassKeys)
    }

    companion object {
        fun fromJson(o: JSONObject): KeyboardTheme {
            val d = ThemePresets.default
            fun f(k: String, def: Float) = o.optDouble(k, def.toDouble()).toFloat()
            return KeyboardTheme(
                id = o.optString("id", d.id),
                name = o.optString("name", d.name),
                bgType = enumOr(o.optString("bgType"), d.bgType),
                bgColor = o.optInt("bgColor", d.bgColor),
                bgColor2 = o.optInt("bgColor2", d.bgColor2),
                bgColorMid = if (o.has("bgColorMid") && !o.isNull("bgColorMid")) o.getInt("bgColorMid") else null,
                gradientAngle = o.optInt("gradientAngle", d.gradientAngle),
                bgImagePath = o.optString("bgImagePath", "").ifEmpty { null },
                imageDim = f("imageDim", d.imageDim),
                imageBlur = o.optBoolean("imageBlur", d.imageBlur),
                bgOpacity = f("bgOpacity", d.bgOpacity),
                keyColor = o.optInt("keyColor", d.keyColor),
                specialKeyColor = o.optInt("specialKeyColor", d.specialKeyColor),
                accentColor = o.optInt("accentColor", d.accentColor),
                accentTextColor = o.optInt("accentTextColor", d.accentTextColor),
                textColor = o.optInt("textColor", d.textColor),
                keyOpacity = f("keyOpacity", d.keyOpacity),
                keyStyle = enumOr(o.optString("keyStyle"), d.keyStyle),
                cornerRadius = f("cornerRadius", d.cornerRadius),
                keyGapH = f("keyGapH", d.keyGapH),
                keyGapV = f("keyGapV", d.keyGapV),
                borderWidth = f("borderWidth", d.borderWidth),
                borderColor = o.optInt("borderColor", d.borderColor),
                keyShadow = o.optBoolean("keyShadow", d.keyShadow),
                fontScale = f("fontScale", d.fontScale),
                boldLabels = o.optBoolean("boldLabels", d.boldLabels),
                fontStyle = enumOr(o.optString("fontStyle"), d.fontStyle),
                dynamicColors = o.optBoolean("dynamicColors", d.dynamicColors),
                pressEffect = enumOr(o.optString("pressEffect"), d.pressEffect),
                rgbLights = o.optBoolean("rgbLights", d.rgbLights),
                accentEnter = o.optBoolean("accentEnter", true),
                bgMotion = enumOr(o.optString("bgMotion"), BgMotion.NONE),
                keyShape = enumOr(o.optString("keyShape"), KeyShape.ROUNDED),
                blur = f("blur", 0f),
                glassKeys = o.optBoolean("glassKeys", false),
            )
        }
    }
}

/** All user settings. Stored as one JSON blob. */
data class YboardSettings(
    val theme: KeyboardTheme = ThemePresets.default,
    val heightScale: Float = 1f,
    val numberRow: Boolean = false,
    val showHints: Boolean = true,
    val keyPopup: Boolean = true,
    val toolbar: Boolean = true,
    val oneHanded: OneHanded = OneHanded.OFF,
    val bottomOffset: Float = 0f,
    val haptics: Boolean = true,
    val hapticStrength: Float = 0.45f,
    val sound: Boolean = false,
    val soundVolume: Float = 0.5f,
    val quietHours: QuietHours = QuietHours(),
    val autoCaps: Boolean = true,
    val doubleSpacePeriod: Boolean = true,
    val spaceCursor: Boolean = true,
    val longPressDelay: Int = 350,
    val english: Boolean = true,
    val hebrew: Boolean = true,
    val language: String = "en",
    val savedThemes: List<KeyboardTheme> = emptyList(),
    val suggestions: Boolean = true,
    val autoCorrect: Boolean = true,
    val learnWords: Boolean = true,
    val swipeTyping: Boolean = true,
    val swipeTrail: Boolean = true,
    /** Swipe trail thickness at your finger, in dp. */
    val trailWidth: Float = 10f,
    val trailColor: TrailColor = TrailColor.THEME,
    /** Used when [trailColor] is CUSTOM. */
    val trailCustomColor: Int = 0xFFFFFFFF.toInt(),
    /** A soft glow around the trail. */
    val trailGlow: Boolean = true,
    val backspaceSwipe: Boolean = true,
    /** "system" = the phone's click, otherwise one of KeySounds.STYLES. */
    val soundStyle: String = "system",
    /** AI voice typing: "auto" = the keyboard's languages, or "en" / "he" only. */
    val voiceLanguages: String = "auto",
    /** AI voice typing understands "delete that", "new line", "question mark"... */
    val voiceCommands: Boolean = true,
    val voiceQuality: com.yboard.keyboard.voice.TranscriptionQuality = com.yboard.keyboard.voice.TranscriptionQuality.BALANCED,
    val voicePauseMs: Int = 500,
    val voiceSilenceSeconds: Int = 15,
    /** Android 16+: hide the bar Android adds under the keys (switch-keyboard and hide buttons). */
    val hideSystemBar: Boolean = true,
    /** Suggest the next word after a space. */
    val nextWords: Boolean = true,
    /** Suggest an emoji for words like "pizza". */
    val emojiSuggest: Boolean = true,
    /** Show the optional "Send by voice" step in setup (off after "Not now"). */
    val sendHelperPrompt: Boolean = true,
    /** Text shortcuts ("omw" -> "On my way!"). */
    val shortcutsOn: Boolean = true,
    val shortcuts: List<Pair<String, String>> = com.yboard.keyboard.text.Shortcuts.defaults,
    /** Words you added yourself ("My words"), newest first. Stays on this phone. */
    val myWords: List<String> = emptyList(),
    /** (English, Hebrew) spellings of your words, so voice typing writes a name in the language you speak. */
    val myWordPairs: List<Pair<String, String>> = emptyList(),
    /** The ✨ button above the keys that adds fitting emojis (AI, uses your OpenAI key). */
    val emojiSprinkle: Boolean = true,
    /** AI next-word predictions (OpenAI, opt-in). */
    val aiPredictions: Boolean = false,
    val aiModels: AiModels = AiModels(),
    /** @ and .com keys and address suggestions in email boxes. */
    val emailHelpers: Boolean = true,
    /** Your own email, offered in email boxes (stays on this phone). */
    val myEmail: String = "",
    /** Logins from your password manager and codes from texts, in the strip (Android 11+). */
    val autofillChips: Boolean = true,
) {
    /** Languages to tell the speech model to expect. */
    val voiceLanguageList: List<String>
        get() = when (voiceLanguages) {
            "en" -> listOf("en")
            "he" -> listOf("he")
            else -> listOf("he", "en")
        }

    val voiceOptions: com.yboard.keyboard.voice.VoiceOptions get() =
        com.yboard.keyboard.voice.VoiceOptions(voiceQuality, voicePauseMs, voiceSilenceSeconds)

    /**
     * "Reset customizations": back to the default look (Yboard Dark, like Gboard).
     * Saved themes, languages, typing, sound and learned words are kept.
     */
    fun withDefaultLook(includeLayout: Boolean): YboardSettings {
        val d = YboardSettings()
        val look = copy(theme = d.theme)
        if (!includeLayout) return look
        return look.copy(
            heightScale = d.heightScale, bottomOffset = d.bottomOffset, numberRow = d.numberRow,
            showHints = d.showHints, toolbar = d.toolbar, oneHanded = d.oneHanded,
        )
    }

    val enabledLanguages: List<String>
        get() = buildList {
            if (english) add("en")
            if (hebrew) add("he")
            if (isEmpty()) add("en")
        }

    val activeLanguage: String
        get() = if (language in enabledLanguages) language else enabledLanguages.first()

    fun toJson(): String = JSONObject().apply {
        put("theme", theme.toJson())
        put("heightScale", heightScale.toDouble())
        put("numberRow", numberRow); put("showHints", showHints)
        put("keyPopup", keyPopup); put("toolbar", toolbar)
        put("oneHanded", oneHanded.name); put("bottomOffset", bottomOffset.toDouble())
        put("haptics", haptics); put("hapticStrength", hapticStrength.toDouble())
        put("sound", sound); put("soundVolume", soundVolume.toDouble())
        put("quietHours", quietHours.toJson())
        put("autoCaps", autoCaps); put("doubleSpacePeriod", doubleSpacePeriod)
        put("spaceCursor", spaceCursor); put("longPressDelay", longPressDelay)
        put("english", english); put("hebrew", hebrew); put("language", language)
        put("savedThemes", JSONArray().apply { savedThemes.forEach { put(it.toJson()) } })
        put("suggestions", suggestions); put("autoCorrect", autoCorrect); put("learnWords", learnWords)
        put("swipeTyping", swipeTyping); put("swipeTrail", swipeTrail); put("backspaceSwipe", backspaceSwipe)
        put("trailWidth", trailWidth.toDouble()); put("trailColor", trailColor.name)
        put("trailCustomColor", trailCustomColor); put("trailGlow", trailGlow)
        put("soundStyle", soundStyle)
        put("voiceLanguages", voiceLanguages); put("voiceCommands", voiceCommands)
        put("voiceQuality", voiceQuality.name); put("voicePauseMs", voicePauseMs); put("voiceSilenceSeconds", voiceSilenceSeconds)
        put("hideSystemBar", hideSystemBar)
        put("nextWords", nextWords); put("emojiSuggest", emojiSuggest)
        put("sendHelperPrompt", sendHelperPrompt)
        put("shortcutsOn", shortcutsOn)
        put("shortcuts", JSONArray().apply { shortcuts.forEach { (s, e) -> put(JSONArray().put(s).put(e)) } })
        put("myWords", JSONArray(myWords))
        put("myWordPairs", JSONArray().apply { myWordPairs.forEach { (e, h) -> put(JSONArray().put(e).put(h)) } })
        put("emojiSprinkle", emojiSprinkle)
        put("aiPredictions", aiPredictions)
        put("aiModels", aiModels.toJson())
        put("emailHelpers", emailHelpers); put("myEmail", myEmail); put("autofillChips", autofillChips)
        put("styleRev", STYLE_REV)
    }.toString()

    companion object {
        /** Bumped when the built-in themes change, so unedited presets pick up the new look. */
        const val STYLE_REV = 2

        fun fromJson(s: String?): YboardSettings {
            if (s.isNullOrBlank()) return YboardSettings()
            return try {
                val o = JSONObject(s)
                val d = YboardSettings()
                fun f(k: String, def: Float) = o.optDouble(k, def.toDouble()).toFloat()
                val saved = o.optJSONArray("savedThemes")
                YboardSettings(
                    theme = o.optJSONObject("theme")?.let { KeyboardTheme.fromJson(it) }
                        ?.let { if (o.optInt("styleRev", 1) < STYLE_REV) ThemePresets.refresh(it) else it }
                        ?: d.theme,
                    heightScale = f("heightScale", d.heightScale),
                    numberRow = o.optBoolean("numberRow", d.numberRow),
                    showHints = o.optBoolean("showHints", d.showHints),
                    keyPopup = o.optBoolean("keyPopup", d.keyPopup),
                    toolbar = o.optBoolean("toolbar", d.toolbar),
                    oneHanded = enumOr(o.optString("oneHanded"), d.oneHanded),
                    bottomOffset = f("bottomOffset", d.bottomOffset),
                    haptics = o.optBoolean("haptics", d.haptics),
                    hapticStrength = f("hapticStrength", d.hapticStrength),
                    sound = o.optBoolean("sound", d.sound),
                    soundVolume = f("soundVolume", d.soundVolume),
                    quietHours = QuietHours.fromJson(o.optJSONObject("quietHours")),
                    autoCaps = o.optBoolean("autoCaps", d.autoCaps),
                    doubleSpacePeriod = o.optBoolean("doubleSpacePeriod", d.doubleSpacePeriod),
                    spaceCursor = o.optBoolean("spaceCursor", d.spaceCursor),
                    longPressDelay = o.optInt("longPressDelay", d.longPressDelay),
                    english = o.optBoolean("english", d.english),
                    hebrew = o.optBoolean("hebrew", d.hebrew),
                    language = o.optString("language", d.language),
                    savedThemes = if (saved == null) emptyList() else
                        (0 until saved.length()).mapNotNull { i ->
                            saved.optJSONObject(i)?.let { KeyboardTheme.fromJson(it) }
                        },
                    suggestions = o.optBoolean("suggestions", d.suggestions),
                    autoCorrect = o.optBoolean("autoCorrect", d.autoCorrect),
                    learnWords = o.optBoolean("learnWords", d.learnWords),
                    swipeTyping = o.optBoolean("swipeTyping", d.swipeTyping),
                    swipeTrail = o.optBoolean("swipeTrail", d.swipeTrail),
                    trailWidth = o.optDouble("trailWidth", d.trailWidth.toDouble()).toFloat(),
                    trailColor = enumOr(o.optString("trailColor"), d.trailColor),
                    trailCustomColor = o.optInt("trailCustomColor", d.trailCustomColor),
                    trailGlow = o.optBoolean("trailGlow", d.trailGlow),
                    backspaceSwipe = o.optBoolean("backspaceSwipe", d.backspaceSwipe),
                    soundStyle = o.optString("soundStyle", d.soundStyle),
                    voiceLanguages = o.optString("voiceLanguages", d.voiceLanguages),
                    voiceCommands = o.optBoolean("voiceCommands", d.voiceCommands),
                    voiceQuality = enumOr(o.optString("voiceQuality"), d.voiceQuality),
                    voicePauseMs = o.optInt("voicePauseMs", d.voicePauseMs).coerceIn(250, 1500),
                    voiceSilenceSeconds = o.optInt("voiceSilenceSeconds", d.voiceSilenceSeconds).let { if (it == 0) 0 else it.coerceIn(8, 60) },
                    hideSystemBar = o.optBoolean("hideSystemBar", d.hideSystemBar),
                    nextWords = o.optBoolean("nextWords", d.nextWords),
                    emojiSuggest = o.optBoolean("emojiSuggest", d.emojiSuggest),
                    sendHelperPrompt = o.optBoolean("sendHelperPrompt", d.sendHelperPrompt),
                    shortcutsOn = o.optBoolean("shortcutsOn", d.shortcutsOn),
                    shortcuts = o.optJSONArray("shortcuts")?.let { arr ->
                        (0 until arr.length()).mapNotNull { i ->
                            arr.optJSONArray(i)?.let { p -> p.optString(0) to p.optString(1) }
                                ?.takeIf { it.first.isNotBlank() && it.second.isNotBlank() }
                        }
                    } ?: d.shortcuts,
                    myWords = o.optJSONArray("myWords")?.let { arr ->
                        (0 until arr.length()).map { arr.optString(it).trim() }.filter { it.isNotEmpty() }
                    } ?: d.myWords,
                    myWordPairs = o.optJSONArray("myWordPairs")?.let { arr ->
                        (0 until arr.length()).mapNotNull { i ->
                            arr.optJSONArray(i)?.let { p -> p.optString(0).trim() to p.optString(1).trim() }
                                ?.takeIf { it.first.isNotEmpty() && it.second.isNotEmpty() }
                        }
                    } ?: d.myWordPairs,
                    emojiSprinkle = o.optBoolean("emojiSprinkle", d.emojiSprinkle),
                    aiPredictions = o.optBoolean("aiPredictions", d.aiPredictions),
                    aiModels = AiModels.fromJson(o.optJSONObject("aiModels")),
                    emailHelpers = o.optBoolean("emailHelpers", d.emailHelpers),
                    myEmail = o.optString("myEmail", d.myEmail),
                    autofillChips = o.optBoolean("autofillChips", d.autofillChips),
                )
            } catch (e: Exception) {
                YboardSettings()
            }
        }
    }
}

inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
    enumValues<T>().firstOrNull { it.name == name } ?: default
