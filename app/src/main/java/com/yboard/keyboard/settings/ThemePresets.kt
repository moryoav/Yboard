package com.yboard.keyboard.settings

object ThemePresets {

    private fun c(hex: Long): Int = hex.toInt()

    /** Gboard's dark theme: neutral grays, gray Enter key (colors measured from a Gboard screenshot). */
    val dark = KeyboardTheme(
        id = "dark", name = "Yboard Dark",
        bgColor = c(0xFF1E1E1E),
        keyColor = c(0xFF393939), specialKeyColor = c(0xFF444444),
        accentColor = c(0xFFA8C7FA), accentTextColor = c(0xFF0B1D3A),
        textColor = c(0xFFE3E3E3), cornerRadius = 6f, accentEnter = false,
    )

    /** Gboard's light theme: white keys, gray side keys, blue Enter key. */
    val light = KeyboardTheme(
        id = "light", name = "Yboard Light",
        bgColor = c(0xFFE8EAEE),
        keyColor = c(0xFFFFFFFF), specialKeyColor = c(0xFFCDCED4),
        accentColor = c(0xFF1973E8), accentTextColor = c(0xFFFFFFFF),
        textColor = c(0xFF000000), cornerRadius = 6f, keyShadow = true,
    )

    val amoled = KeyboardTheme(
        id = "amoled", name = "Pure Black",
        bgColor = c(0xFF000000),
        keyColor = c(0xFF151517), specialKeyColor = c(0xFF232326),
        accentColor = c(0xFFFFFFFF), accentTextColor = c(0xFF000000),
        textColor = c(0xFFEDEDED),
    )

    val glass = KeyboardTheme(
        id = "glass", name = "Glass",
        bgColor = c(0xFF0E0E14), bgOpacity = 0.55f,
        keyColor = c(0xFFFFFFFF), specialKeyColor = c(0xFFFFFFFF), keyOpacity = 0.13f,
        accentColor = c(0xFFFFFFFF), accentTextColor = c(0xFF111111),
        textColor = c(0xFFFFFFFF), cornerRadius = 12f,
    )

    val frost = KeyboardTheme(
        id = "frost", name = "Frost",
        bgColor = c(0xFFF4F5FA), bgOpacity = 0.6f,
        keyColor = c(0xFFFFFFFF), specialKeyColor = c(0xFFFFFFFF), keyOpacity = 0.55f,
        accentColor = c(0xFF1F1F1F), accentTextColor = c(0xFFFFFFFF),
        textColor = c(0xFF151515), cornerRadius = 12f,
    )

    val ocean = KeyboardTheme(
        id = "ocean", name = "Ocean",
        bgType = BgType.GRADIENT, bgColor = c(0xFF0F2027), bgColor2 = c(0xFF2C5364), gradientAngle = 90,
        keyColor = c(0xFFFFFFFF), specialKeyColor = c(0xFFFFFFFF), keyOpacity = 0.12f,
        accentColor = c(0xFF4FD1C5), accentTextColor = c(0xFF06302C),
        textColor = c(0xFFFFFFFF), cornerRadius = 10f,
    )

    val sunset = KeyboardTheme(
        id = "sunset", name = "Sunset",
        bgType = BgType.GRADIENT, bgColor = c(0xFFFF5F6D), bgColor2 = c(0xFFFFC371), gradientAngle = 45,
        keyColor = c(0xFFFFFFFF), specialKeyColor = c(0xFFFFFFFF), keyOpacity = 0.24f,
        accentColor = c(0xFFFFFFFF), accentTextColor = c(0xFFE0465A),
        textColor = c(0xFFFFFFFF), cornerRadius = 12f, boldLabels = true,
    )

    val aurora = KeyboardTheme(
        id = "aurora", name = "Aurora",
        bgType = BgType.GRADIENT, bgColor = c(0xFF3A1C71), bgColor2 = c(0xFFD76D77), gradientAngle = 120,
        keyColor = c(0xFFFFFFFF), specialKeyColor = c(0xFFFFFFFF), keyOpacity = 0.14f,
        accentColor = c(0xFFFFAF7B), accentTextColor = c(0xFF3A1C71),
        textColor = c(0xFFFFFFFF), cornerRadius = 14f,
    )

