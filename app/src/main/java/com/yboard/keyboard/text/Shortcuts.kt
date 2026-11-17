package com.yboard.keyboard.text

/** Text shortcuts: type "omw" and a space, get "On my way!". */
object Shortcuts {
    val defaults: List<Pair<String, String>> = listOf(
        "omw" to "On my way!",
        "brb" to "Be right back",
        "idk" to "I don't know",
        "btw" to "By the way",
        "ty" to "Thank you!",
        "np" to "No problem",
        "ttyl" to "Talk to you later",
        "hbd" to "Happy birthday! 🎉",
    )

    /** What [word] expands to, or null. Matching ignores capitals. */
    fun find(word: String, list: List<Pair<String, String>>): String? =
        list.firstOrNull { it.first.equals(word, ignoreCase = true) }?.second

    /** A shortcut must be one word of letters (so it's recognized while typing). */
    fun isValid(shortcut: String): Boolean = shortcut.length in 1..20 && shortcut.all { it.isLetter() }
}
