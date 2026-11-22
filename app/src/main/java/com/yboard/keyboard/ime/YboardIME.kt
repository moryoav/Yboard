package com.yboard.keyboard.ime

import android.Manifest
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.icu.text.BreakIterator
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import com.yboard.keyboard.text.EmojiText
import com.yboard.keyboard.text.EditorPolicy
import com.yboard.keyboard.settings.AiFeature
import com.yboard.keyboard.text.MyWords
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import androidx.annotation.RequiresApi
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import com.yboard.keyboard.keyboard.ClipboardPanel
import com.yboard.keyboard.keyboard.EmojiPanel
import com.yboard.keyboard.keyboard.EmojiSearchBar
import com.yboard.keyboard.keyboard.EnterType
import com.yboard.keyboard.keyboard.GridPadView
import com.yboard.keyboard.keyboard.KeyDef
import com.yboard.keyboard.keyboard.KeySounds
import com.yboard.keyboard.keyboard.KeyType
import com.yboard.keyboard.keyboard.KeyboardRootView
import com.yboard.keyboard.keyboard.KeyboardView
import com.yboard.keyboard.keyboard.Layouts
import com.yboard.keyboard.keyboard.Mode
import com.yboard.keyboard.keyboard.ShiftState
import com.yboard.keyboard.keyboard.ThemeRender
import com.yboard.keyboard.keyboard.ThemesPanel
import com.yboard.keyboard.keyboard.ToolbarView
import com.yboard.keyboard.settings.OneHanded
import com.yboard.keyboard.settings.Prefs
import com.yboard.keyboard.settings.YboardSettings
import com.yboard.keyboard.text.Dictionary
import com.yboard.keyboard.text.EmojiSearch
import com.yboard.keyboard.text.PredictionContext
import com.yboard.keyboard.text.PredictionStats
import com.yboard.keyboard.text.SentenceContext
import com.yboard.keyboard.text.GlideRanking
import com.yboard.keyboard.local.LocalQwen
import com.yboard.keyboard.local.QwenModel
import com.yboard.keyboard.text.Pt
import com.yboard.keyboard.text.Suggester
import com.yboard.keyboard.text.SwipeDecoder
import com.yboard.keyboard.text.UserWords
import com.yboard.keyboard.text.WordReplacement
import com.yboard.keyboard.text.WordFreq
import com.yboard.keyboard.ui.MainActivity
import com.yboard.keyboard.voice.ApiKeyStore
import com.yboard.keyboard.voice.OpenAi
import com.yboard.keyboard.voice.VoiceTyping
import com.yboard.keyboard.stickers.StickerShare
import com.yboard.keyboard.stickers.StickerImages
import com.yboard.keyboard.stickers.StickerImage
import com.yboard.keyboard.stickers.StickerRequestGate
import com.yboard.keyboard.keyboard.StickerPanel
import java.util.concurrent.Executors
import kotlin.math.abs

class YboardIME : InputMethodService(), KeyboardView.Listener, ToolbarView.Listener {

    private var root: KeyboardRootView? = null
    private var settings: YboardSettings = YboardSettings()
    private var mode = Mode.LETTERS
    private var baseMode = Mode.LETTERS
    private var shift = ShiftState.OFF
    private var lastShiftTap = 0L
    private var lastSpaceTime = 0L
    private data class SuggestionSpace(val cursor: Int, val inserted: String)
    private var suggestionSpace: SuggestionSpace? = null
    private var editorInfo: EditorInfo? = null
    private var selecting = false
    private var clipboard: ClipboardManager? = null
    private val clips = mutableListOf<String>()

    // Cursor position reported by the app.
    private var selStart = -1
    private var selEnd = -1

    // Word engine.
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val imageWorker = Executors.newSingleThreadExecutor()
    private var stickerGate: StickerRequestGate? = null
    private var stickerCall: okhttp3.Call? = null
    private var stickerPanel: StickerPanel? = null
    private var stickerImage: StickerImage? = null
    private var stickerSource = ""
    private val dictionaries = HashMap<String, Dictionary?>()
    private val loading = HashSet<String>()
    private var decoder: SwipeDecoder? = null
    private var engineLanguage = ""
    private var enginePersonalized = false
    private var suggestionsAllowed = false
    private var learningAllowed = false
    private var predictionFieldActive = false

    /** What each of the 3 strip slots will type ("" = empty slot). */
    private var slotWords: List<String> = emptyList()
    private var slotDisplay: List<String> = emptyList()
    private var typedSlot = -1
    /** The strip slot holding an emoji suggestion, or -1. */
    private var emojiSlot = -1
    /** In an email box: what each strip slot types (null = empty). */
    private var emailChips: List<com.yboard.keyboard.text.EmailHelper.Chip?> = emptyList()
    private var emailField = false
    private var currentWord = ""
    private val rejected = HashSet<String>()

    private class AutoCorrection(val original: String, val fixed: String, val separator: String)
    private var lastAutoCorrect: AutoCorrection? = null

    private class SwipeCommit(val word: String, val alternatives: List<String>, val before: String, val cursor: Int?)
    private data class RecentSwipe(val word: String, val before: String, val offset: Int?, val alternatives: List<String>)
    private val recentSwipes = ArrayDeque<RecentSwipe>()
    private var lastSwipe: SwipeCommit? = null
    private val pendingSwipes = ArrayDeque<List<SwipeDecoder.Candidate>>()
    private val swipePending get() = pendingSwipes.isNotEmpty()

    // Backspace slide-to-delete-words.
    private var bsOrigin = -1
    private var bsText: CharSequence? = null

    // Emoji search.
    private var emojiSearchBar: EmojiSearchBar? = null
    private var emojiQuery = ""