    val neon = KeyboardTheme(
        id = "neon", name = "Neon",
        bgColor = c(0xFF07070F),
        keyColor = c(0xFF0D0D1A), specialKeyColor = c(0xFF0D0D1A),
        accentColor = c(0xFFFF2BD6), accentTextColor = c(0xFF07070F),
        textColor = c(0xFF00E5FF), keyStyle = KeyStyle.OUTLINED,
        borderWidth = 1.5f, borderColor = c(0xFF00E5FF), cornerRadius = 10f,
    )

    val mint = KeyboardTheme(
        id = "mint", name = "Mint",
        bgColor = c(0xFFE3F4EA),
        keyColor = c(0xFFFFFFFF), specialKeyColor = c(0xFFB9E2C8),
        accentColor = c(0xFF1E8E3E), accentTextColor = c(0xFFFFFFFF),
        textColor = c(0xFF12301D), cornerRadius = 12f, keyShadow = true,
    )

    val rose = KeyboardTheme(
        id = "rose", name = "Rose",
        bgColor = c(0xFFFCE4EC),
        keyColor = c(0xFFFFFFFF), specialKeyColor = c(0xFFF8BBD0),
        accentColor = c(0xFFD81B60), accentTextColor = c(0xFFFFFFFF),
        textColor = c(0xFF3B0A1E), cornerRadius = 14f, keyShadow = true,
    )

    val lavender = KeyboardTheme(
        id = "lavender", name = "Lavender",
        bgColor = c(0xFFEDE7F6),
        keyColor = c(0xFFFFFFFF), specialKeyColor = c(0xFFD6C9EF),
        accentColor = c(0xFF6A3DE8), accentTextColor = c(0xFFFFFFFF),
        textColor = c(0xFF221641), cornerRadius = 10f, keyShadow = true,
    )

    val forest = KeyboardTheme(
        id = "forest", name = "Forest",
        bgColor = c(0xFF16241C),
        keyColor = c(0xFF24382B), specialKeyColor = c(0xFF2F4838),
        accentColor = c(0xFF7BD389), accentTextColor = c(0xFF0D2414),
        textColor = c(0xFFE6F2E8),
    )

    val paper = KeyboardTheme(
        id = "paper", name = "Paper",
        bgColor = c(0xFFF2E8D5),
        keyColor = c(0xFFFFFAEF), specialKeyColor = c(0xFFE3D4B4),
        accentColor = c(0xFFB5432F), accentTextColor = c(0xFFFFFFFF),
        textColor = c(0xFF3A2E1E), fontStyle = FontStyle.SERIF, cornerRadius = 6f,
        borderWidth = 0.8f, borderColor = c(0xFFD2C29F),
    )

    val minimal = KeyboardTheme(
        id = "minimal", name = "Borderless",
        bgColor = c(0xFF202124),
        keyColor = c(0xFF202124), specialKeyColor = c(0xFF202124),
        accentColor = c(0xFF8AB4F8), accentTextColor = c(0xFF202124),
        textColor = c(0xFFE8EAED), keyStyle = KeyStyle.FLAT,
    )

    // ---- Gradient themes: see-through keys over a three-color gradient that fades to near black.

    private fun gradient(id: String, name: String, top: Long, mid: Long, bottom: Long, enter: Long) = KeyboardTheme(
        id = id, name = name,
        bgType = BgType.GRADIENT, bgColor = c(top), bgColorMid = c(mid), bgColor2 = c(bottom), gradientAngle = 90,
        keyColor = c(0xFFFFFFFF), specialKeyColor = c(0xFFFFFFFF), keyOpacity = 0.18f,
        accentColor = c(enter), accentTextColor = c(0xFFFFFFFF),
        textColor = c(0xFFFFFFFF), cornerRadius = 6f,
    )

    /** Blue fading to black, like Gboard's blue gradient theme. */
    val midnight = gradient("midnight", "Midnight Blue", 0xFF36628A, 0xFF33516E, 0xFF0B131A, 0xFF3A6894)
    val galaxy = gradient("galaxy", "Galaxy", 0xFF7B2FD6, 0xFF3D16A0, 0xFF0A0420, 0xFF7B3FE4)
    val candy = gradient("candy", "Candy", 0xFFD83F7A, 0xFF5466D2, 0xFF12152B, 0xFFE0558A)
    val glow = gradient("glow", "Sunset Glow", 0xFFD9573F, 0xFF962F57, 0xFF1C0918, 0xFFE0684C)
    val deepSea = gradient("deepsea", "Deep Sea", 0xFF1689B8, 0xFF0E587E, 0xFF051320, 0xFF1A96C8)
    val emerald = gradient("emerald", "Emerald Night", 0xFF1E8A6A, 0xFF135541, 0xFF05110D, 0xFF22A07A)
    val cherry = gradient("cherry", "Cherry", 0xFFB53A3F, 0xFF6C1C2D, 0xFF15050A, 0xFFC8464C)
    val gold = gradient("gold", "Golden Hour", 0xFFB5711F, 0xFF6D3E10, 0xFF130A03, 0xFFC98426)

