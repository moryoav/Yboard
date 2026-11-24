package com.yboard.keyboard.voice

import android.util.Log
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import java.io.ByteArrayOutputStream

/**
 * AI voice typing, while the mic is on.
 *
 * Words show up in the text field as you say them (underlined, like Gboard). Words that sound
 * like a command ("delete that", "question mark", "תמחק את זה") are NOT shown in the field; they
 * appear in the bar above the keys instead. Once a command is recognized, a pause can carry it out:
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
    private val options: VoiceOptions = VoiceOptions(),
    initialSelection: Int = -1,
    private val services: VoiceServices = VoiceServices(),
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
        /** Whether [word] is in its bundled dictionary, or null while that dictionary is unavailable. */
        fun isDictionaryWord(word: String): Boolean? = null
    }

    private val main = services.dispatcher
    private val worker = services.worker
    private val aiWorker = services.aiWorker
    private val mic = services.microphone(this, options)
    @Volatile private var live: VoiceConnection? = null
    private var connected = false
    /** Live transcription isn't available: send each phrase as a recording instead. */
    @Volatile private var phraseMode = false
    private var micStopped = false
    private var cursor = initialSelection
    private val selection = VoiceSelection(initialSelection)

    private class Phrase(val id: String) {
        var remoteId: String? = null
        val text = StringBuilder()
        var final: String? = null
        var done = false
        var literal = false
        /** The phrase's recording (phrase-by-phrase mode), to ask again if it came back in another language. */
        var audio: ByteArray? = null
        var retried = false
        var transcribing = false
        var watchdog = 0
        val shown: String get() = (final ?: text.toString()).trim()
    }

    private var toldLanguageSkipped = false

    private val phrases = LinkedHashMap<String, Phrase>()
    private val finishedIds = HashSet<String>()
    private var localIds = 0
    private var commitIds = 0  // mic thread only
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

    @Volatile var isActive = false
        private set
    private var stopping = false
    private var level = 0f

    fun start() {
        isActive = true
        host.inputConnection()?.let { ic ->
            ic.finishComposingText()
            text.append(ic.getTextBeforeCursor(MAX_TEXT, 0) ?: "")
            if (cursor < 0) {
                ic.getExtractedText(ExtractedTextRequest(), 0)?.let { extracted ->
                    cursor = extracted.startOffset + minOf(extracted.selectionStart, extracted.selectionEnd)
                    selection.reset(cursor)
                }
            }
        }
        live = services.connection(apiKey, languages, this, vocabulary, namePairs, options).also { it.connect() }
        mic.start()
        ui()
    }

    /** Mic button tapped again: stop listening, finish the last phrase, then end. */
    fun stop() {
        if (!isActive || stopping) return
        stopping = true
        mic.stop()
        ui()
        // Each pending phrase has its own watchdog and bounded fallback request. Never discard it on a fixed stop timer.
    }

    /** End right away (a key was typed, or the keyboard closed). Dictated words shown so far stay. */
    fun end() {
        if (!isActive) return
        isActive = false
        mic.stop()
        live?.close()
        live = null
        main.cancelAll()
        worker.shutdownNow()
        aiWorker.shutdownNow()
        if (composing.isNotEmpty()) host.inputConnection()?.finishComposingText()
        composing = ""
        phrases.clear()
        commitPhrases.clear()
        synchronized(phraseAudio) { phraseAudio.reset() }
        host.onVoiceUi(null, 0f, false)
    }

    /** A cursor move changes what commands refer to. Finish the preview and stop safely. */
    fun onSelectionChanged(start: Int, end: Int) {
        if (!isActive || sending != null) return
        if (cursor < 0 && start >= 0 && start == end) {
            cursor = (start - composing.length).coerceAtLeast(0)
            selection.reset(start)
            return
        }
        if (selection.moved(start, end)) {
            host.showMessage("Voice typing stopped because the cursor moved. Tap the mic to continue here.")
            end()
        }
    }

    private fun expectCursor(position: Int) { selection.expect(position) }

    private fun fail(message: String) {
        host.showMessage(message)
        end()
    }

    // ------------------------------------------------------------- microphone

    override fun onAudio(pcm: ByteArray) {
        if (!isActive) return
        synchronized(phraseAudio) { phraseAudio.write(pcm) }
        if (!phraseMode) live?.append(pcm)
    }

    override fun onPhraseEnd() {
        if (!isActive) return
        val audio = synchronized(phraseAudio) { phraseAudio.toByteArray().also { phraseAudio.reset() } }
        val id = "yboard-turn-${++commitIds}"
        val connection = if (!phraseMode) live else null
        main.post { phraseEnded(id, audio) }
        // Commit on the recording thread so new-phrase audio cannot overtake this boundary.
        connection?.commit(id)
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
        main.post { if (isActive) { micStopped = true; if (stopping) finishIfIdle() } }
    }

    private fun phraseEnded(id: String, audio: ByteArray) {
        if (!isActive) return
        val p = phrases.values.firstOrNull { it.audio == null && !it.done } ?: newPhrase()
        p.audio = audio
        commitPhrases[id] = p
        if (phraseMode) pump() else {
            val version = ++p.watchdog
            main.postDelayed({
                if (isActive && !p.done && !p.transcribing && version == p.watchdog) {
                    useRecordings("Live transcription is taking too long. Retrying the unfinished words.")
                }
            }, 12000)
        }
    }

    private val commitPhrases = HashMap<String, Phrase>()
    private fun newPhrase(): Phrase = Phrase("local-${++localIds}").also { phrases[it.id] = it }

    // ------------------------------------------------------- live transcription

    override fun onConnected() {
        if (!isActive || phraseMode) return
        connected = true
        ui()
    }

    override fun onCommitted(itemId: String) {
        phraseFor(itemId)
    }

    override fun onCommitIgnored(turnId: String?) {
        if (!isActive || phraseMode) return
        val p = if (turnId != null) commitPhrases[turnId] else phrases.values.firstOrNull { it.audio != null && !it.done }
        p ?: return
        finishPhrase(p, p.shown)
    }

    private val firstWordsLogged = HashSet<String>()

    override fun onDelta(itemId: String, delta: String) {
        val p = phraseFor(itemId) ?: return
        if (firstWordsLogged.add(itemId)) Log.i(TAG, "first words arrived (still talking: ${mic.speaking})")
        p.text.append(delta)
        // A delta from a turn we already committed must not affect the next mic phrase.
        if (smartCommands && !p.done && p.audio == null && p === phrases.values.lastOrNull()) {
            val shown = visiblePhrase(p)
            val command = LocalCommands.trailingEdit(shown)?.second ?: shown
            val quick = LocalCommands.quickEndFrames(command)
            val frames = when {
                quick > 0 -> quick
                LocalCommands.trailingSend(shown) != null || VoiceEdits.looksLikeCommand(command) -> options.commandPauseFrames
                else -> 0
            }
            mic.setCommandPause(frames)
        }
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

    private fun outsideVoiceLanguages(heard: String): Boolean =
        Scripts.hasDisallowed(heard, languages) || Scripts.looksLikeOtherLatinLanguage(heard, host::isEnglishWord, myNames)

    private fun visiblePhrase(p: Phrase): String =
        p.shown.takeUnless { Scripts.hasDisallowed(it, languages) }.orEmpty()

    private fun finishPhrase(p: Phrase, heard: String) {
        if (!isActive || p.done || p.transcribing) return
        if (heard.isNotBlank() && outsideVoiceLanguages(heard)) {
            retryInConfiguredLanguages(p, heard)
            return
        }
        completePhrase(p, heard)
    }

    private fun completePhrase(p: Phrase, heard: String) {
        p.final = heard
        p.done = true
        pump()
    }

    /**
     * The speech model heard another language (Arabic, Russian...): send that phrase's recording again,
     * using the configured languages. If that fails, keep only the allowed part of the original.
     */
    private fun retryInConfiguredLanguages(p: Phrase, heard: String) {
        val audio = p.audio
        if (audio == null || p.retried || audio.size < OpenAi.SAMPLE_RATE * 2 / 4) {
            dropOtherLanguage(p, heard)
            return
        }
        p.retried = true
        p.transcribing = true
        p.final = ""  // don't show the wrong words meanwhile
        // The language you're most likely speaking first (Arabic letters usually mean it was Hebrew).
        val first = if (Scripts.looksSemitic(heard) || hebrewNow()) "he" else "en"
        val order = languages.sortedBy { it != first }
        Log.i(TAG, "heard another language, asking again: $order")
        val context = text.toString()
        val wav = Wav.encode(audio, OpenAi.SAMPLE_RATE)
        showComposing()
        fun attempt(index: Int) {
            val lang = order.getOrNull(index)
            if (lang == null) {
                p.transcribing = false
                dropOtherLanguage(p, heard)
                return
            }
            worker.execute {
                val result = runCatching {
                    services.transcribe(apiKey, wav, listOf(lang), context, vocabulary, namePairs, onlyLanguage = lang)
                }.getOrNull()
                main.post {
                    if (!isActive) return@post
                    if (result.isNullOrBlank() || outsideVoiceLanguages(result)) {
                        attempt(index + 1)
                    } else {
                        Log.i(TAG, "asked again: got text in a configured language")
                        p.transcribing = false
                        completePhrase(p, result)
                    }
                }
            }
        }
        attempt(0)
    }

    private fun dropOtherLanguage(p: Phrase, heard: String) {
        val safe = Scripts.withoutDisallowed(heard, languages).takeUnless(::outsideVoiceLanguages).orEmpty()
        // Removing words could leave behind something that sounds like an editing or send command.
        p.literal = true
        notifyLanguageSkipped()
        completePhrase(p, safe)
    }

    private fun notifyLanguageSkipped() {
        if (toldLanguageSkipped) return
        toldLanguageSkipped = true
        host.showMessage("Yboard skipped words outside your voice typing languages.")
    }

    override fun onFailed(itemId: String?, message: String) {
        if (!isActive || phraseMode) return
        Log.w(TAG, "live transcription error, recovering unfinished audio")
        useRecordings("Live transcription failed. Retrying with recorded phrases.")
    }

    override fun onClosed(message: String?, code: Int) {
        if (!isActive || phraseMode) return
        when {
            code == 401 || code == 403 -> fail(OpenAi.friendly(OpenAi.ApiError(code, message ?: "")))
            code == 429 -> fail(OpenAi.friendly(OpenAi.ApiError(code, message ?: "")))
            else -> useRecordings("The live connection dropped. Retrying with recorded phrases.")
        }
    }

    private fun useRecordings(message: String) {
        if (!isActive || phraseMode) return
        phraseMode = true // Ignore callbacks from the old connection before closing it.
        live?.close()
        live = null
        connected = true
        host.showMessage(message)
        pump()
    }

    private fun phraseFor(id: String): Phrase? {
        if (!isActive || phraseMode || id.isEmpty() || id in finishedIds) return null
        phrases.values.firstOrNull { it.remoteId == id }?.let { return if (it.transcribing) null else it }
        val p = phrases.values.firstOrNull { it.remoteId == null && !it.done } ?: newPhrase()
        p.remoteId = id
        return p
    }

    // ------------------------------------------------------------ phrase mode

    private fun transcribeRecording(p: Phrase) {
        if (!isActive || p.done || p.transcribing) return
        val pcm = p.audio ?: return
        if (pcm.isEmpty()) { finishPhrase(p, p.shown); return }
        p.transcribing = true
        p.watchdog++
        val context = text.toString()
        ui()
        worker.execute {
            val result = runCatching { services.transcribe(apiKey, Wav.encode(pcm, OpenAi.SAMPLE_RATE), languages, context, vocabulary, namePairs) }
            main.post {
                if (!isActive) return@post
                p.transcribing = false
                result.exceptionOrNull()?.let { e ->
                    if (e is OpenAi.ApiError && e.code in setOf(401, 403, 429)) { fail(OpenAi.friendly(e)); return@post }
                    host.showMessage("Couldn't finish a recorded phrase. Words already received were kept. ${OpenAi.friendly(e)}")
                }
                val heard = result.getOrNull()
                if (heard != null && heard.isBlank() && p.shown.isNotBlank()) {
                    host.showMessage("The recording returned no words. The live words were kept.")
                }
                finishPhrase(p, heard?.takeIf { it.isNotBlank() } ?: p.shown)
            }
        }
    }

    // ------------------------------------------------------- applying phrases

    /** Applies finished phrases, oldest first. Waits while the AI is deciding about one, or while sending. */
    private fun pump() {
        if (!isActive) return
        while (thinking == null && sending == null) {
            val head = phrases.values.firstOrNull() ?: break
            if (!head.done) {
                // The next recording gets the words just committed by the previous one as context.
                if (phraseMode && head.audio != null) transcribeRecording(head)
                break
            }
            phrases.remove(head.id)
            head.remoteId?.let { finishedIds.add(it) }
            commitPhrases.entries.removeAll { it.value === head }
            val spoken = head.shown
            if (spoken.isNotEmpty()) {
                if (head.literal) insertDictation(VoiceEdits.attachPunctuation(spoken)) else handlePhrase(spoken)
            }
        }
        showComposing()
        if (stopping) finishIfIdle()
    }

    /** The latest voice-typed phrase that is still at the end of the text (what "delete that" removes). */
    private fun lastTyped(before: String): String? {
        while (typed.isNotEmpty()) {
            val p = typed.last()
            if (p.isNotBlank() && before.endsWith(p)) return LocalCommands.latestDictatedSentence(p)
            typed.removeAt(typed.size - 1)  // already removed by another command
        }
        return null
    }

    private fun handlePhrase(heard: String) {
        val spoken = VoiceEdits.attachPunctuation(heard)
        val n = ++phraseCount
        val before = text.toString()
        if (!smartCommands) {
            insertDictation(spoken)
            return
        }
        LocalCommands.trailingEdit(spoken)?.let { (words, command) ->
            insertDictation(LocalCommands.spokenPunctuation(words))
            handlePhrase(command)
            return
        }
        // 1) Simple commands, instantly and without the internet.
        LocalCommands.match(spoken, before, lastTyped(before))?.let { m ->
            Log.i(TAG, "phrase $n: command ${m.kind}")
            if (m.kind == LocalCommands.Kind.DELETE_ALL) { deleteEverything(); return }
            if (m.kind == LocalCommands.Kind.SEND) { send(); return }
            if (m.kind == LocalCommands.Kind.UNDO && deletedAll != null) { bringBackEverything(); return }
            if (m.kind == LocalCommands.Kind.CANCEL_PHRASE) {
                val kept = spoken.dropLast(LocalCommands.latestDictatedSentence(spoken).length)
                if (kept.isNotBlank()) insertDictation(LocalCommands.spokenPunctuation(kept))
                return
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
        if (!VoiceEdits.looksLikeCommand(spoken)) {
            Log.i(TAG, "phrase $n: dictation (${dictated.length} chars)")
            insertDictation(dictated)
            return
        }
        Log.i(TAG, "phrase $n: asking AI")
        thinking = spoken
        val context = before.takeLast(500)
        val last = lastTyped(before).orEmpty()
        aiWorker.execute {
            val edit = try {
                services.command(apiKey, context, last, spoken, keyboardLanguage)
            } catch (e: Exception) {
                Log.w(TAG, "phrase $n: AI failed: ${e.javaClass.simpleName} ${e.message?.take(160)}")
                null
            }
            main.post {
                if (!isActive) return@post
                thinking = null
                if (edit == null) {
                    host.showMessage("The AI didn't answer, so the words were typed instead.")
                    insertDictation(dictated)
                } else {
                    Log.i(TAG, "phrase $n: AI edit remove=${edit.remove.length} insert=${edit.insert.length}")
                    if (edit.remove.isEmpty() && edit.insert == spoken) insertDictation(dictated)
                    else apply(edit, remember = edit.insert.isNotBlank())
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
            expectCursor(cursor)
            ic.setComposingText("", 1)
            composing = ""
        }
        expectCursor(0)
        ic.deleteSurroundingText(1_000_000, 1_000_000)  // apps limit this to the text there is
        cursor = 0
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
            expectCursor(cursor)
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
        host.inputConnection()?.getExtractedText(ExtractedTextRequest(), 0)?.let { extracted ->
            cursor = extracted.startOffset + minOf(extracted.selectionStart, extracted.selectionEnd)
            selection.reset(cursor)
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
            expectCursor(cursor)
            ic.setComposingText("", 1)
            composing = ""
        }
        if (cursor >= 0) cursor += saved.length
        expectCursor(cursor)
        ic.commitText(saved, 1)
        ic.endBatchEdit()
        text.setLength(0)
        text.append(saved.takeLast(MAX_TEXT))
        Log.i(TAG, "brought back ${saved.length} chars")
    }

    /** Is the text being dictated Hebrew? From the words before the cursor, else the keyboard's language. */
    private fun hebrewNow(): Boolean =
        languages.singleOrNull()?.let { it == "he" }
            ?: com.yboard.keyboard.text.NameSpelling.hebrewText(text.takeLast(80).toString())
            ?: (keyboardLanguage == "he")

    private fun insertDictation(spoken: String) {
        val words = VoiceEdits.continuationCase(text.toString(), DictatedTimes.format(spoken), myNames)
        apply(VoiceEdit("", words), remember = true)
    }

    private fun apply(edit0: VoiceEdit, remember: Boolean) {
        // Your names in the language you're speaking ("Yoav" in English, "יואב" in Hebrew).
        val edit = if (remember && (namePairs.isNotEmpty() || vocabulary.isNotEmpty())) {
            val fixed = com.yboard.keyboard.text.NameSpelling.fix(
                edit0.insert, namePairs, hebrewNow(), vocabulary, host::isDictionaryWord,
            )
            if (fixed == edit0.insert || Scripts.hasDisallowed(fixed, languages)) edit0 else edit0.copy(insert = fixed)
        } else edit0
        if (Scripts.hasDisallowed(edit.insert, languages)) {
            notifyLanguageSkipped()
            return
        }
        val ic = host.inputConnection() ?: return
        deletedAll = null
        val before = text.toString()
        val n = VoiceEdits.removeCount(before, edit.remove)
        if (n < 0) Log.w(TAG, "edit: text to remove wasn't at the end, nothing removed")
        ic.beginBatchEdit()
        if (composing.isNotEmpty()) {
            expectCursor(cursor)
            ic.setComposingText("", 1)
            composing = ""
        }
        if (n > 0) {
            typed.lastOrNull()?.takeIf { before.endsWith(it) }?.let { latest ->
                typed.removeAt(typed.lastIndex)
                val kept = latest.dropLast(minOf(n, latest.length))
                if (kept.isNotBlank()) typed.add(kept)
            }
            if (cursor >= 0) cursor = (cursor - n).coerceAtLeast(0)
            expectCursor(cursor)
            ic.deleteSurroundingText(n, 0)
            text.setLength(text.length - n)
        }
        val prefix = text.toString()
        val insert = VoiceEdits.forInsert(prefix, edit.insert)
        if (insert.isNotEmpty()) {
            if (cursor >= 0) cursor += insert.length
            expectCursor(cursor)
            ic.commitText(insert, 1)
            text.append(insert)
            if (text.length > MAX_TEXT * 2) text.delete(0, text.length - MAX_TEXT)
        }
        if (remember && insert.isNotBlank()) {
            host.onDictated(prefix, insert)
            typed.add(insert.takeLast(MAX_TEXT))
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
        val before = text.toString()
        val preview = buildString {
            for (p in phrases.values) {
                val shown = LocalCommands.preview(VoiceEdits.attachPunctuation(visiblePhrase(p)), smartCommands && !p.literal) ?: continue
                val left = if (isEmpty()) before else toString()
                val words = VoiceEdits.continuationCase(left, DictatedTimes.format(shown), myNames)
                append(VoiceEdits.forInsert(left, words))
            }
        }
        if (preview != composing) {
            if (cursor >= 0) expectCursor(cursor + preview.length)
            ic.setComposingText(preview, 1)
            composing = preview
        }
        ui()
    }

    /** A command being said or worked on, to show in the bar ("“delete that”"). */
    private fun heardCommand(): String? {
        thinking?.let { return it }
        if (!smartCommands) return null
        val phrase = phrases.values.lastOrNull()?.takeUnless { it.literal } ?: return null
        val shown = visiblePhrase(phrase).takeIf { it.isNotBlank() } ?: return null
        return LocalCommands.trailingEdit(shown)?.second ?: when {
            LocalCommands.trailingSend(shown) != null -> "send"
            LocalCommands.preview(shown, smart = true) == null -> shown
            else -> null
        }
    }

    private fun finishIfIdle() {
        if (!isActive) return
        if (micStopped && phrases.isEmpty() && thinking == null && sending == null) end()
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
            phraseMode && phrases.isNotEmpty() -> "Transcribing recording…"
            else -> "Listening…"
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
