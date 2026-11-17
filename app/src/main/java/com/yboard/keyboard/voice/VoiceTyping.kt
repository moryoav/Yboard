package com.yboard.keyboard.voice

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * AI voice typing, while the mic is on.
 *
 * Words show up in the text field as you say them (underlined, like Gboard). Words that sound
 * like a command ("delete that", "question mark", "תמחק את זה") are NOT shown in the field; they
 * appear in the bar above the keys instead, and are carried out when you pause:
 * simple commands right away on the phone, anything trickier by the AI.
 * Phrases are always applied in the order they were spoken.
 *
 * Yboard keeps its own copy of the text before the cursor while the mic is on (read once at the
 * start, then updated with every change it makes), because some apps report their text late.
 *
 * Everything here runs on the main thread except the network calls and the microphone.
 */
class VoiceTyping(
    private val apiKey: String,
    private val languages: List<String>,
    private val keyboardLanguage: String,
    private val smartCommands: Boolean,
    private val host: Host,
    /** Your own words (names, slang), so the speech model spells them right. */
    private val vocabulary: List<String> = emptyList(),
    /** (English, Hebrew) spellings of your names: a name comes out in the language you're speaking. */
    var namePairs: List<Pair<String, String>> = emptyList(),
) : MicStream.Listener, LiveTranscriber.Listener {

    /** What pressing "send" did. */
    enum class SendResult {
        /** The text box's own send action (or an Enter that sends). */
        ACTION,
        /** "Tap Send for me" tapped the app's Send button. */
        TAPPED,
        /** "Tap Send for me" is on but found no Send button (yet). Nothing was done. */
        NO_BUTTON,
        /** Pressed Enter, which may only add a new line. */
        ENTER,
    }

    interface Host {
        fun inputConnection(): InputConnection?
        /** [status] null = voice typing has ended. */
        fun onVoiceUi(status: String?, level: Float, listening: Boolean)
        fun showMessage(text: String)
        /**
         * Sends the message: the box's send action, else the app's Send button ("Tap Send for me").
         * Falls back to Enter only when [lastTry] (so a button that's still appearing gets more chances).
         */
        fun sendMessage(lastTry: Boolean): SendResult
        /** Taps the app's Send button only (no Enter, no box action): TAPPED or NO_BUTTON. */
        fun tapSendButton(): SendResult = SendResult.NO_BUTTON
        /** "Tap Send for me" is on. */
        fun sendHelperOn(): Boolean = false
        /** Dictated [text] was typed after [before] (so next-word predictions can learn from it). */
        fun onDictated(before: String, text: String) {}
        /** Whether [word] is English (from the dictionary), or null if that can't be told. */
        fun isEnglishWord(word: String): Boolean? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()      // phrase-by-phrase transcription
    private val aiWorker = Executors.newSingleThreadExecutor()    // the command AI
    private val mic = MicStream(this)
    private var live: LiveTranscriber? = null
    private var connected = false
    /** Live transcription isn't available: send each phrase as a recording instead. */
    private var phraseMode = false

    private class Phrase(val id: String) {
        val text = StringBuilder()
        var final: String? = null
        var done = false
        /** The phrase's recording (phrase-by-phrase mode), to ask again if it came back in another language. */
        var audio: ByteArray? = null
        /** Live mode: which recorded phrase this is (they're numbered in order). */
        var segment = -1
        var retried = false
        val shown: String get() = (final ?: text.toString()).trim()
    }

    /** Recordings of the phrases sent live, by number, to send one again if it comes back in another language. */
    private val segmentAudio = HashMap<Int, ByteArray>()
    private var segmentsEnded = 0
    /** Phrases OpenAI has started on (each live phrase gets the next number, in order). */
    private var segmentsSeen = 0
    private var toldOnlyHebrewEnglish = false

    private val phrases = LinkedHashMap<String, Phrase>()
    private val finishedIds = HashSet<String>()
    private var localIds = 0
    /**
     * Phrases we ended before any of their words arrived. We wait for those when stopping.
     * (Usually words arrive while you're still talking, so this stays 0.)
     */
    private var expecting = 0
    private var thinking: String? = null     // a phrase the AI is deciding about
    private var composing = ""               // what we show underlined in the field
    /** Our copy of the text before the cursor (without the underlined preview). */
    private val text = StringBuilder()
    /** Phrases typed by voice this time, newest last (so "delete that" can be said again and again). */
    private val typed = ArrayList<String>()
    private var phraseCount = 0
    /** The whole text from just before "delete everything", so "undo" can bring it back. */
    private var deletedAll: String? = null

    /** A "send" in progress. New phrases wait (and nothing is previewed) until it's done. */
    private class Sending {
        var looks = 0
        var retapped = false
        var boxBefore: String? = null
        var beforeCursor: String? = null
        var result: SendResult? = null
    }
    private var sending: Sending? = null
    /** "...see you soon, send": send once the AI has finished with the words before it. */
    private var sendAfterThinking = false

    private val phraseAudio = ByteArrayOutputStream()

    var isActive = false
        private set
    private var stopping = false
    private var level = 0f

    fun start() {
        isActive = true
        host.inputConnection()?.let { ic ->
            ic.finishComposingText()
            text.append(ic.getTextBeforeCursor(MAX_TEXT, 0) ?: "")
        }
        live = LiveTranscriber(apiKey, languages, this, vocabulary, namePairs).also { it.connect() }
        mic.start()
        ui()
    }

    /** Mic button tapped again: stop listening, finish the last phrase, then end. */
    fun stop() {
        if (!isActive || stopping) return
        stopping = true
        mic.stop()
        ui()
        // Never hang around: give OpenAI a few seconds for the last words, the AI (or a send) a bit longer.
        main.postDelayed({ if (thinking == null && sending == null) end() else main.postDelayed({ end() }, 8000) }, 4000)
    }

    /** End right away (a key was typed, or the keyboard closed). Dictated words shown so far stay. */
    fun end() {
        if (!isActive) return
        isActive = false
        mic.stop()
        live?.close()
        live = null
        main.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        aiWorker.shutdownNow()
        if (composing.isNotEmpty()) host.inputConnection()?.finishComposingText()
        composing = ""
        host.onVoiceUi(null, 0f, false)
    }

    private fun fail(message: String) {
        host.showMessage(message)
        end()
    }

    // ------------------------------------------------------------- microphone

    override fun onAudio(pcm: ByteArray) {
        synchronized(phraseAudio) { phraseAudio.write(pcm) }
        if (!phraseMode) live?.append(pcm)
    }

    override fun onPhraseEnd() {
        val audio = synchronized(phraseAudio) { phraseAudio.toByteArray().also { phraseAudio.reset() } }
        main.post { phraseEnded(audio) }
    }

    override fun onLevel(level: Float) {
        main.post { if (isActive) { this.level = level; ui() } }
    }

    override fun onLongSilence() {
        main.post { stop() }
    }

    override fun onMicError(message: String) {
        main.post { if (isActive) fail(message) }
    }

    override fun onMicStopped() {
        main.post { if (stopping) finishIfIdle() }
    }

    private fun phraseEnded(audio: ByteArray) {
        if (!isActive) return
        if (!phraseMode) {
            live?.commit()
            segmentAudio[segmentsEnded] = audio
            segmentsEnded++
            segmentAudio.keys.removeAll { it < segmentsEnded - 10 }
            if (phrases.values.none { !it.done }) expecting++
        } else if (audio.size >= OpenAi.SAMPLE_RATE * 2 / 4) {  // at least 250 ms
            transcribeRecording(audio)
        }
    }

    // ------------------------------------------------------- live transcription

    override fun onConnected() {
        connected = true
        ui()
    }

    override fun onCommitted(itemId: String) {
        phraseFor(itemId)
    }

    override fun onCommitIgnored() {
        segmentsSeen++  // that recording had no speech, so no words will come for it
        if (expecting > 0) expecting--
        if (stopping) finishIfIdle()
    }

    private val firstWordsLogged = HashSet<String>()

    override fun onDelta(itemId: String, delta: String) {
        val p = phraseFor(itemId) ?: return
        if (firstWordsLogged.add(itemId)) Log.i(TAG, "first words arrived (still talking: ${mic.speaking})")
        p.text.append(delta)
        // A whole command so far? Then a short pause is enough to carry it out.
        if (!p.done && p === phrases.values.lastOrNull()) mic.setQuickEnd(LocalCommands.quickEndFrames(p.shown))
        showComposing()
    }

    override fun onCompleted(itemId: String, transcript: String) {
        val p = phraseFor(itemId) ?: return
        finishPhrase(p, transcript)
    }

    /** Your own words never count as "another language". */
    private val myNames: Set<String> by lazy {
        (vocabulary + namePairs.flatMap { listOf(it.first, it.second) }).map { it.lowercase() }.toSet()
    }

    /** A phrase's words arrived: use them, unless they're not Hebrew or English. */
    private fun finishPhrase(p: Phrase, heard: String) {
        if (heard.isNotBlank() && Scripts.notHebrewOrEnglish(heard, host::isEnglishWord, myNames)) {
            askAgainInHebrewOrEnglish(p, heard)
            return
        }
        p.final = heard
        p.done = true
        pump()
    }

    /**
     * The speech model heard another language (Arabic, Russian...): send that phrase's recording again,
     * allowing only Hebrew and English. If that still fails, the other-language words are dropped.
     */
    private fun askAgainInHebrewOrEnglish(p: Phrase, heard: String) {
        val audio = p.audio ?: segmentAudio[p.segment]
        if (audio == null || p.retried || audio.size < OpenAi.SAMPLE_RATE * 2 / 4) {
            dropOtherLanguage(p, heard, couldAsk = false)
            return
        }
        p.retried = true
        p.final = ""  // don't show the wrong words meanwhile
        // The language you're most likely speaking first (Arabic letters usually mean it was Hebrew).
        val first = if (Scripts.looksSemitic(heard) || hebrewNow()) "he" else "en"
        val order = listOf(first, if (first == "he") "en" else "he").filter { it in languages }.ifEmpty { languages }
        Log.i(TAG, "heard another language, asking again: $order")
        val context = text.toString()
        val wav = Wav.encode(audio, OpenAi.SAMPLE_RATE)
        worker.execute {
            var result: String? = null
            var reached = false
            for (lang in order) {
                val r = runCatching {
                    OpenAi.transcribeFile(apiKey, wav, listOf(lang), context, vocabulary, namePairs, onlyLanguage = lang)
                }.getOrNull() ?: continue
                reached = true
                if (r.isNotBlank() && !Scripts.hasForeign(r) && !Scripts.hasAccents(r)) { result = r; break }
            }
            main.post {
                if (!isActive) return@post
                val ok = result
                if (ok == null) {
                    dropOtherLanguage(p, heard, couldAsk = reached)
                } else {
                    Log.i(TAG, "asked again: got Hebrew / English")
                    p.final = ok
                    p.done = true
                    pump()
                }
            }
        }
    }

    /** The words really weren't Hebrew or English: leave them out (and say why, once). */
    private fun dropOtherLanguage(p: Phrase, heard: String, couldAsk: Boolean) {
        Log.i(TAG, "dropped words in another language")
        p.final = when {
            !couldAsk && !Scripts.hasForeign(heard) && !Scripts.hasAccents(heard) -> heard  // couldn't check: keep them
            Scripts.hasAccents(heard) -> ""
            else -> Scripts.withoutForeign(heard)
        }
        p.done = true
        if (!toldOnlyHebrewEnglish && p.final != heard) {
            toldOnlyHebrewEnglish = true
            host.showMessage("Yboard types only Hebrew and English, so it skipped words in another language.")
        }
        pump()
    }

    override fun onFailed(itemId: String?, message: String) {
        if (itemId.isNullOrEmpty()) {
            Log.w(TAG, "live transcription error")
            return
        }
        val p = phraseFor(itemId) ?: return
        p.done = true  // keep whatever words arrived
        pump()
    }

    override fun onClosed(message: String?, code: Int) {
        if (!isActive) return
        live = null
        when {
            code == 401 || code == 403 -> fail(OpenAi.friendly(OpenAi.ApiError(code, message ?: "")))
            code == 429 -> fail(OpenAi.friendly(OpenAi.ApiError(code, message ?: "")))
            stopping -> { expecting = 0; phrases.values.forEach { it.done = true }; pump() }
            else -> {
                // Live words aren't available (or the connection dropped): carry on phrase by phrase.
                Log.w(TAG, "live transcription closed ($code), using phrase mode")
                phraseMode = true
                connected = true
                expecting = 0
                phrases.values.forEach { it.done = true }
                pump()
                ui()
            }
        }
    }

    private fun phraseFor(id: String): Phrase? {
        if (id.isEmpty() || id in finishedIds) return null
        return phrases.getOrPut(id) {
            if (expecting > 0) expecting--
            Phrase(id).also { if (!phraseMode) it.segment = segmentsSeen++ }
        }
    }

    // ------------------------------------------------------------ phrase mode

    private fun transcribeRecording(pcm: ByteArray) {
        val p = Phrase("local-${++localIds}")
        p.audio = pcm
        phrases[p.id] = p
        val context = text.toString()
        ui()
        worker.execute {
            val result = runCatching { OpenAi.transcribeFile(apiKey, Wav.encode(pcm, OpenAi.SAMPLE_RATE), languages, context, vocabulary, namePairs) }
            main.post {
                if (!isActive) return@post
                result.exceptionOrNull()?.let { e ->
                    if (e is OpenAi.ApiError && e.code in setOf(401, 403, 429)) { fail(OpenAi.friendly(e)); return@post }
                    host.showMessage(OpenAi.friendly(e))
                }
                finishPhrase(p, result.getOrNull().orEmpty())
            }
        }
    }

    // ------------------------------------------------------- applying phrases

    /** Applies finished phrases, oldest first. Waits while the AI is deciding about one, or while sending. */
    private fun pump() {
        if (!isActive) return
        while (thinking == null && sending == null) {
            val head = phrases.values.firstOrNull() ?: break
            if (!head.done) break
            phrases.remove(head.id)
            finishedIds.add(head.id)
            val spoken = head.shown
            if (spoken.isNotEmpty()) handlePhrase(spoken)
        }
        showComposing()
        if (stopping) finishIfIdle()
    }

    /** The latest voice-typed phrase that is still at the end of the text (what "delete that" removes). */
    private fun lastTyped(before: String): String? {
        while (typed.isNotEmpty()) {
            val p = typed.last()
            if (p.isNotBlank() && before.endsWith(p)) return p
            typed.removeAt(typed.size - 1)  // already removed by another command
        }
        return null
    }

    private fun handlePhrase(spoken: String) {
        val n = ++phraseCount
        val before = text.toString()
        // 1) Simple commands, instantly and without the internet.
        LocalCommands.match(spoken, before, lastTyped(before))?.let { m ->
            Log.i(TAG, "phrase $n: command ${m.kind}")
            if (m.kind == LocalCommands.Kind.DELETE_ALL) { deleteEverything(); return }
            if (m.kind == LocalCommands.Kind.SEND) { send(); return }
            if (m.kind == LocalCommands.Kind.UNDO && deletedAll != null) { bringBackEverything(); return }
            if (m.kind == LocalCommands.Kind.DELETE_PHRASE && typed.isNotEmpty() && m.edit.remove == typed.last()) {
                typed.removeAt(typed.size - 1)
            }
            apply(m.edit, remember = false)
            return
        }
        // "See you soon. Send." (said without a long pause): type the words, then send.
        LocalCommands.trailingSend(spoken)?.let { words ->
            Log.i(TAG, "phrase $n: words, then send")
            if (words.isNotBlank()) handlePhrase(words)
            if (thinking != null) sendAfterThinking = true else send()
            return
        }
        // 2) Spoken punctuation inside the sentence: "hi question mark" -> "hi?".
        val dictated = LocalCommands.spokenPunctuation(spoken)
        // 3) Anything else that sounds like editing goes to the AI.
        if (!smartCommands || !VoiceEdits.looksLikeCommand(spoken)) {
            Log.i(TAG, "phrase $n: dictation (${dictated.length} chars)")
            apply(VoiceEdit("", dictated), remember = true)
            return
        }
        Log.i(TAG, "phrase $n: asking AI")
        thinking = spoken
        val context = before.takeLast(500)
        val last = lastTyped(before).orEmpty()
        aiWorker.execute {
            val edit = try {
                OpenAi.command(apiKey, context, last, spoken, keyboardLanguage)
            } catch (e: Exception) {
                Log.w(TAG, "phrase $n: AI failed: ${e.javaClass.simpleName} ${e.message?.take(160)}")
                null
            }
            main.post {
                if (!isActive) return@post
                thinking = null
                if (edit == null) {
                    host.showMessage("The AI didn't answer, so the words were typed instead.")
                    apply(VoiceEdit("", dictated), remember = true)
                } else {
                    Log.i(TAG, "phrase $n: AI edit remove=${edit.remove.length} insert=${edit.insert.length}")
                    if (edit.remove.isNotEmpty() && typed.isNotEmpty() && edit.remove.endsWith(typed.last())) {
                        typed.removeAt(typed.size - 1)
                    }
                    apply(edit, remember = edit.insert.isNotBlank())
                }
                if (sendAfterThinking) {
                    sendAfterThinking = false
                    send()
                }
                pump()
            }
        }
    }

    /** Clears the whole text box (not just what's before the cursor), keeping a copy for "undo". */
    private fun deleteEverything() {
        val ic = host.inputConnection() ?: return
        val whole = try {
            ic.getExtractedText(ExtractedTextRequest(), 0)?.text?.toString()
        } catch (e: Exception) {
            null
        }
        ic.beginBatchEdit()
        if (composing.isNotEmpty()) {
            ic.setComposingText("", 1)
            composing = ""
        }
        ic.deleteSurroundingText(1_000_000, 1_000_000)  // apps limit this to the text there is
        ic.endBatchEdit()
        deletedAll = whole ?: text.toString()
        text.setLength(0)
        typed.clear()
    }

    /**
     * "Send": presses send, then re-reads the (now usually empty) text box.
     * Voice typing doesn't end until this is done, even if the mic was turned off right after "send".
     */
    private fun send() {
        if (sending != null) return  // "send. send." is one send
        val ic = host.inputConnection() ?: return
        if (composing.isNotEmpty()) {
            ic.setComposingText("", 1)
            composing = ""
        }
        typed.clear()
        deletedAll = null
        sending = Sending()
        ui()
        // Give the app a moment to receive the last words before tapping Send.
        main.postDelayed({ trySend() }, SEND_FIRST_DELAY)
    }

    /** The whole text box (before and after the cursor), or null if the app won't say. */
    private fun boxText(): String? {
        val ic = host.inputConnection() ?: return null
        val before = ic.getTextBeforeCursor(MAX_TEXT, 0) ?: return null
        return before.toString() + (ic.getTextAfterCursor(MAX_TEXT, 0) ?: "")
    }

    private fun trySend() {
        val s = sending ?: return
        if (!isActive) return
        val box = boxText()
        if (s.looks == 0 && box != null && box.isBlank()) {
            Log.i(TAG, "send: the box is empty, nothing to send")
            finishSend()
            return
        }
        s.looks++
        s.boxBefore = box
        s.beforeCursor = host.inputConnection()?.getTextBeforeCursor(MAX_TEXT, 0)?.toString()
        val result = host.sendMessage(lastTry = s.looks >= SEND_LOOKS)
        Log.i(TAG, "send: try ${s.looks}: $result")
        if (result == SendResult.NO_BUTTON) {
            // WhatsApp shows its Send button only once there's text; it may still be appearing.
            main.postDelayed({ trySend() }, SEND_RETRY_DELAY)
            return
        }
        s.result = result
        main.postDelayed({ checkSent() }, SEND_CHECK_DELAY)
    }

    /** The app clears the box after sending; check it did. */
    private fun checkSent() {
        val s = sending ?: return
        if (!isActive) return
        val box = boxText()
        if (s.result == SendResult.ACTION && !s.retapped && box != null && box.isNotBlank() && box == s.boxBefore) {
            // The box's own send action did nothing (ChatGPT and other AI chats): tap the Send button instead.
            s.retapped = true
            val tapped = host.tapSendButton()
            Log.i(TAG, "send: the box's send action did nothing, tapped the button: $tapped")
            if (tapped == SendResult.TAPPED) {
                s.result = SendResult.TAPPED
                main.postDelayed({ checkSent() }, SEND_CHECK_DELAY)
                return
            }
        }
        if (s.result == SendResult.TAPPED && !s.retapped && box != null && box.isNotBlank() && box == s.boxBefore) {
            // The box didn't change, so the tap didn't take (the app was busy). Tap once more.
            s.retapped = true
            val again = host.sendMessage(lastTry = false)
            Log.i(TAG, "send: the box didn't change, tapped again: $again")
            if (again == SendResult.TAPPED) {
                main.postDelayed({ checkSent() }, SEND_CHECK_DELAY)
                return
            }
        }
        if (s.result == SendResult.ENTER) {
            val ic = host.inputConnection()
            val now = ic?.getTextBeforeCursor(MAX_TEXT, 0)?.toString()
            val was = s.beforeCursor
            if (ic != null && now != null && was != null && now.endsWith("\n") && (was + "\n").endsWith(now)) {
                // Enter only made a new line. Undo the line.
                ic.deleteSurroundingText(1, 0)
                Log.i(TAG, "send: the app added a new line instead of sending")
                host.showMessage(
                    if (host.sendHelperOn()) "Couldn't find this app's Send button. Tap Send yourself this time."
                    else "To send in WhatsApp by voice: Yboard → Voice typing → turn on \"Tap Send for me\"."
                )
            }
        }
        finishSend()
    }

    private fun finishSend() {
        sending = null
        host.inputConnection()?.getTextBeforeCursor(MAX_TEXT, 0)?.let { now ->
            text.setLength(0)
            text.append(now)
        }
        ui()
        pump()
    }

    /** "Undo" right after "delete everything". */
    private fun bringBackEverything() {
        val ic = host.inputConnection() ?: return
        val saved = deletedAll ?: return
        deletedAll = null
        ic.beginBatchEdit()
        if (composing.isNotEmpty()) {
            ic.setComposingText("", 1)
            composing = ""
        }
        ic.commitText(saved, 1)
        ic.endBatchEdit()
        text.setLength(0)
        text.append(saved.takeLast(MAX_TEXT))
        Log.i(TAG, "brought back ${saved.length} chars")
    }

    /** Is the text being dictated Hebrew? From the words before the cursor, else the keyboard's language. */
    private fun hebrewNow(): Boolean =
        com.yboard.keyboard.text.NameSpelling.hebrewText(text.takeLast(80).toString()) ?: (keyboardLanguage == "he")

    private fun apply(edit0: VoiceEdit, remember: Boolean) {
        // Your names in the language you're speaking ("Yoav" in English, "יואב" in Hebrew).
        val edit = if (remember && namePairs.isNotEmpty()) {
            val fixed = com.yboard.keyboard.text.NameSpelling.fix(edit0.insert, namePairs, hebrewNow())
            if (fixed != edit0.insert) Log.i(TAG, "names: wrote your names in the language you're speaking")
            edit0.copy(insert = fixed)
        } else edit0
        val ic = host.inputConnection() ?: return
        deletedAll = null
        val before = text.toString()
        val n = VoiceEdits.removeCount(before, edit.remove)
        if (n < 0) Log.w(TAG, "edit: text to remove wasn't at the end, nothing removed")
        ic.beginBatchEdit()
        if (composing.isNotEmpty()) {
            ic.setComposingText("", 1)
            composing = ""
        }
        if (n > 0) {
            ic.deleteSurroundingText(n, 0)
            text.setLength(text.length - n)
        }
        val insert = VoiceEdits.forInsert(text.toString(), edit.insert)
        if (insert.isNotEmpty()) {
            ic.commitText(insert, 1)
            text.append(insert)
            if (text.length > MAX_TEXT * 2) text.delete(0, text.length - MAX_TEXT)
        }
        if (remember && insert.isNotBlank()) {
            host.onDictated(text.substring(0, text.length - insert.length), insert)
            typed.add(insert)
            while (typed.size > 30) typed.removeAt(0)
        }
        ic.endBatchEdit()
    }

    /**
     * Shows the dictated words heard so far underlined at the cursor. Words that sound like
     * a command are left out (they show in the bar above the keys instead).
     */
    private fun showComposing() {
        if (!isActive) return
        // While sending, keep the box as it is (new words would be sent too). They show right after.
        if (sending != null) { ui(); return }
        val ic = host.inputConnection() ?: return
        val shown = phrases.values.mapNotNull { p -> LocalCommands.preview(p.shown, smartCommands) }
            .filter { it.isNotEmpty() }
        val joined = shown.joinToString(" ")
        val preview = if (joined.isEmpty()) "" else VoiceEdits.forInsert(text.toString(), joined)
        if (preview != composing) {
            ic.setComposingText(preview, 1)
            composing = preview
        }
        ui()
    }

    /** A command being said or worked on, to show in the bar ("“delete that”"). */
    private fun heardCommand(): String? {
        thinking?.let { return it }
        return phrases.values.lastOrNull { p ->
            p.shown.isNotEmpty() && LocalCommands.preview(p.shown, smartCommands) == null
        }?.shown
    }

    private fun finishIfIdle() {
        if (!isActive) return
        if (phrases.isEmpty() && thinking == null && expecting == 0 && sending == null) end()
    }

    private fun ui() {
        if (!isActive) return
        val heard = heardCommand()
        val status = when {
            sending != null -> "Sending…"
            stopping -> "Finishing…"
            thinking != null -> "Working on “${thinking!!.take(40)}”…"
            heard != null -> "“${heard.take(48)}”"
            !connected -> "Connecting…"
            phraseMode && phrases.isNotEmpty() -> "Writing…"
            else -> "Listening… tap the mic to stop"
        }
        host.onVoiceUi(status, level, !stopping)
    }

    companion object {
        private const val TAG = "YboardVoice"
        private const val MAX_TEXT = 1000
        private const val SEND_FIRST_DELAY = 250L
        private const val SEND_RETRY_DELAY = 300L
        private const val SEND_CHECK_DELAY = 500L
        /** Looks for the Send button before giving up and pressing Enter (about 1 second). */
        private const val SEND_LOOKS = 4
    }
}