    // ---- Moving backgrounds and special key shapes.
    val snowfall = midnight.copy(id = "snowfall", name = "Snowfall", bgMotion = BgMotion.SNOW)
    val starry = galaxy.copy(id = "starry", name = "Starry Night", bgMotion = BgMotion.STARS)
    val flowing = candy.copy(id = "flowing", name = "Color Flow", bgMotion = BgMotion.FLOW)
    val bubblePop = deepSea.copy(id = "bubblepop", name = "Bubble Pop", bgMotion = BgMotion.BUBBLES, keyShape = KeyShape.BUBBLE)
    val pebbles = emerald.copy(id = "pebbles", name = "Pebbles", keyShape = KeyShape.ROUND)

    // ---- Liquid Glass: the app behind shows through, blurred; keys are shiny glass.
    val liquidGlass = KeyboardTheme(
        id = "liquidglass", name = "Liquid Glass",
        bgColor = c(0xFFF4F6FA), bgOpacity = 0.28f, blur = 26f,
        keyColor = c(0xFFFFFFFF), specialKeyColor = c(0xFFE4E8F0), keyOpacity = 0.55f,
        accentColor = c(0xFF0A84FF), accentTextColor = c(0xFFFFFFFF),
        textColor = c(0xFF111216), cornerRadius = 10f, glassKeys = true, keyShadow = true,
    )
    val liquidGlassDark = KeyboardTheme(
        id = "liquidglassdark", name = "Liquid Glass Dark",
        bgColor = c(0xFF0E1014), bgOpacity = 0.30f, blur = 26f,
        keyColor = c(0xFFFFFFFF), specialKeyColor = c(0xFFFFFFFF), keyOpacity = 0.14f,
        accentColor = c(0xFF0A84FF), accentTextColor = c(0xFFFFFFFF),
        textColor = c(0xFFFFFFFF), cornerRadius = 10f, glassKeys = true,
    )

    /** Colors come from the phone's wallpaper (Android 12+); these are only fallbacks. */
    val wallpaper = dark.copy(
        id = "wallpaper", name = "Wallpaper colors", dynamicColors = true, cornerRadius = 10f, accentEnter = true,
    )

    val all: List<KeyboardTheme> = listOf(
        dark, light, liquidGlass, liquidGlassDark, midnight, galaxy, candy, glow, deepSea, emerald, cherry, gold,
        snowfall, starry, flowing, bubblePop, pebbles,
        glass, frost, amoled, ocean, sunset, aurora, neon, mint, rose, lavender, forest, paper, minimal
    )

    /** Presets to show in pickers: wallpaper colors first on phones that support it. */
    val visible: List<KeyboardTheme>
        get() = if (android.os.Build.VERSION.SDK_INT >= 31) listOf(wallpaper) + all else all

    val default: KeyboardTheme get() = dark

    /**
     * After an update: a theme the user picked but never edited (its id is still a preset id)
     * gets the preset's new colors and shape. Effects, font and transparency stay as they were.
     */
    fun refresh(t: KeyboardTheme): KeyboardTheme {
        val p = (all + wallpaper).firstOrNull { it.id == t.id } ?: return t
        return t.copy(
            bgColor = p.bgColor, bgColor2 = p.bgColor2, bgColorMid = p.bgColorMid,
            keyColor = p.keyColor, specialKeyColor = p.specialKeyColor,
            accentColor = p.accentColor, accentTextColor = p.accentTextColor, textColor = p.textColor,
            cornerRadius = p.cornerRadius, keyShadow = p.keyShadow, accentEnter = p.accentEnter,
            bgMotion = p.bgMotion, keyShape = p.keyShape, blur = p.blur, glassKeys = p.glassKeys,
        )
    }
}