    // AI voice typing (only while the mic is on).
    private var voice: VoiceTyping? = null
    private val voiceHost = object : VoiceTyping.Host {
        override fun inputConnection() = currentInputConnection
        override fun onVoiceUi(status: String?, level: Float, listening: Boolean) {
            root?.toolbar?.setVoice(status, level, listening)
            if (status == null) {
                voice = null
                updateSuggestions()
            }
        }
        override fun showMessage(text: String) = Toast.makeText(this@YboardIME, text, Toast.LENGTH_LONG).show()
        override fun onDictated(before: String, text: String) {
            learnAccepted(text, before)
        }
        override fun sendMessage(lastTry: Boolean): VoiceTyping.SendResult {
            val info = editorInfo
            android.util.Log.i("YboardVoice", "send: typing in ${info?.packageName}, action ${info?.let { it.imeOptions and EditorInfo.IME_MASK_ACTION }}")
            if (info != null) {
                val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
                val enterIsSend = (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0 &&
                    action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED
                if (enterIsSend) {
                    sendKeyChar('\n')  // the field's own send / go / search
                    return VoiceTyping.SendResult.ACTION
                }
            }
            // Chat apps with their own Send button (WhatsApp): "Tap Send for me" taps it.
            val helper = com.yboard.keyboard.voice.SendButtonService.instance
            if (helper != null) {
                if (helper.tapSend()) return VoiceTyping.SendResult.TAPPED
                if (!lastTry) return VoiceTyping.SendResult.NO_BUTTON
            }
            // Otherwise Enter, which sends when the app's "Enter is send" setting is on.
            sendKeyChar('\n')
            return VoiceTyping.SendResult.ENTER
        }
        override fun sendHelperOn() = com.yboard.keyboard.voice.SendButtonService.instance != null
        override fun tapSendButton(): VoiceTyping.SendResult =
            if (com.yboard.keyboard.voice.SendButtonService.instance?.tapSend() == true) VoiceTyping.SendResult.TAPPED
            else VoiceTyping.SendResult.NO_BUTTON
        override fun isEnglishWord(word: String): Boolean? {
            val d = dictionaries["en"] ?: return null
            val n = Dictionary.normalize(word)
            return d.contains(n) || UserWords.get(this@YboardIME, "en").isKnown(n)
        }
    }

    private val settingsListener: (YboardSettings) -> Unit = { s -> onSettingsChanged(s) }
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { captureClip() }

    // ------------------------------------------------------------ lifecycle

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        settings = Prefs.current
        UserWords.setMyWords(settings.myWords)
        Prefs.addListener(settingsListener)
        clips.addAll(Prefs.clips())
        clipboard = getSystemService(ClipboardManager::class.java)
        clipboard?.addPrimaryClipChangedListener(clipListener)
        LocalQwen.init(this)
        QwenModel.addListener(modelListener)
        settings.enabledLanguages.forEach { ensureDictionary(it) }
        worker.execute { EmojiSearch.load(this) }
        if (settings.sound && settings.soundStyle != "system") KeySounds.warmUp(this)
        if (Build.VERSION.SDK_INT >= 31) {
            // Battery saver can switch blur off; the keyboard then becomes less see-through.
            try { getSystemService(WindowManager::class.java)?.addCrossWindowBlurEnabledListener(mainExecutor, blurListener) } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        closeSticker()
        imageWorker.shutdownNow()
        endVoice()
        cancelAiPredictions()
        if (Build.VERSION.SDK_INT >= 31) {
            try { getSystemService(WindowManager::class.java)?.removeCrossWindowBlurEnabledListener(blurListener) } catch (_: Exception) {}
        }
        Prefs.removeListener(settingsListener)
        clipboard?.removePrimaryClipChangedListener(clipListener)
        worker.shutdown()
        aiExecutor.shutdownNow()
        QwenModel.removeListener(modelListener)
        LocalQwen.clearContext()
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val r = KeyboardRootView(this, preview = false)
        r.keyboard.listener = this
        r.toolbar.listener = this
        root = r
        r.apply(settings)
        updateGesture()
        refreshLayout()
        setupWindow()
        return r
    }

    private fun setupWindow() {
        val w = window?.window ?: return
        w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        if (Build.VERSION.SDK_INT >= 30) {
            // The keyboard window reaches the bottom of the screen. We paint our background
            // there too, and KeyboardRootView leaves room under the keys for the bottom bar.
            WindowCompat.setDecorFitsSystemWindows(w, false)
            w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            @Suppress("DEPRECATION")
            w.navigationBarColor = Color.TRANSPARENT
            w.isNavigationBarContrastEnforced = false
        }
        updateNavIcons()
    }

    private fun updateNavIcons() {
        val w = window?.window ?: return
        val v = root ?: return
        val theme = v.settings.theme
        val light = ThemeRender.isLight(ThemeRender.dominantColor(theme)) && theme.bgOpacity > 0.4f
        WindowCompat.getInsetsController(w, v).isAppearanceLightNavigationBars = light
        if (Build.VERSION.SDK_INT < 30) {
            @Suppress("DEPRECATION")
            w.navigationBarColor = ThemeRender.withAlpha(ThemeRender.dominantColor(theme), 1f)
        }
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    /**
     * Android 16+ lets a keyboard hide the bar Android draws under it (with the switch-keyboard
     * button that jumps to Gboard). Swiping back still closes the keyboard.
     */
    /** Liquid glass: blur the app behind the keyboard (Android 12+, if the phone allows it right now). */
    private fun applyBlur() {
        val r = root ?: return
        if (Build.VERSION.SDK_INT < 31) { r.blurActive = false; return }
        val w = window?.window ?: return
        val wm = getSystemService(WindowManager::class.java)
        val want = r.settings.theme.blur
        val ok = want > 0f && wm?.isCrossWindowBlurEnabled == true
        try {
            w.setBackgroundBlurRadius(if (ok) (want * resources.displayMetrics.density).toInt() else 0)
        } catch (_: Exception) {
        }
        r.blurActive = ok
    }

    private val blurListener = java.util.function.Consumer<Boolean> { applyBlur() }

    private fun applySystemBarChoice() {
        if (Build.VERSION.SDK_INT < 36) return
        val controller = window?.window?.insetsController ?: return
        try {
            if (settings.hideSystemBar) controller.hide(WindowInsets.Type.captionBar())
            else controller.show(WindowInsets.Type.captionBar())
        } catch (_: Exception) {
        }
    }

    override fun onWindowShown() {
        super.onWindowShown()
        applySystemBarChoice()
        applyBlur()
        // Re-read the bottom bar size each time (it changes with rotation and nav mode).
        root?.let { ViewCompat.requestApplyInsets(it) }
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        closeSticker()
        cancelAiPredictions()
        LocalQwen.clearContext()
        qwenWords = emptyList()
        qwenInput = null
        predictionFieldActive = false
        failedInput = null
        endVoice()
        sprinkle = null
        sprinkleRound++
        ensureNamePairs()
        applySystemBarChoice()
        editorInfo = info
        selecting = false
        selStart = info.initialSelStart
        selEnd = info.initialSelEnd
        suggestionSpace = null
        closeEmojiSearch(reopenPanel = false)
        root?.closePanel()
        baseMode = when (info.inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_DATETIME -> Mode.NUMBER
            InputType.TYPE_CLASS_PHONE -> Mode.PHONE
            else -> Mode.LETTERS
        }
        mode = baseMode
        shift = ShiftState.OFF
        suggestionsAllowed = fieldAllowsSuggestions(info)
        emailField = (info.inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT &&
            (info.inputType and InputType.TYPE_MASK_VARIATION).let {
                it == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS || it == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
            }
        LocalQwen.warmUp(this)
        learningAllowed = suggestionsAllowed &&
            (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) == 0
        predictionFieldActive = true
        lastAutoCorrect = null
        lastSwipe = null
        clearReplacementSession()
        rejected.clear()
        root?.keyboard?.enterType = enterTypeFor(info)
        ensureDictionary(settings.activeLanguage)
        rebuildEngine()
        refreshLayout()
        updateAutoShift()
        updateSuggestions()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        suggestionSpace = null
        predictionFieldActive = false
        clearReplacementSession()
        LocalQwen.clearContext()
        closeSticker()
        endVoice()
        cancelAiPredictions()
        qwenWords = emptyList()
        qwenInput = null
        failedInput = null
        stripBefore = null
        root?.keyboard?.cancelAllPointers()
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        suggestionSpace = null
        predictionFieldActive = false
        clearReplacementSession()
        LocalQwen.clearContext()
        closeSticker()
        endVoice()
        super.onFinishInput()
        cancelAiPredictions()
        qwenWords = emptyList()
        qwenInput = null
        failedInput = null
        stripBefore = null
        clearAutofill()
    }

    // ------------------------------------------------- passwords & codes (autofill)

    private var autofillRound = 0

    private fun clearAutofill() {
        autofillRound++
        root?.showAutofill(emptyList(), emptyList())
    }

    /** Android asks which chips the keyboard can show; Yboard asks for ones styled like its keys. */
    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest? {
        if (!settings.autofillChips) return null
        return try {
            AutofillChips.request(this, root?.settings?.theme ?: settings.theme)
        } catch (e: Exception) {
            android.util.Log.w("YboardAutofill", "request failed", e)
            null
        }
    }

    /** Logins / codes arrived (or an empty list: hide them). */
    @RequiresApi(Build.VERSION_CODES.R)
    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        val round = ++autofillRound
        val list = response.inlineSuggestions
        android.util.Log.i("YboardAutofill", "autofill chips: ${list.size}")
        if (list.isEmpty() || !settings.autofillChips) {
            root?.showAutofill(emptyList(), emptyList())
            return true
        }
        AutofillChips.inflate(this, list) { chips, pinned ->
            if (round == autofillRound) root?.showAutofill(chips, pinned)
        }
        return true
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        selStart = newSelStart
        selEnd = newSelEnd
        if (oldSelEnd != newSelEnd) {
            val returnedToWordEnd = replacement?.end == newSelEnd
            revisitWordEnd = if (newSelStart == newSelEnd && (newSelEnd < oldSelEnd || returnedToWordEnd))
                newSelEnd else -1
        }
        suggestionSpace?.let {
            if (newSelStart != it.cursor || newSelEnd != it.cursor) suggestionSpace = null
        }
        voice?.onSelectionChanged(newSelStart, newSelEnd)
        if (bsOrigin >= 0) return  // we're selecting words with the backspace slide
        updateAutoShift()
        updateSuggestions()
    }

    private fun onSettingsChanged(s: YboardSettings) {
        cancelAiPredictions()
        qwenWords = emptyList()
        qwenInput = null
        settings = s
        voice?.namePairs = s.myWordPairs
        UserWords.setMyWords(s.myWords)
        root?.apply(s)
        s.enabledLanguages.forEach { ensureDictionary(it) }
        rebuildEngine()
        refreshLayout()
        updateNavIcons()
        applySystemBarChoice()
        applyBlur()
        updateSuggestions()
        if (s.sound && s.soundStyle != "system") KeySounds.warmUp(this)
    }

    // ----------------------------------------------------------- word engine

    private fun fieldAllowsSuggestions(info: EditorInfo): Boolean {
        return EditorPolicy.suggestions(info.inputType)
    }

    private fun ensureDictionary(lang: String) {
        if (dictionaries.containsKey(lang) || !loading.add(lang)) return
        worker.execute {
            val d = Dictionary.load(this, lang)
            main.post {
                loading.remove(lang)
                dictionaries[lang] = d
                if (lang == settings.activeLanguage) {
                    engineLanguage = ""
                    rebuildEngine()
                }
                val replacingThisLanguage = replacement?.language == lang
                if (replacingThisLanguage) replacement = null
                if (lang == settings.activeLanguage || replacingThisLanguage) updateSuggestions()
            }
        }
    }

    private fun rebuildEngine() {
        val lang = settings.activeLanguage
        val personalized = settings.learnWords && learningAllowed
        if (lang == engineLanguage && personalized == enginePersonalized && decoder != null) { updateGesture(); return }
        val dict = dictionaries[lang]
        val user = UserWords.get(this, lang)
        decoder = dict?.let { d ->
            SwipeDecoder { c ->
                user.swipeCandidates(d.startingWith(c), c, settings.learnWords && learningAllowed)
            }
        }
        engineLanguage = if (dict != null) lang else ""
        enginePersonalized = personalized
        updateGesture()
    }

    private fun updateGesture() {
        root?.keyboard?.gestureTyping = settings.swipeTyping && editorInfo?.let { EditorPolicy.glide(it.inputType) } == true &&
            decoder != null && emojiSearchBar == null
    }

    private fun isWordChar(c: Char) = PredictionContext.isWordChar(c)

    /** The word right before the cursor (what you're typing now). */
    private fun wordBeforeCursor(): String {
        val before = suggestionText?.takeLast(48) ?: currentInputConnection?.getTextBeforeCursor(48, 0) ?: return ""
        var i = before.length
        while (i > 0 && isWordChar(before[i - 1])) i--
        return before.subSequence(i, before.length).toString().trimStart('\'', '’', '׳', '״')
    }

    private fun cursorInsideWord(): Boolean {
        val after = currentInputConnection?.getTextAfterCursor(1, 0)
        return !after.isNullOrEmpty() && after[0].isLetter()
    }

    private fun userWords() = UserWords.get(this, settings.activeLanguage)

    private fun learn(word: String, amount: Int = 1) {
        if (!settings.learnWords || !learningAllowed) return
        if (word.any { it.isDigit() }) return
        userWords().learn(word, amount)
    }

    private fun swipeWordBeforeCursor(): Boolean {
        val ls = lastSwipe ?: return false
        suggestionText?.let { return (ls.cursor == null || ls.cursor == selEnd) && it.endsWith(ls.word) }
        val connection = currentInputConnection ?: return false
        val target = editorTarget(connection, ls.word.length, afterLimit = 0) ?: return false
        return (ls.cursor == null || ls.cursor == (target.cursor ?: selEnd)) && target.before == ls.word
    }

    private fun showStrip(words: List<String>, primary: Int, display: List<String> = words) {
        if (replacement != null) root?.toolbar?.cancelSuggestionPress()
        replacement = null
        stripBefore = suggestionText ?: currentInputConnection?.getTextBeforeCursor(SentenceContext.READ_LIMIT, 0)?.toString()
        stripCursor = selEnd
        modelNote = false
        slotWords = words
        slotDisplay = display
        emojiSlot = -1
        emailChips = emptyList()
        root?.toolbar?.setSuggestions(display, primary)
    }

    // Other AI features keep their own worker. Typing and glide probabilities use the selected local model.
    private val aiExecutor = Executors.newSingleThreadExecutor()
    private var localRound = 0L
    private var localPending: Runnable? = null
    private var qwenInput: SentenceContext.Input? = null
    private var qwenRequested: SentenceContext.Input? = null
    private var qwenWords: List<String> = emptyList()
    private var failedInput: SentenceContext.Input? = null
    private var retryAfter = 0L
    private var stripBefore: String? = null
    private var stripCursor = -1
    private var suggestionText: String? = null
    private var replacement: Replacement? = null
    private var revisitWordEnd = -1
    private var modelNote = false
    private var modelNoteAction = false
    private var predictionModel = QwenModel.selected
    private var predictionModelReady = QwenModel.selectedState.ready
    private val modelListener: () -> Unit = {
        val catalog = QwenModel.state.value
        val selected = catalog.selected
        val ready = catalog[selected].ready
        if (predictionModel != selected || predictionModelReady != ready) {
            predictionModel = selected
            predictionModelReady = ready
            cancelAiPredictions()
            qwenInput = null
            qwenWords = emptyList()
            lastSwipe = null
        }
        failedInput = null
        updateSuggestions()
    }

    private fun cancelAiPredictions() {
        resetPredictionRequests()
        preparedSwipe = null
        LocalQwen.cancel()
    }

    private fun resetPredictionRequests() {
        localRound++
        localPending?.let { main.removeCallbacks(it) }
        localPending = null
        qwenRequested = null
        if (swipePending) {
            pendingSwipes.clear()
            clearStrip()
        }
    }

    private fun typingContextActive() = predictionFieldActive && voice?.isActive != true &&
        emojiSearchBar == null && mode != Mode.NUMBER && mode != Mode.PHONE

    private fun localAllowed() = suggestionsAllowed && learningAllowed && selStart == selEnd && typingContextActive()

    private fun glideAllowed(): Boolean {
        val info = editorInfo ?: return false
        return settings.swipeTyping && EditorPolicy.glide(info.inputType) &&
            (localAllowed() || EditorPolicy.url(info.inputType) && typingContextActive())
    }

    private fun glideTarget(connection: InputConnection) =
        editorTarget(connection, allowSelection = editorInfo?.let { EditorPolicy.url(it.inputType) } == true)

    private fun localInput(): SentenceContext.Input? {
        if (selStart != selEnd || selEnd < 0) return null
        val before = suggestionText ?: currentInputConnection?.getTextBeforeCursor(SentenceContext.READ_LIMIT, 0)?.toString() ?: return null
        val input = SentenceContext.capture(before, selEnd)
        // Never silently turn a sentence longer than the editor read limit into a short tail.
        if (before.length >= SentenceContext.READ_LIMIT && selEnd > before.length && input.context.length >= before.length - input.prefix.length)
            return null
        return input
    }

    private fun showModelNote(text: String) {
        clearStrip()
        modelNote = true
        modelNoteAction = text == "Download model" || text == "Model unavailable"
        root?.toolbar?.setSuggestions(listOf("", text, ""), -1)
    }

    private fun renderLocal(input: SentenceContext.Input): Boolean {
        val words = localCompletions(input)
        if (words.isEmpty()) return false
        if (root?.toolbar?.suggestionTouchActive == true) {
            val round = localRound
            main.postDelayed({ if (round == localRound) updateSuggestions() }, 32)
            return true
        }
        val cased = words.map { word ->
            if (input.prefix.isEmpty() && shift != ShiftState.OFF && settings.activeLanguage == "en")
                word.replaceFirstChar { it.uppercaseChar() }
            else word
        }
        currentWord = input.prefix
        typedSlot = -1
        showStrip(listOf(cased.getOrElse(1) { "" }, cased[0], cased.getOrElse(2) { "" }), 1)
        return true
    }

    private fun localCompletions(input: SentenceContext.Input): List<String> {
        if (qwenInput?.sameWord(input) != true) return emptyList()
        val dictionary = dictionaries[settings.activeLanguage]
        val user = if (settings.learnWords) userWords() else null
        return qwenWords.mapNotNull { word ->
            val normalized = Dictionary.normalize(word)
            val spelling = dictionary?.spelling(normalized)
                ?: user?.takeIf { it.isKnown(normalized) }?.spelling(normalized)
            Suggester.modelCompletion(input.prefix, word, spelling)
        }.distinct().take(3)
    }

    private fun requestLocal(input: SentenceContext.Input) {
        val model = QwenModel.selectedState
        if (!model.ready) { showModelNote(if (model.downloading) "Downloading model…" else "Download model"); return }
        val requested = qwenRequested
        if (requested?.sameWord(input) == true && input.prefix.startsWith(requested.prefix, true)) return
        val prior = qwenInput
        if (prior?.sameWord(input) == true && input.prefix.startsWith(prior.prefix, true) && localCompletions(input).isNotEmpty()) return
        if (prior == input) return
        if (failedInput == input && SystemClock.elapsedRealtime() < retryAfter) return
        cancelAiPredictions()
        val round = localRound
        qwenRequested = input
        val pending = Runnable {
            if (round != localRound || !localAllowed()) return@Runnable
            LocalQwen.predict(input.context, input.prefix) { result ->
                if (round != localRound || !localAllowed()) return@predict
                qwenRequested = null
                val now = localInput() ?: return@predict
                if (!now.sameWord(input) || cursorInsideWord()) return@predict
                result.onSuccess { output ->
                    qwenInput = input
                    qwenWords = output.words
                    PredictionStats.time("qwen", output.elapsedMs)
                    if (!renderLocal(now)) {
                        if (now.prefix != input.prefix) requestLocal(now)
                        else showModelNote("No prediction")
                    }
                }.onFailure {
                    failedInput = now
                    retryAfter = SystemClock.elapsedRealtime() + 3000
                    showModelNote("Model unavailable")
                }
            }
        }
        localPending = pending
        main.postDelayed(pending, 15)
    }

    /** Sentence context before the word being accepted, excluding its unfinished prefix. */
    private fun contextBefore(word: String): String {
        val before = currentInputConnection?.getTextBeforeCursor(SentenceContext.READ_LIMIT, 0)?.toString().orEmpty()
        return if (word.isNotEmpty() && before.endsWith(word)) before.dropLast(word.length) else before
    }

    private fun learnAccepted(text: String, before: String, amount: Int = 1) {
        if (!settings.learnWords || !learningAllowed) return
        Regex("[\\p{L}\\p{M}'’]+|[.!?\\n]").findAll(text).forEach { token ->
            if (token.value in listOf(".", "!", "?", "\n")) return@forEach
            learn(token.value, amount)
        }
    }

    private fun acceptSwipe() {
        val swipe = lastSwipe ?: return
        if (swipeWordBeforeCursor()) learnAccepted(swipe.word, swipe.before)
        lastSwipe = null
    }

    private fun clearStrip() {
        if (replacement != null) root?.toolbar?.cancelSuggestionPress()
        replacement = null
        modelNote = false
        slotWords = emptyList()
        emojiSlot = -1
        typedSlot = -1
        currentWord = ""
        stripBefore = null
        stripCursor = -1
        root?.toolbar?.setSuggestions(emptyList(), -1)
    }

    /** Email boxes: your address and common endings in the strip. */
    private fun showEmailChips() {
        val before = currentInputConnection?.getTextBeforeCursor(80, 0)?.toString() ?: ""
        val chips = com.yboard.keyboard.text.EmailHelper.chips(com.yboard.keyboard.text.EmailHelper.token(before), settings.myEmail)
        if (chips.all { it == null }) { clearStrip(); return }
        currentWord = ""
        typedSlot = -1
        showStrip(chips.map { it?.text ?: "" }, 1)
        emailChips = chips
    }

    private fun updateSuggestions() {
        if (!predictionFieldActive) { clearStrip(); return }
        if (swipePending) return
        val started = SystemClock.elapsedRealtime()
        try {
            if (suggestionsAllowed && settings.suggestions && voice?.isActive != true && emojiSearchBar == null && selStart == selEnd)
                suggestionText = currentInputConnection?.getTextBeforeCursor(SentenceContext.READ_LIMIT, 0)?.toString()
            updateSuggestionsNow()
        } finally {
            suggestionText = null
            PredictionStats.time("local", SystemClock.elapsedRealtime() - started)
        }
    }

    private fun updateSuggestionsNow() {
        if (voice?.isActive == true || sprinkleNote()) { cancelAiPredictions(); return }
        if (emailLayout() && emojiSearchBar == null && selStart == selEnd) { cancelAiPredictions(); showEmailChips(); return }
        if (!localAllowed()) { cancelAiPredictions(); clearStrip(); return }
        val insideWord = cursorInsideWord()
        if ((insideWord || revisitWordEnd == selEnd && selEnd >= 0) && showWordReplacement()) return
        if (!settings.suggestions) { cancelAiPredictions(); clearStrip(); return }
        if (swipeWordBeforeCursor()) {
            val swipe = lastSwipe!!
            val alternatives = swipe.alternatives.filter { it != swipe.word }
            currentWord = ""
            showStrip(listOf(alternatives.getOrElse(0) { "" }, swipe.word, alternatives.getOrElse(1) { "" }), 1)
            return
        }
        lastSwipe = null
        if (insideWord) { cancelAiPredictions(); clearStrip(); return }
        val input = localInput() ?: run { cancelAiPredictions(); showModelNote("Sentence too long"); return }
        if (input.context.isBlank() && input.prefix.isEmpty()) { cancelAiPredictions(); clearStrip(); return }
        if (input.prefix.isEmpty() && !settings.nextWords) { cancelAiPredictions(); clearStrip(); return }
        if (qwenInput?.sameWord(input) == false) { qwenInput = null; qwenWords = emptyList() }
        val engine = LocalQwen.status.value
        if (!renderLocal(input)) showModelNote(when {
            qwenInput == input -> "No prediction"
            engine.model == predictionModel && engine.loading -> "Loading model…"
            else -> "Predicting…"
        })
        requestLocal(input)
    }

    /** Fixes the word before the cursor (if it looks like a typo) and adds [separator]. */
    /** The text shortcut [word] expands to ("omw" -> "On my way!"), or null. */
    private fun shortcutFor(word: String): String? =
        if (settings.shortcutsOn && word.isNotEmpty()) com.yboard.keyboard.text.Shortcuts.find(word, settings.shortcuts) else null

    private fun applyAutoCorrect(separator: String): Boolean {
        if (!suggestionsAllowed) return false
        if (selStart != selEnd || emojiSearchBar != null) return false
        val ic = currentInputConnection ?: return false
        val w = wordBeforeCursor()
        if (w.isEmpty() || cursorInsideWord()) return false
        if (Dictionary.normalize(w) in rejected) return false
        // Text shortcuts first, then typo fixes.
        val fix = shortcutFor(w) ?: return false
        if (fix == w) return false
        ic.beginBatchEdit()
        ic.deleteSurroundingText(w.length, 0)
        ic.commitText(fix + separator, 1)
        ic.endBatchEdit()
        lastAutoCorrect = AutoCorrection(w, fix, separator)
        return true
    }

    /** Called when a word is finished by space / punctuation / enter. */
    private fun finishWord(separator: String): Boolean {
        val wasSwipe = swipeWordBeforeCursor()
        acceptSwipe()
        if (wasSwipe) return false
        val typed = wordBeforeCursor()
        if (typed == "i" && capitalizeTypedPronoun(separator)) {
            learn("I")
            return true
        }
        val before = if (typed.isNotEmpty() && suggestionsAllowed) contextBefore(typed) else ""
        if (applyAutoCorrect(separator)) {
            lastAutoCorrect?.let { learnAccepted(it.fixed, before) }
            return true
        }
        val w = typed
        if (w.isNotEmpty() && !cursorInsideWord() && suggestionsAllowed) {
            learn(w)
        }
        return false
    }

    // ------------------------------------------------------------- state

    private fun enterTypeFor(info: EditorInfo): EnterType {
        if ((info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return EnterType.NEWLINE
        if (isMultiLine(info)) return EnterType.NEWLINE
        return when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO -> EnterType.GO
            EditorInfo.IME_ACTION_SEARCH -> EnterType.SEARCH
            EditorInfo.IME_ACTION_SEND -> EnterType.SEND
            EditorInfo.IME_ACTION_NEXT -> EnterType.NEXT
            EditorInfo.IME_ACTION_DONE -> EnterType.DONE
            else -> EnterType.NEWLINE
        }
    }

    private fun isMultiLine(info: EditorInfo) =
        (info.inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT &&
            (info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0

    private fun refreshLayout() {
        val kv = root?.keyboard ?: return
        // Email addresses are English, with @ and .com keys (and no 🌐 key).
        val email = emailLayout()
        val layout = Layouts.build(
            mode, layoutLanguage(), settings.numberRow, settings.enabledLanguages.size > 1 && !email,
            email = email,
        )
        if (layout != kv.layoutDef) kv.setLayout(layout)
        kv.shiftState = shift
    }

    private fun emailLayout() = emailField && settings.emailHelpers

    /** The language of the letters on screen (email boxes always show English). */
    private fun layoutLanguage() = if (emailLayout()) "en" else settings.activeLanguage

    private fun setShift(s: ShiftState) {
        shift = s
        root?.keyboard?.shiftState = s
    }

    private fun updateAutoShift() {
        if (shift == ShiftState.LOCKED) return
        val info = editorInfo ?: return
        if (!settings.autoCaps || mode != Mode.LETTERS || layoutLanguage() != "en") return
        val ic = currentInputConnection ?: return
        val caps = ic.getCursorCapsMode(info.inputType) != 0
        setShift(if (caps) ShiftState.ON else ShiftState.OFF)
    }

    // ------------------------------------------------------- key handling

    override fun onKey(key: KeyDef) {
        dismissWordReplacement()
        stripBefore = null
        if (swipePending) cancelAiPredictions()
        endVoice()
        if (emojiSearchBar != null && handleSearchKey(key)) return
        if (key.type != KeyType.BACKSPACE) lastAutoCorrect = null
        when (key.type) {
            KeyType.CHAR, KeyType.COMMA, KeyType.PERIOD -> commitChar(key.output)
            KeyType.SPACE -> handleSpace()
            KeyType.BACKSPACE -> handleBackspace()
            KeyType.ENTER -> handleEnter()
            KeyType.SHIFT -> handleShiftTap()
            KeyType.SYMBOLS -> { mode = Mode.SYMBOLS; refreshLayout() }
            KeyType.SYMBOLS2 -> { mode = Mode.SYMBOLS2; refreshLayout() }
            KeyType.LETTERS -> { mode = Mode.LETTERS; refreshLayout(); updateAutoShift() }
            KeyType.GLOBE -> switchLanguage()
        }
    }

    override fun onText(text: String) {
        dismissWordReplacement()
        endVoice()
        cancelAiPredictions()
        if (emojiSearchBar != null) { emojiQuery += text.lowercase(); refreshEmojiSearch(); return }
        commitTextKey(text)
    }

    private fun commitTextKey(text: String) {
        lastAutoCorrect = null
        val ic = currentInputConnection ?: return
        val prependSpace = text.firstOrNull()?.isLetterOrDigit() == true && swipeWordBeforeCursor()
        val replacedSpace = replaceSuggestionSpace(ic, text)
        if (!replacedSpace) {
            val finished = if (text in PUNCTUATION) finishWord(text) else {
                acceptSwipe()
                text.firstOrNull()?.let { !isWordElement(it) } == true && capitalizeTypedPronoun(text)
            }
            if (!finished) ic.commitText(if (prependSpace) " $text" else text, 1)
        }
        finishTextKey(replacedSpace)
    }

    private fun commitChar(s: String) {
        val upper = mode == Mode.LETTERS && layoutLanguage() == "en" && shift != ShiftState.OFF && s.length == 1
        commitTextKey(if (upper) s.uppercase() else s)
    }

    private fun finishTextKey(replacedSpace: Boolean) {
        if (shift == ShiftState.ON) setShift(ShiftState.OFF)
        lastSpaceTime = 0
        if (replacedSpace) {
            // The cursor stayed in place, so the editor may skip its selection callback.
            updateAutoShift()
            updateSuggestions()
        }
    }

    private fun handleSpace() {
        suggestionSpace = null
        val ic = currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        if (settings.doubleSpacePeriod && now - lastSpaceTime < 700) {
            val before = ic.getTextBeforeCursor(2, 0)
            if (before != null && before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()) {
                ic.beginBatchEdit()
                ic.deleteSurroundingText(1, 0)
                ic.commitText(". ", 1)
                ic.endBatchEdit()
                lastSpaceTime = 0
                return
            }
        }
        if (!finishWord(" ")) ic.commitText(" ", 1)
        lastSpaceTime = now
    }

    private fun handleBackspace() {
        suggestionSpace = null
        val ic = currentInputConnection ?: return
        lastSpaceTime = 0
        // Backspace right after an autocorrect puts back what you typed.
        lastAutoCorrect?.let { ac ->
            lastAutoCorrect = null
            val expect = ac.fixed + ac.separator
            if (ic.getTextBeforeCursor(expect.length, 0)?.toString() == expect) {
                ic.beginBatchEdit()
                ic.deleteSurroundingText(expect.length, 0)
                ic.commitText(ac.original, 1)
                ic.endBatchEdit()
                rejected.add(Dictionary.normalize(ac.original))
                learn(ac.original)
                return
            }
        }
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
            return
        }
        // Backspace right after a swiped word deletes the whole word, like Gboard.
        if (swipeWordBeforeCursor()) {
            val w = lastSwipe!!.word
            lastSwipe = null
            ic.deleteSurroundingText(w.length, 0)
            return
        }
        val before = ic.getTextBeforeCursor(32, 0)
        if (before.isNullOrEmpty()) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            return
        }
        // Delete one whole character, including multi-part emoji like 👩‍💻 or flags.
        val text = before.toString()
        val bi = BreakIterator.getCharacterInstance()
        bi.setText(text)
        val start = bi.preceding(text.length)
        val count = if (start == BreakIterator.DONE) 1 else (text.length - start).coerceAtLeast(1)
        ic.deleteSurroundingText(count, 0)
    }

    private fun handleEnter() {
        suggestionSpace = null
        val ic = currentInputConnection ?: return
        val info = editorInfo
        val type = root?.keyboard?.enterType ?: EnterType.NEWLINE
        lastSpaceTime = 0
        // Finish the current word first (autocorrect it if needed), without adding a separator.
        finishWord("")
        when {
            type != EnterType.NEWLINE && info != null ->
                ic.performEditorAction(info.imeOptions and EditorInfo.IME_MASK_ACTION)
            else -> sendKeyChar('\n')
        }
    }

    private fun handleShiftTap() {
        if (mode != Mode.LETTERS) return
        val now = SystemClock.uptimeMillis()
        val next = when (shift) {
            ShiftState.OFF -> ShiftState.ON
            ShiftState.ON -> if (now - lastShiftTap < 350) ShiftState.LOCKED else ShiftState.OFF
            ShiftState.LOCKED -> ShiftState.OFF
        }
        lastShiftTap = now
        setShift(next)
    }

    private fun switchLanguage() {
        val langs = settings.enabledLanguages
        if (langs.size < 2) return
        val i = langs.indexOf(settings.activeLanguage)
        val next = langs[(i + 1) % langs.size]
        mode = Mode.LETTERS
        Prefs.update { it.copy(language = next) }
        if (next != "en") setShift(ShiftState.OFF) else updateAutoShift()
        if (emojiSearchBar != null) refreshEmojiSearch()
    }

    // Nothing in Yboard ever switches to another keyboard app: the 🌐 key only flips
    // between English and Hebrew, and holding space / 🌐 does nothing special.
    override fun onLongPress(key: KeyDef): Boolean = when (key.type) {
        KeyType.SHIFT -> { if (mode == Mode.LETTERS) setShift(ShiftState.LOCKED); true }
        KeyType.COMMA -> { if (emojiSearchBar == null) openEmoji(); true }
        else -> false
    }

    override fun onCursorMove(steps: Int) {
        suggestionSpace = null
        endVoice()
        cancelAiPredictions()
        qwenWords = emptyList()
        qwenInput = null
        val code = if (steps > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
        repeat(abs(steps)) { sendDownUpKeyEvents(code) }
    }

    override fun onOneHanded(action: Int) {
        Prefs.update {
            it.copy(
                oneHanded = if (action == KeyboardView.ONE_HANDED_EXIT) OneHanded.OFF
                else if (it.oneHanded == OneHanded.LEFT) OneHanded.RIGHT else OneHanded.LEFT
            )
        }
    }

    // --------------------------------------------------------- swipe typing

    private data class PreparedSwipe(val connection: InputConnection, val target: EditorTarget, val ticket: Long)
    private var preparedSwipe: PreparedSwipe? = null

    override fun onSwipeStart() {
        preparedSwipe = null
        dismissWordReplacement()
        if (swipePending || !glideAllowed() || !QwenModel.selectedState.ready) return
        val connection = currentInputConnection ?: return
        val target = glideTarget(connection) ?: return
        val before = if (isStandalonePronoun(target)) target.before.dropLast(1) + "I" else target.before
        val context = swipeContext(before, target.cursor ?: selEnd) ?: return
        resetPredictionRequests()
        preparedSwipe = PreparedSwipe(connection, target, LocalQwen.prepare(context))
    }

    override fun onSwipe(path: List<Pt>, keyCenters: Map<Char, Pt>, keyWidth: Float) {
        val preparation = preparedSwipe
        preparedSwipe = null
        endVoice()
        if (!glideAllowed()) return
        val model = QwenModel.selectedState
        if (!model.ready) { showModelNote(if (model.downloading) "Downloading model…" else "Download model"); return }
        val decoder = decoder ?: return
        val candidates = decoder.decodeScored(path, keyCenters, keyWidth, 24, includeFrequency = false)
        if (candidates.isEmpty()) return
        if (swipePending) {
            pendingSwipes.addLast(candidates)
            return
        }
        resetPredictionRequests()
        pendingSwipes.addLast(candidates)
        showModelNote("Reading swipe…")
        recognizeNextSwipe(preparation)
    }

    private data class EditorTarget(val before: String, val after: String?, val cursor: Int?, val selected: String = "")

    private fun editorTarget(
        connection: InputConnection,
        limit: Int = SentenceContext.READ_LIMIT,
        afterLimit: Int = 32,
        allowSelection: Boolean = false,
    ): EditorTarget? {
        if (Build.VERSION.SDK_INT >= 31) {
            connection.getSurroundingText(limit, afterLimit, 0)?.let { text ->
                if (!allowSelection && text.selectionStart != text.selectionEnd) return null
                return EditorTarget(
                    text.text.subSequence(0, text.selectionStart).toString(),
                    text.text.subSequence(text.selectionEnd, text.text.length).toString(),
                    text.offset.takeIf { it >= 0 }?.plus(text.selectionStart),
                    text.text.subSequence(text.selectionStart, text.selectionEnd).toString(),
                )
            }
        }
        val selected = connection.getSelectedText(0)?.toString().orEmpty()
        if (!allowSelection && selected.isNotEmpty()) return null
        val before = connection.getTextBeforeCursor(limit, 0)?.toString() ?: return null
        return EditorTarget(before, connection.getTextAfterCursor(afterLimit, 0)?.toString(), null, selected)
    }

    private fun isWordElement(char: Char): Boolean = isWordChar(char) || char.isDigit()

    private fun isStandalonePronoun(target: EditorTarget): Boolean =
        suggestionsAllowed && layoutLanguage() == "en" && target.before.endsWith('i') &&
            target.before.getOrNull(target.before.length - 2)?.let(::isWordElement) != true &&
            target.after?.firstOrNull()?.let(::isWordElement) != true

    private fun capitalizeTypedPronoun(separator: String): Boolean {
        if (!suggestionsAllowed || layoutLanguage() != "en") return false
        val connection = currentInputConnection ?: return false
        if (connection.getTextBeforeCursor(1, 0)?.toString() != "i") return false
        val target = editorTarget(connection, limit = 2, afterLimit = 1) ?: return false
        return isStandalonePronoun(target) && commitCapitalizedPronoun(connection, target, separator)
    }

    private fun commitCapitalizedPronoun(connection: InputConnection, target: EditorTarget, suffix: String): Boolean {
        val cursor = target.cursor
        if (Build.VERSION.SDK_INT >= 34 && cursor != null && cursor > 0 &&
            connection.replaceText(cursor - 1, cursor, "I$suffix", 1, null)) return true
        connection.beginBatchEdit()
        try {
            return connection.deleteSurroundingText(1, 0) && connection.commitText("I$suffix", 1)
        } finally {
            connection.endBatchEdit()
        }
    }

    private fun capitalizePronoun(word: String): String =
        if (word == "i" && suggestionsAllowed && layoutLanguage() == "en") "I" else word

    private data class Replacement(
        val connection: InputConnection,
        val editor: EditorTarget,
        val cursor: Int,
        val span: WordReplacement.Span,
        val language: String,
    ) {
        val start get() = cursor - span.left
        val end get() = cursor + span.right
    }

    private fun clearReplacementSession() {
        recentSwipes.clear()
        if (replacement != null) root?.toolbar?.cancelSuggestionPress()
        replacement = null
        revisitWordEnd = -1
    }

    private fun dismissWordReplacement() {
        revisitWordEnd = -1
        if (replacement == null) return
        cancelAiPredictions()
        clearStrip()
    }

    private fun showWordReplacement(): Boolean {
        val connection = currentInputConnection ?: return false
        val editor = editorTarget(connection, afterLimit = 64) ?: return false
        val after = editor.after ?: return false
        val cursor = editor.cursor ?: selEnd
        if (cursor < 0 || cursor != selEnd) return false
        val includeWordEnd = revisitWordEnd == cursor && (after.isNotEmpty() || replacement?.end == cursor)
        val span = WordReplacement.atCursor(editor.before, after, includeWordEnd)
            ?: return false
        val target = Replacement(connection, editor, cursor, span, MyWords.languageOf(span.word))
        if (target.start < 0) return false
        if (replacement == target) return true

        root?.toolbar?.cancelSuggestionPress()
        cancelAiPredictions()
        lastSwipe = null
        val beforeWord = editor.before.dropLast(span.left)
        val beforeTail = beforeWord.takeLast(24)
        val recent = recentSwipes.lastOrNull {
            it.word == span.word && it.before == beforeTail && (it.offset == null || it.offset == target.start)
        }
        val originals = recent?.alternatives.orEmpty().filterNot { it == span.word }
        if (originals.isNotEmpty()) {
            showReplacementOptions(target, originals)
            return true
        }

        ensureDictionary(target.language)
        val user = if (settings.learnWords) UserWords.get(this, target.language) else UserWords.inMemory(target.language)
        val options = Suggester(dictionaries[target.language], user, target.language).suggest(span.word, 12).suggestions
        showReplacementOptions(target, options)
        if (options.size > 1 && QwenModel.selectedState.ready) {
            val leftContext = SentenceContext.capture(beforeWord, target.start).let { it.context + it.prefix }
            val nextWord = after.drop(span.right).trimStart(' ', '\t').takeWhile(PredictionContext::isWordChar)
            val phrases = if (nextWord.isEmpty()) options else options.map { "$it $nextWord" }
            LocalQwen.rank(leftContext, phrases) { result ->
                val scores = result.getOrNull() ?: return@rank
                if (replacement != target || !replacementStillCurrent(target) ||
                    root?.toolbar?.suggestionTouchActive == true || scores.size != options.size) return@rank
                showReplacementOptions(target, options.indices.sortedByDescending { scores[it] }.map { options[it] })
            }
        }
        return true
    }

    private fun showReplacementOptions(target: Replacement, options: List<String>) {
        currentWord = ""
        val best = options.firstOrNull()
        if (best == null) clearStrip()
        else showStrip(listOf(options.getOrElse(1) { "" }, best, options.getOrElse(2) { "" }), 1)
        replacement = target
    }

    private fun replacementStillCurrent(target: Replacement): Boolean =
        localAllowed() && selEnd == target.cursor && currentInputConnection === target.connection &&
            editorTarget(target.connection, afterLimit = 64) == target.editor

    private fun replaceWord(target: Replacement, word: String) {
        dismissWordReplacement()
        if (!replacementStillCurrent(target)) {
            updateSuggestions()
            return
        }
        suggestionSpace = null
        lastAutoCorrect = null
        val connection = target.connection
        if (Build.VERSION.SDK_INT >= 34 && target.editor.cursor != null &&
            connection.replaceText(target.start, target.end, word, 1, null)) return

        connection.beginBatchEdit()
        try {
            if (connection.setSelection(target.start, target.end)) {
                if (connection.getSelectedText(0)?.toString() != target.span.word || !connection.commitText(word, 1)) {
                    connection.setSelection(target.cursor, target.cursor)
                }
            }
        } finally {
            connection.endBatchEdit()
        }
    }

    private fun swipeContext(before: String, cursor: Int): String? {
        val input = SentenceContext.capture(before, cursor)
        if (before.length >= SentenceContext.READ_LIMIT && cursor > before.length &&
            input.context.length >= before.length - input.prefix.length) return null
        return if (before.lastOrNull()?.isWhitespace() == false)
            SentenceContext.capture("$before ", cursor + 1).context else input.context
    }

    private fun recognizeNextSwipe(preparation: PreparedSwipe? = null) {
        val candidates = pendingSwipes.first()
        val connection = currentInputConnection
        val target = connection?.let { glideTarget(it) }
        if (connection == null || target == null || !glideAllowed()) {
            cancelAiPredictions()
            updateSuggestions()
            return
        }
        val capitalizeBefore = isStandalonePronoun(target)
        val before = if (capitalizeBefore) target.before.dropLast(1) + "I" else target.before
        val context = swipeContext(before, target.cursor ?: selEnd)
        if (context == null) {
            cancelAiPredictions()
            showModelNote("Sentence too long")
            return
        }
        val preparedTicket = preparation?.takeIf { it.connection === connection && it.target == target }?.ticket
        val round = localRound
        val casing = shift
        LocalQwen.rank(context, candidates.map { it.word }, preparedTicket) { result ->
            if (round != localRound) return@rank
            if (currentInputConnection !== connection || !glideAllowed() || glideTarget(connection) != target) {
                cancelAiPredictions()
                updateSuggestions()
                return@rank
            }
            pendingSwipes.removeFirst()
            val ranked = result.getOrNull()?.let { GlideRanking.rank(candidates, it) }.orEmpty()
            if (ranked.isEmpty()) {
                if (swipePending) recognizeNextSwipe() else showModelNote("Model unavailable")
                return@rank
            }
            fun cased(word: String) = when (casing) {
                ShiftState.LOCKED -> word.uppercase()
                ShiftState.ON -> word.replaceFirstChar { it.uppercaseChar() }
                ShiftState.OFF -> capitalizePronoun(word)
            }
            val alternatives = ranked.map(::cased).distinct()
            val word = alternatives.first()
            acceptSwipe()
            lastAutoCorrect = null
            val space = if (target.selected.isEmpty() &&
                before.lastOrNull()?.let { it.isLetterOrDigit() || it in ".,!?:;" } == true) " " else ""
            suggestionSpace = null
            val insert = space + word
            val committed = if (capitalizeBefore) commitCapitalizedPronoun(connection, target, insert)
                else connection.commitText(insert, 1)
            if (!committed) {
                cancelAiPredictions()
                updateSuggestions()
                return@rank
            }
            if (shift == ShiftState.ON) setShift(ShiftState.OFF)
            lastSwipe = SwipeCommit(word, alternatives, before, target.cursor?.plus(insert.length))
            if (suggestionsAllowed) {
                if (recentSwipes.size == 32) recentSwipes.removeFirst()
                recentSwipes.addLast(RecentSwipe(word, (before + space).takeLast(24),
                    target.cursor?.plus(space.length), alternatives))
            }
            lastSpaceTime = 0
            currentWord = ""
            if (swipePending) {
                // Read the updated editor so the next word is ranked with this word as context.
                updateAutoShift()
                recognizeNextSwipe()
            } else if (suggestionsAllowed) {
                val others = alternatives.drop(1)
                showStrip(listOf(others.getOrElse(0) { "" }, word, others.getOrElse(1) { "" }), 1)
            } else {
                clearStrip()
            }
        }
    }

    // ------------------------------------------------ backspace word-swipe

    override fun onBackspaceSwipe(words: Int) {
        suggestionSpace = null
        endVoice()
        if (swipePending) cancelAiPredictions()
        val ic = currentInputConnection ?: return
        if (bsOrigin < 0) {
            if (selStart < 0 || selStart != selEnd) return
            bsOrigin = selStart
            bsText = ic.getTextBeforeCursor(2000, 0) ?: ""
        }
        val text = bsText ?: return
        var i = text.length
        repeat(words) {
            while (i > 0 && !isWordChar(text[i - 1]) && !text[i - 1].isDigit()) i--
            while (i > 0 && (isWordChar(text[i - 1]) || text[i - 1].isDigit())) i--
        }
        val start = bsOrigin - (text.length - i)
        ic.setSelection(start.coerceAtLeast(0), bsOrigin)
    }

    override fun onBackspaceSwipeEnd(apply: Boolean) {
        val ic = currentInputConnection
        if (ic != null && bsOrigin >= 0) {
            if (apply) ic.commitText("", 1) else ic.setSelection(bsOrigin, bsOrigin)
        }
        bsOrigin = -1
        bsText = null
        lastSwipe = null
        lastAutoCorrect = null
        updateSuggestions()
    }

    // ------------------------------------------------------------ toolbar

    override fun onSuggestion(index: Int) {
        if (modelNote) { if (modelNoteAction) openApp("typing"); return }
        endVoice()
        sprinkle?.let { sp ->
            if (index == 1 && sp.newText != null) undoSprinkle()
            return
        }
        val word = slotWords.getOrNull(index)?.takeIf { it.isNotEmpty() } ?: return
        replacement?.let { target ->
            replaceWord(target, word)
            return
        }
        val ic = currentInputConnection ?: return
        if (stripBefore == null || selStart != selEnd || stripCursor != selEnd ||
            ic.getTextBeforeCursor(SentenceContext.READ_LIMIT, 0)?.toString() != stripBefore) {
            updateSuggestions()
            return
        }
        cancelAiPredictions()
        suggestionSpace = null
        PredictionStats.count("accepted")
        lastAutoCorrect = null
        emailChips.getOrNull(index)?.let { chip ->
            ic.beginBatchEdit()
            if (chip.replace.isNotEmpty()) ic.deleteSurroundingText(chip.replace.length, 0)
            ic.commitText(chip.text, 1)
            ic.endBatchEdit()
            return
        }
        if (index == emojiSlot) {
            // Keep the word, add the emoji after it: "pizza 🍕 "
            val after = ic.getTextBeforeCursor(1, 0)
            val space = if (!after.isNullOrEmpty() && !after.last().isWhitespace()) " " else ""
            commitSpacedSuggestion(ic, "$space$word")
            addRecentEmoji(word)
            lastSwipe = null
            return
        }
        val ls = lastSwipe
        if (ls != null && swipeWordBeforeCursor()) {
            // A tap accepts the word and advances the strip to next-word predictions.
            ic.beginBatchEdit()
            ic.deleteSurroundingText(ls.word.length, 0)
            lastSwipe = null
            commitSpacedSuggestion(ic, word, ls.word.length)
            ic.endBatchEdit()
            learnAccepted(word, ls.before, 2)
            currentWord = ""
            updateSuggestions()
            return
        }
        val w = currentWord
        val before = contextBefore(w)
        ic.beginBatchEdit()
        ic.deleteSurroundingText(w.length, 0)
        commitSpacedSuggestion(ic, word, w.length)
        ic.endBatchEdit()
        // Picking exactly what you typed teaches Yboard that word right away.
        learnAccepted(word, before, 2)
        currentWord = ""
        if (shift == ShiftState.ON) setShift(ShiftState.OFF)
        updateSuggestions()
    }

    private fun commitSpacedSuggestion(ic: InputConnection, word: String, replacedLength: Int = 0) {
        val inserted = "${capitalizePronoun(word)} "
        val start = selEnd - replacedLength
        if (ic.commitText(inserted, 1) && start >= 0) {
            suggestionSpace = SuggestionSpace(start + inserted.length, inserted)
        }
        lastSpaceTime = 0
    }

    private fun replaceSuggestionSpace(ic: InputConnection, punctuation: String): Boolean {
        val space = suggestionSpace ?: return false
        suggestionSpace = null
        if (punctuation !in PUNCTUATION) return false
        val target = editorTarget(ic, space.inserted.length) ?: return false
        if ((target.cursor != null && target.cursor != space.cursor) || !target.before.endsWith(space.inserted)) return false
        ic.beginBatchEdit()
        try {
            if (!ic.deleteSurroundingText(1, 0)) return false
            ic.commitText(punctuation, 1)
            return true
        } finally {
            ic.endBatchEdit()
        }
    }

    override fun onSuggestionLongPress(index: Int) {
        if (replacement != null) return
        if (sprinkle != null) return
        val word = slotWords.getOrNull(index)?.takeIf { it.isNotEmpty() } ?: return
        val norm = Dictionary.normalize(word)
        val inDict = dictionaries[settings.activeLanguage]?.contains(norm) == true
        if (userWords().isMine(norm)) {
            Toast.makeText(this, "“$word” is in My words. Remove it in Yboard → Typing → My words", Toast.LENGTH_SHORT).show()
            return
        }
        if (!inDict && userWords().count(norm) > 0) {
            userWords().forget(norm)
            Toast.makeText(this, "“$word” removed from learned words", Toast.LENGTH_SHORT).show()
            updateSuggestions()
        }
    }

    override fun onToolbar(action: ToolbarView.Action) {
        val r = root ?: return
        if (swipePending) cancelAiPredictions()
        if (action != ToolbarView.Action.STICKER) closeSticker()
        if (action == ToolbarView.Action.MIC) {
            toggleVoice()
            return
        }
        endVoice()
        if (r.toolbar.active == action) {
            if (action == ToolbarView.Action.STICKER) closeSticker()
            r.closePanel()
            return
        }
        when (action) {
            ToolbarView.Action.MENU -> openApp()
            ToolbarView.Action.UNDO -> {
                r.closePanel()
                sendKey(KeyEvent.KEYCODE_Z, false, ctrl = true)
            }
            ToolbarView.Action.CLIPBOARD -> openClipboard()
            ToolbarView.Action.THEMES -> openThemes()
            ToolbarView.Action.EDIT -> openEditing()
            ToolbarView.Action.SPRINKLE -> { r.closePanel(); sprinkleEmojis() }
            ToolbarView.Action.STICKER -> openSticker()
            ToolbarView.Action.ONE_HANDED -> {
                r.closePanel()
                Prefs.update { it.copy(oneHanded = if (it.oneHanded == OneHanded.OFF) OneHanded.RIGHT else OneHanded.OFF) }
            }
            ToolbarView.Action.MIC -> Unit
        }
    }

    private fun openApp(screen: String? = null) {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (screen != null) intent.putExtra(MainActivity.EXTRA_OPEN, screen)
        startActivity(intent)
        requestHideSelf(0)
    }

    private fun openSticker() {
        if (isPasswordField(editorInfo)) { toast("Stickers are off in password fields"); return }
        val editor = editorInfo ?: return
        if (StickerShare.mimeTypeFor(androidx.core.view.inputmethod.EditorInfoCompat.getContentMimeTypes(editor).toList()) == null) {
            toast("This app doesn't accept WebP stickers from keyboards."); return
        }
        if (ApiKeyStore.get(this) == null) { toast("Add your OpenAI key first: Yboard → Voice typing"); return }
        val ic = currentInputConnection ?: return
        val source = boxTarget(ic)?.second ?: run {
            val selected = ic.getSelectedText(0)?.toString()
            if (!selected.isNullOrBlank()) selected else {
                val before = ic.getTextBeforeCursor(1501, 0) ?: return@run ""
                val after = ic.getTextAfterCursor(1501, 0) ?: ""
                before.toString() + after
            }
        }
        if (source.isBlank()) { toast("Type a message first, then tap Sticker."); return }
        if (source.length > 1500) { toast("Select up to 1,500 characters to turn into a sticker."); return }
        closeSticker()
        closeEmojiSearch(reopenPanel = false)
        stickerSource = source
        stickerGate = StickerRequestGate()
        val panel = StickerPanel(this, source, settings.aiModels[AiFeature.STICKER].model,
            onClose = { closeSticker(); root?.closePanel(); updateSuggestions() },
            onRecreate = { createSticker() }, onSend = { sendSticker() })
        stickerPanel = panel
        root?.showPanel(panel, ToolbarView.Action.STICKER)
        createSticker()
    }

    private fun createSticker() {
        val gate = stickerGate ?: return
        val panel = stickerPanel ?: return
        val key = ApiKeyStore.get(this) ?: return
        val choice = settings.aiModels[AiFeature.STICKER]
        val token = gate.next()
        stickerCall?.cancel()
        val call = StickerImages.call(key, stickerSource, choice)
        stickerCall = call
        panel.loading(choice.model)
        imageWorker.execute {
            val result = runCatching { StickerImages.execute(this, call, choice) }
            main.post {
                if (stickerGate !== gate || !gate.accepts(token)) {
                    result.getOrNull()?.let { it.bitmap.recycle(); it.file.delete() }
                    return@post
                }
                stickerCall = null
                result.onSuccess { image ->
                    val previous = stickerImage
                    stickerImage = image
                    panel.show(image)
                    previous?.let { it.bitmap.recycle(); it.file.delete() }
                }.onFailure { error ->
                    panel.error(StickerImages.friendly(error))
                    com.yboard.keyboard.voice.AiStatus.report(AiFeature.STICKER, "Last sticker request failed")
                }
            }
        }
    }

    private fun sendSticker() {
        if (stickerGate == null || stickerCall != null) return
        val image = stickerImage ?: return
        when (StickerShare.send(this, currentInputConnection, editorInfo, image.file)) {
            StickerShare.Result.ACCEPTED -> {
                // The receiving app may read this URI after this keyboard panel is closed.
                stickerImage = null
                stickerPanel?.clearPreview()
                image.bitmap.recycle()
                closeSticker()
                root?.closePanel()
                updateSuggestions()
            }
            StickerShare.Result.UNSUPPORTED -> stickerPanel?.error("This app doesn't accept WebP stickers here.")
            StickerShare.Result.REJECTED -> stickerPanel?.error("The app rejected this sticker. You can recreate it or try sending again.")
            StickerShare.Result.FAILED -> stickerPanel?.error("Couldn't share this sticker. Try sending it again.")
        }
    }

    private fun closeSticker() {
        stickerGate?.close()
        stickerGate = null
        stickerCall?.cancel()
        stickerCall = null
        stickerPanel?.clearPreview()
        stickerPanel = null
        stickerImage?.let { it.bitmap.recycle(); it.file.delete() }
        stickerImage = null
        stickerSource = ""
    }

    // ------------------------------------------------------ ✨ emoji sprinkle

    /** A sprinkle on its way ([newText] null) or just done (the strip offers Undo). */
    private class Sprinkle(val start: Int, val oldText: String, var newText: String? = null)
    private var sprinkle: Sprinkle? = null
    private var sprinkleRound = 0

    /** The text to change: what's selected, else the whole box. (Absolute start, text) or null. */
    private fun boxTarget(ic: InputConnection): Pair<Int, String>? {
        val et = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return null
        val full = et.text?.toString() ?: return null
        val a = minOf(et.selectionStart, et.selectionEnd)
        val b = maxOf(et.selectionStart, et.selectionEnd)
        if (a >= 0 && b <= full.length && a < b) return et.startOffset + a to full.substring(a, b)
        return et.startOffset to full
    }

    /** Whether the box still has [text] at [start] (nothing was typed over it). */
    private fun stillThere(ic: InputConnection, start: Int, text: String): Boolean {
        val et = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return false
        val full = et.text?.toString() ?: return false
        val i = start - et.startOffset
        return i >= 0 && full.regionMatches(i, text, 0, text.length)
    }

    private fun replaceText(ic: InputConnection, start: Int, length: Int, text: String) {
        ic.beginBatchEdit()
        ic.setSelection(start, start + length)
        ic.commitText(text, 1)
        ic.endBatchEdit()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    /** One message in the middle of the strip (a tap on it goes to [onSuggestion]). */
    private fun showNote(text: String) {
        slotWords = listOf("", text, "")
        slotDisplay = slotWords
        emojiSlot = -1
        typedSlot = -1
        emailChips = emptyList()
        root?.toolbar?.setSuggestions(slotWords, 1)
    }

    /** ✨: the AI adds fitting emojis to your message (or the words you selected). */
    private fun sprinkleEmojis() {
        val key = ApiKeyStore.get(this)
        if (key == null) { toast("Add your OpenAI key first: Yboard → Voice typing"); return }
        if (isPasswordField(editorInfo)) return
        val ic = currentInputConnection ?: return
        val target = boxTarget(ic)
        if (target == null) { toast("This app doesn't let the keyboard read its text"); return }
        val (start, text) = target
        if (text.isBlank()) { toast("Write your message first, then tap ✨"); return }
        if (text.length > 1500) { toast("That's long. Select the part to add emojis to."); return }
        val round = ++sprinkleRound
        val sp = Sprinkle(start, text)
        sprinkle = sp
        showNote("✨ Adding emojis…")
        aiExecutor.execute {
            val r = runCatching { OpenAi.sprinkle(key, text) }
            main.post {
                if (round != sprinkleRound || sprinkle !== sp) return@post
                val out = r.getOrNull()
                val c = currentInputConnection
                val merged = out?.let { EmojiText.sprinkleResult(text, it) }
                when {
                    out == null -> toast(OpenAi.friendly(r.exceptionOrNull() ?: Exception()))
                    c == null || !stillThere(c, start, text) -> toast("The text changed meanwhile. Tap ✨ again.")
                    merged == text -> toast("No emojis to add")
                    merged != null -> {
                        replaceText(c, start, text.length, merged)
                        sp.newText = merged
                        android.util.Log.i("YboardTyping", "sprinkle: added emojis")
                        showNote("↩ Undo emojis")
                        return@post
                    }
                }
                sprinkle = null
                updateSuggestions()
            }
        }
    }

    private fun undoSprinkle() {
        val sp = sprinkle ?: return
        val now = sp.newText ?: return
        sprinkle = null
        val ic = currentInputConnection ?: return
        if (stillThere(ic, sp.start, now)) replaceText(ic, sp.start, now.length, sp.oldText)
        updateSuggestions()
    }

    /** While a sprinkle is on its way or just done, the strip shows it. False = show the usual strip. */
    private fun sprinkleNote(): Boolean {
        val sp = sprinkle ?: return false
        val done = sp.newText
        if (done == null) { showNote("✨ Adding emojis…"); return true }
        val ic = currentInputConnection
        if (ic != null && selEnd <= sp.start + done.length && stillThere(ic, sp.start, done)) {
            showNote("↩ Undo emojis")
            return true
        }
        sprinkle = null
        return false
    }

    // ------------------------------------------------ names in both languages

    private val pairsAsked = HashSet<String>()

    /**
     * Words in "My words" that don't have their other-language spelling yet ("Yoav" ⇄ "יואב") get it from
     * the AI in the background, once, so voice typing can write a name in the language you're speaking.
     */
    private fun ensureNamePairs() {
        val s = settings
        val missing = s.myWords.filter { MyWords.pairOf(it, s.myWordPairs) == null && it !in pairsAsked }.take(40)
        if (missing.isEmpty()) return
        val key = ApiKeyStore.get(this) ?: return
        pairsAsked.addAll(missing)
        aiExecutor.execute {
            val found = runCatching { OpenAi.otherSpellings(key, missing, s.myWords) }.getOrDefault(emptyMap())
            android.util.Log.i("YboardVoice", "names: found ${found.size} of ${missing.size} spellings")
            if (found.isEmpty()) return@execute
            main.post {
                Prefs.update { st ->
                    var pairs = st.myWordPairs
                    found.forEach { (w, other) ->
                        if (MyWords.pairOf(w, pairs) == null) MyWords.withPair(pairs, w, other)?.let { pairs = it }
                    }
                    st.copy(myWordPairs = pairs)
                }
            }
        }
    }

    // ------------------------------------------------------- AI voice typing

    private fun endVoice() {
        voice?.end()
        voice = null
    }

    private fun isPasswordField(info: EditorInfo?): Boolean {
        info ?: return false
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        return when (info.inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    private fun toggleVoice() {
        suggestionSpace = null
        voice?.let {
            if (it.isActive) { it.stop(); return }
        }
        if (isPasswordField(editorInfo)) {
            Toast.makeText(this, "Voice typing is off in password fields", Toast.LENGTH_SHORT).show()
            return
        }
        val key = ApiKeyStore.get(this)
        if (key == null) {
            Toast.makeText(this, "Add your OpenAI key in Yboard → Voice typing", Toast.LENGTH_LONG).show()
            openApp(MainActivity.OPEN_VOICE)
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Allow the microphone in Yboard → Voice typing", Toast.LENGTH_LONG).show()
            openApp(MainActivity.OPEN_VOICE)
            return
        }
        closeEmojiSearch(reopenPanel = false)
        root?.closePanel()
        clearStrip()
        clearAutofill()
        ensureNamePairs()
        android.util.Log.i("YboardVoice", "names: ${settings.myWordPairs.size} known in both languages")
        voice = VoiceTyping(
            apiKey = key,
            languages = settings.voiceLanguageList,
            keyboardLanguage = settings.activeLanguage,
            smartCommands = settings.voiceCommands,
            host = voiceHost,
            vocabulary = settings.myWords,
            namePairs = settings.myWordPairs,
            options = settings.voiceOptions,
            initialSelection = minOf(selStart, selEnd),
        ).also { it.start() }
    }

    private fun addRecentEmoji(e: String) {
        val recents = Prefs.recentEmoji()
        recents.remove(e); recents.add(0, e)
        while (recents.size > 32) recents.removeAt(recents.size - 1)
        Prefs.saveRecentEmoji(recents)
    }

    private fun openEmoji() {
        val r = root ?: return
        if (swipePending) cancelAiPredictions()
        r.showPanel(EmojiPanel(
            this,
            onEmoji = { e -> lastSwipe = null; currentInputConnection?.commitText(e, 1) },
            onBackspace = { handleBackspace() },
            onSpace = { handleSpace() },
            onClose = { r.closePanel() },
            onSearch = { openEmojiSearch() },
        ), null)
        clearStrip()
    }

    // ------------------------------------------------------- emoji search

    private fun openEmojiSearch() {
        val r = root ?: return
        r.closePanel()
        emojiQuery = ""
        val bar = EmojiSearchBar(
            this,
            onPick = { e -> currentInputConnection?.commitText(e, 1); addRecentEmoji(e) },
            onBack = { closeEmojiSearch(reopenPanel = true) },
        )
        emojiSearchBar = bar
        r.showSearchBar(bar)
        mode = Mode.LETTERS
        setShift(ShiftState.OFF)
        refreshLayout()
        updateGesture()
        clearStrip()
        if (!EmojiSearch.isLoaded) worker.execute { EmojiSearch.load(this); main.post { refreshEmojiSearch() } }
        refreshEmojiSearch()
    }

    private fun closeEmojiSearch(reopenPanel: Boolean) {
        if (emojiSearchBar == null) return
        root?.hideSearchBar()
        emojiSearchBar = null
        emojiQuery = ""
        updateGesture()
        if (reopenPanel) openEmoji() else updateSuggestions()
    }

    private fun refreshEmojiSearch() {
        emojiSearchBar?.update(emojiQuery, EmojiSearch.search(emojiQuery))
    }

    /** While searching emoji, letters go into the search box instead of the app. */
    private fun handleSearchKey(key: KeyDef): Boolean {
        when (key.type) {
            KeyType.CHAR, KeyType.COMMA, KeyType.PERIOD -> emojiQuery += key.output.lowercase()
            KeyType.SPACE -> if (emojiQuery.isNotEmpty() && !emojiQuery.endsWith(" ")) emojiQuery += " "
            KeyType.BACKSPACE -> emojiQuery = emojiQuery.dropLast(1)
            KeyType.ENTER -> {
                EmojiSearch.search(emojiQuery).firstOrNull()?.let { e ->
                    currentInputConnection?.commitText(e, 1); addRecentEmoji(e)
                }
                return true
            }
            else -> return false
        }
        refreshEmojiSearch()
        return true
    }

    // ------------------------------------------------------- other panels

    private fun openClipboard() {
        val r = root ?: return
        captureClip()
        val panel = ClipboardPanel(
            this,
            onPick = { text -> currentInputConnection?.commitText(text, 1) },
            onDelete = { text -> clips.remove(text); Prefs.saveClips(clips); (r.panelView as? ClipboardPanel)?.setItems(clips) },
            onClear = { clips.clear(); Prefs.saveClips(clips); (r.panelView as? ClipboardPanel)?.setItems(clips) },
        )
        panel.setItems(clips)
        r.showPanel(panel, ToolbarView.Action.CLIPBOARD)
    }

    private fun openThemes() {
        val r = root ?: return
        r.showPanel(ThemesPanel(
            this,
            onPick = { theme -> Prefs.update { it.copy(theme = theme) } },
            onBgOpacity = { v -> Prefs.updateTheme { it.copy(bgOpacity = v) } },
            onKeyOpacity = { v -> Prefs.updateTheme { it.copy(keyOpacity = v) } },
            onMore = { openApp() },
        ), ToolbarView.Action.THEMES)
    }

    private fun openEditing() {
        val r = root ?: return
        val pad = GridPadView.editPanel(this)
        pad.toggled = if (selecting) setOf(GridPadView.SELECT) else emptySet()
        pad.onCell = { id -> onEditAction(id, pad) }
        r.showPanel(pad, ToolbarView.Action.EDIT)
    }

    private fun onEditAction(id: Int, pad: GridPadView) {
        val ic = currentInputConnection ?: return
        when (id) {
            GridPadView.UP -> sendKey(KeyEvent.KEYCODE_DPAD_UP, selecting)
            GridPadView.DOWN -> sendKey(KeyEvent.KEYCODE_DPAD_DOWN, selecting)
            GridPadView.LEFT -> sendKey(KeyEvent.KEYCODE_DPAD_LEFT, selecting)
            GridPadView.RIGHT -> sendKey(KeyEvent.KEYCODE_DPAD_RIGHT, selecting)
            GridPadView.HOME -> sendKey(KeyEvent.KEYCODE_MOVE_HOME, selecting)
            GridPadView.END -> sendKey(KeyEvent.KEYCODE_MOVE_END, selecting)
            GridPadView.SELECT -> {
                selecting = !selecting
                pad.toggled = if (selecting) setOf(GridPadView.SELECT) else emptySet()
            }
            GridPadView.SELECT_ALL -> ic.performContextMenuAction(android.R.id.selectAll)
            GridPadView.COPY -> ic.performContextMenuAction(android.R.id.copy)
            GridPadView.CUT -> ic.performContextMenuAction(android.R.id.cut)
            GridPadView.PASTE -> ic.performContextMenuAction(android.R.id.paste)
            GridPadView.BACKSPACE -> handleBackspace()
            GridPadView.UNDO -> sendKey(KeyEvent.KEYCODE_Z, false, ctrl = true)
            GridPadView.REDO -> sendKey(KeyEvent.KEYCODE_Z, true, ctrl = true)
        }
    }

    private fun sendKey(code: Int, shiftHeld: Boolean, ctrl: Boolean = false) {
        val ic = currentInputConnection ?: return
        var meta = 0
        if (shiftHeld) meta = meta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        if (ctrl) meta = meta or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        val now = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, meta))
    }

    private fun captureClip() {
        try {
            val clip = clipboard?.primaryClip ?: return
            val extras = clip.description?.extras
            if (extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true) return
            if (clip.itemCount == 0) return
            val text = clip.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
            if (text.isNullOrEmpty()) return
            if (ApiKeyStore.looksLikeSecret(text)) return  // never keep API keys / tokens
            clips.remove(text)
            clips.add(0, text)
            while (clips.size > 25) clips.removeAt(clips.size - 1)
            Prefs.saveClips(clips)
            (root?.panelView as? ClipboardPanel)?.setItems(clips)
        } catch (_: Exception) {
        }
    }

    companion object {
        /** Typing one of these right after a word finishes the word (and may autocorrect it). */
        private val PUNCTUATION = setOf(".", ",", "!", "?", ";", ":")
    }
}
