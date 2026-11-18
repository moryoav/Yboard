package com.yboard.keyboard.voice

import android.os.SystemClock
import android.util.Log
import com.yboard.keyboard.settings.AiFeature
import com.yboard.keyboard.settings.AiModel
import com.yboard.keyboard.settings.Prefs
import com.yboard.keyboard.usage.TokenUsage
import com.yboard.keyboard.usage.UsageStore
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Talks to OpenAI with the user's own API key.
 * - Live words: Realtime transcription over a WebSocket ([LIVE_MODEL], see [LiveTranscriber]).
 * - Fallback: one recording per phrase to /v1/audio/transcriptions ([FILE_MODEL]).
 * - Commands: a small, fast text model turns "delete that" into a [VoiceEdit] (/v1/responses).
 */
object OpenAi {
    const val API = "https://api.openai.com/v1"
    const val LIVE_URL = "wss://api.openai.com/v1/realtime?intent=transcription"
    val LIVE_MODEL: String get() = Prefs.current.aiModels[AiFeature.LIVE].model
    val FILE_MODEL: String get() = Prefs.current.aiModels[AiFeature.FILE].model
    const val SAMPLE_RATE = 24000

    private const val TAG = "YboardVoice"

    private const val SPEECH_PROMPT =
        "Dictation typed on a phone keyboard. The speaker speaks only English and Hebrew: write only in English " +
            "or Hebrew, never in any other language or alphabet. Use normal punctuation. " +
            "The speaker may also say editing commands such as new line, delete that, scratch that, send, " +
            "question mark, comma, period; write those as the words."

    /** Command words the speech model should expect, so "new line" isn't heard as "no line". */
    val SPEECH_KEYWORDS = listOf(
        "new line", "new paragraph", "delete that", "scratch that", "delete that word", "question mark",
        "exclamation mark", "comma", "period", "full stop", "colon", "send", "send it",
        "שורה חדשה", "תמחק את זה", "סימן שאלה", "סימן קריאה", "פסיק", "נקודה", "שלח", "תשלח",
    )

    /** At most this many of your own words go with each request. */
    private const val MAX_VOCABULARY = 60

    /**
     * The speech prompt, plus your own words so names are spelled the way you spell them, and their
     * English / Hebrew spellings ([pairs]) so a name comes out in the language you're speaking.
     */
    fun speechPrompt(vocabulary: List<String>, pairs: List<Pair<String, String>> = emptyList()): String {
        val sb = StringBuilder(SPEECH_PROMPT)
        val words = vocabulary.take(MAX_VOCABULARY)
        if (words.isNotEmpty()) sb.append(" Names and words this person uses (spell them exactly like this): ${words.joinToString(", ")}.")
        val p = pairs.take(MAX_VOCABULARY)
        if (p.isNotEmpty()) {
            sb.append(" The same names in English and Hebrew: ${p.joinToString(", ") { "${it.first} = ${it.second}" }}.")
            sb.append(" In a Hebrew sentence write them in Hebrew letters; in an English sentence, in English letters.")
        }
        return sb.toString()
    }

    fun speechKeywords(vocabulary: List<String>, pairs: List<Pair<String, String>> = emptyList()): List<String> =
        (SPEECH_KEYWORDS + vocabulary.take(MAX_VOCABULARY) + pairs.take(MAX_VOCABULARY).flatMap { listOf(it.first, it.second) })
            .distinct()

    /** Session settings levels: 0 = everything; each rejected setting drops a level; never below languages. */
    const val LAST_SESSION_LEVEL = 3

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    /** For the command AI: give up after 20 s instead of hanging. */
    private val aiHttp: OkHttpClient by lazy { http.newBuilder().callTimeout(20, TimeUnit.SECONDS).build() }
    private val transcriptionHttp: OkHttpClient by lazy { http.newBuilder().callTimeout(30, TimeUnit.SECONDS).build() }

    /** Available IDs from this account. Choosing an ID never silently changes another feature. */
    fun models(key: String): List<String> {
        val req = Request.Builder().url("$API/models").header("Authorization", "Bearer $key").build()
        return http.newCall(req).execute().use { r ->
            val body = r.body?.string()
            if (!r.isSuccessful) throw ApiError(r.code, errorMessage(r.code, body))
            val arr = JSONObject(body ?: "{}").optJSONArray("data") ?: return@use emptyList()
            (0 until arr.length()).map { arr.getJSONObject(it).getString("id") }.sorted()
        }
    }

    class ApiError(val code: Int, message: String) : Exception(message)

    /** OpenAI's error text from a JSON error body ({"error": {"message": ...}}). */
    fun errorMessage(code: Int, body: String?): String {
        val msg = try {
            JSONObject(body ?: "").optJSONObject("error")?.optString("message")
        } catch (e: Exception) {
            null
        }
        return msg?.takeIf { it.isNotBlank() } ?: "HTTP $code"
    }

    /** A message a person understands, for errors that stop voice typing. */
    fun friendly(e: Throwable): String = when {
        e is ApiError && (e.code == 401 || e.code == 403) ->
            "OpenAI didn't accept your API key. Check it in Yboard → Voice typing."
        e is ApiError && e.code == 429 ->
            "OpenAI says your account is out of credit or busy. Check your OpenAI billing."
        e is ApiError -> "OpenAI: ${e.message}"
        else -> "Couldn't reach OpenAI. Check your internet connection."
    }

    // ------------------------------------------------------------------ key check

    /** Returns null when the key works, otherwise a short reason. Network call: run off the main thread. */
    fun checkKey(key: String): String? = try {
        val req = Request.Builder().url("$API/models").header("Authorization", "Bearer $key").build()
        http.newCall(req).execute().use { r ->
            when {
                r.isSuccessful -> null
                r.code == 401 || r.code == 403 -> "OpenAI didn't accept this key."
                r.code == 429 -> "The key works, but OpenAI says the account is out of credit or busy."
                else -> "OpenAI answered: " + errorMessage(r.code, r.body?.string())
            }
        }
    } catch (e: Exception) {
        "Couldn't reach OpenAI. Check your internet connection."
    }

    // ------------------------------------------------------------ live session

    /** The Realtime transcription session setup. [full] = with languages, low delay and a prompt. */
    fun liveSessionJson(languages: List<String>, full: Boolean, vocabulary: List<String> = emptyList()): String =
        liveSessionJson(languages, if (full) 0 else LAST_SESSION_LEVEL, vocabulary)

    /**
     * The live session settings. [level] 0 sends everything; if OpenAI rejects one, the next level
     * leaves out the least important (keywords, then the prompt), keeping "Hebrew and English only"
     * as long as possible. [LAST_SESSION_LEVEL] = just the model.
     */
    fun liveSessionJson(
        languages: List<String>, level: Int, vocabulary: List<String> = emptyList(), pairs: List<Pair<String, String>> = emptyList(),
        model: String = LIVE_MODEL,
        delay: String = "low",
    ): String {
        val transcription = JSONObject().put("model", model)
        if (level <= 2) transcription.put("languages", JSONArray(languages))
        if (level <= 1) transcription.put("prompt", speechPrompt(vocabulary, pairs))
        if (level == 0) {
            transcription.put("delay", delay)
            transcription.put("keywords", JSONArray(speechKeywords(vocabulary, pairs)))
        }
        val input = JSONObject()
            .put("format", JSONObject().put("type", "audio/pcm").put("rate", SAMPLE_RATE))
            .put("transcription", transcription)
            .put("turn_detection", JSONObject.NULL)
        return JSONObject()
            .put("type", "session.update")
            .put("session", JSONObject().put("type", "transcription").put("audio", JSONObject().put("input", input)))
            .toString()
    }

    // ------------------------------------------------------- phrase-by-phrase

    /** Transcribes one recorded phrase (WAV). Network call: run off the main thread. */
    fun transcribeFile(
        key: String, wav: ByteArray, languages: List<String>, context: String, vocabulary: List<String> = emptyList(),
        pairs: List<Pair<String, String>> = emptyList(),
        /** "he" / "en": the speaker is speaking only this language (asking again after a wrong-language answer). */
        onlyLanguage: String? = null,
    ): String {
        val model = FILE_MODEL
        val only = when (onlyLanguage) {
            "he" -> "The speaker is speaking Hebrew. Write Hebrew. "
            "en" -> "The speaker is speaking English. Write English. "
            else -> ""
        }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "speech.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "json")
            .addFormDataPart("prompt", (only + speechPrompt(vocabulary, pairs) + " " + context.takeLast(200)).trim())
        languages.forEach { body.addFormDataPart("languages[]", it) }
        speechKeywords(vocabulary, pairs).forEach { body.addFormDataPart("keywords[]", it) }
        val req = Request.Builder().url("$API/audio/transcriptions")
            .header("Authorization", "Bearer $key")
            .post(body.build())
            .build()
        val t0 = SystemClock.elapsedRealtime()
        transcriptionHttp.newCall(req).execute().use { r ->
            val text = r.body?.string()
            val ms = SystemClock.elapsedRealtime() - t0
            if (!r.isSuccessful) {
                val msg = errorMessage(r.code, text)
                Log.w(TAG, "transcribe -> HTTP ${r.code} in ${ms}ms: ${msg.take(200)}")
                throw ApiError(r.code, msg)
            }
            Log.i(TAG, "transcribe -> OK in ${ms}ms")
            val result = JSONObject(text ?: "{}")
            val seconds = (wav.size - 44).coerceAtLeast(0) / (SAMPLE_RATE * 2.0)
            UsageStore.record(AiFeature.FILE, model, TokenUsage.parse(result.optJSONObject("usage"), seconds))
            return result.optString("text").trim()
        }
    }

    // ------------------------------------------------------------- commands

    private val COMMAND_INSTRUCTIONS = """
        You are the editing brain of a voice keyboard. The user is dictating into a text field on a phone,
        in English or Hebrew. You get JSON with: text_before_cursor (what is already typed), last_inserted
        (what voice typing added for the previous phrase), spoken (the new phrase, as heard), and
        keyboard_language. Decide what the new phrase means and return an edit: "remove" is text to delete
        from the very end of text_before_cursor (copy it exactly, or "" for nothing), and "insert" is the
        text to type after that.

        - Normal dictation: remove "", insert the spoken words exactly as said. Do not rephrase, translate,
          answer questions or add anything.
        - Spoken punctuation and layout become symbols: "question mark" -> "?", "comma" -> ",",
          "period"/"full stop" -> ".", "exclamation mark" -> "!", "colon" -> ":", "new line" -> a line
          break, "new paragraph" -> two line breaks. Hebrew too: "סימן שאלה", "פסיק", "נקודה", "סימן קריאה",
          "שורה חדשה". Example: spoken "hi question mark" -> insert "hi?". Keep the words when the person
          clearly means them as words (for example "what is a question mark").
        - Commands that edit what is already typed are carried out, never typed:
          "delete that", "scratch that", "undo that", "מחק את זה", "תמחק", "בטל" -> remove last_inserted.
          "delete that word", "delete the last word", "תמחק את המילה האחרונה" -> remove the last word.
          "delete the last sentence" -> remove the last sentence. "replace X with Y" / "change X to Y" ->
          remove everything from X to the end and insert the corrected text. "capitalize that" etc. work
          the same way. The user doesn't have to be precise: work out what they most likely mean.
        - A phrase can mix both, like "delete that and write see you soon" -> remove last_inserted,
          insert "see you soon".
        - "remove" must be an exact copy of the end of text_before_cursor. Never remove more than needed.
        - If unsure whether something is a command, treat it as dictation.
    """.trimIndent()

    private val EDIT_SCHEMA: JSONObject
        get() = JSONObject()
            .put("type", "object")
            .put(
                "properties", JSONObject()
                    .put("remove", JSONObject().put("type", "string")
                        .put("description", "Exact text to delete from the end of text_before_cursor, or empty."))
                    .put("insert", JSONObject().put("type", "string")
                        .put("description", "Text to type at the cursor after removing."))
            )
            .put("required", JSONArray().put("remove").put("insert"))
            .put("additionalProperties", false)

    /** The /v1/responses request for one spoken phrase. */
    fun commandRequestJson(
        model: String, before: String, lastInserted: String, spoken: String, language: String, effort: String?,
    ): String {
        val user = JSONObject()
            .put("text_before_cursor", before)
            .put("last_inserted", lastInserted)
            .put("spoken", spoken)
            .put("keyboard_language", if (language == "he") "Hebrew" else "English")
        val body = JSONObject()
            .put("model", model)
            .put(
                "input", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", COMMAND_INSTRUCTIONS))
                    .put(JSONObject().put("role", "user").put("content", user.toString()))
            )
            .put(
                "text", JSONObject().put(
                    "format", JSONObject()
                        .put("type", "json_schema")
                        .put("name", "keyboard_edit")
                        .put("strict", true)
                        .put("schema", EDIT_SCHEMA)
                )
            )
            .put("max_output_tokens", 600)
            .put("store", false)
        if (effort != null) body.put("reasoning", JSONObject().put("effort", effort))
        return body.toString()
    }

    /** The model's text from a /v1/responses reply. */
    fun outputText(response: JSONObject): String? {
        response.optString("output_text").takeIf { it.isNotEmpty() }?.let { return it }
        val out = response.optJSONArray("output") ?: return null
        for (i in 0 until out.length()) {
            val item = out.optJSONObject(i) ?: continue
            if (item.optString("type") != "message") continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val c = content.optJSONObject(j) ?: continue
                if (c.optString("type") == "output_text") return c.optString("text")
            }
        }
        return null
    }

    fun parseEdit(text: String): VoiceEdit {
        val o = JSONObject(text)
        return VoiceEdit(o.optString("remove"), o.optString("insert"))
    }

    /**
     * Asks the AI what a spoken phrase means. Network call: run off the main thread.
     * Uses the selected model and reasoning level without silently changing the selection.
     */
    fun command(key: String, before: String, lastInserted: String, spoken: String, language: String): VoiceEdit {
        val choice = Prefs.current.aiModels[AiFeature.COMMAND]
        val out = textFeature(AiFeature.COMMAND, key, choice,
            commandRequestJson(choice.model, before, lastInserted, spoken, language, choice.reasoning))
        return parseEdit(out)
    }

    // ------------------------------------------------------- emoji sprinkle ✨

    private val SPRINKLE_INSTRUCTIONS = """
        You add fitting emojis to a message someone is about to send from their phone, in English or Hebrew.
        Keep all of their text exactly as it is: the same words, spelling, punctuation, spaces and line breaks,
        in the same order. Only insert emojis. Put each emoji right after the words it fits, or at the end of a
        sentence. Use about one or two emojis per sentence and no more than 6 in total. Match the mood.
        Never add words, and never answer or comment on the message.
        Examples: "happy birthday dana" -> "happy birthday dana 🎉🎂🥳". "נתראה מחר בים" -> "נתראה מחר בים 🏖️".
    """.trimIndent()

    private val TEXT_SCHEMA: JSONObject
        get() = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject().put("text", JSONObject().put("type", "string")))
            .put("required", JSONArray().put("text"))
            .put("additionalProperties", false)

    fun sprinkleRequestJson(model: String, text: String, effort: String?): String {
        val body = JSONObject()
            .put("model", model)
            .put("input", JSONArray()
                .put(JSONObject().put("role", "system").put("content", SPRINKLE_INSTRUCTIONS))
                .put(JSONObject().put("role", "user").put("content", JSONObject().put("text", text).toString())))
            .put("text", JSONObject().put("format", JSONObject()
                .put("type", "json_schema").put("name", "with_emojis").put("strict", true).put("schema", TEXT_SCHEMA)))
            .put("max_output_tokens", 3000)
            .put("store", false)
        if (effort != null) body.put("reasoning", JSONObject().put("effort", effort))
        return body.toString()
    }

    /** [text] with emojis added. Network call: run off the main thread. */
    fun sprinkle(key: String, text: String): String {
        val choice = Prefs.current.aiModels[AiFeature.SPRINKLE]
        val out = textFeature(AiFeature.SPRINKLE, key, choice, sprinkleRequestJson(choice.model, text, choice.reasoning))
        return JSONObject(out).optString("text")
    }

    // ------------------------------------------------ names in both languages

    private val SPELLING_INSTRUCTIONS = """
        A person keeps a list of their own words: names, nicknames, brands and slang. For each word you get,
        give how it is written in the other language: English letters -> Hebrew letters, Hebrew letters ->
        English letters, the way Israelis usually write it (Yoav -> יואב, יואב -> Yoav, WhatsApp -> וואטסאפ).
        Names are written by sound, not translated. If one of the person's other words ("their_words") is the
        same name in the other language, use exactly that spelling.
    """.trimIndent()

    private val SPELLING_SCHEMA: JSONObject
        get() = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject().put("pairs", JSONObject()
                .put("type", "array")
                .put("items", JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject()
                        .put("word", JSONObject().put("type", "string"))
                        .put("other", JSONObject().put("type", "string")))
                    .put("required", JSONArray().put("word").put("other"))
                    .put("additionalProperties", false))))
            .put("required", JSONArray().put("pairs"))
            .put("additionalProperties", false)

    fun spellingRequestJson(model: String, words: List<String>, theirWords: List<String>, effort: String?): String {
        val user = JSONObject().put("words", JSONArray(words)).put("their_words", JSONArray(theirWords))
        val body = JSONObject()
            .put("model", model)
            .put("input", JSONArray()
                .put(JSONObject().put("role", "system").put("content", SPELLING_INSTRUCTIONS))
                .put(JSONObject().put("role", "user").put("content", user.toString())))
            .put("text", JSONObject().put("format", JSONObject()
                .put("type", "json_schema").put("name", "spellings").put("strict", true).put("schema", SPELLING_SCHEMA)))
            .put("max_output_tokens", 2000)
            .put("store", false)
        if (effort != null) body.put("reasoning", JSONObject().put("effort", effort))
        return body.toString()
    }

    /** Each of [words] written in the other language (word -> other spelling). Network call. */
    fun otherSpellings(key: String, words: List<String>, theirWords: List<String>): Map<String, String> {
        val choice = Prefs.current.aiModels[AiFeature.SPELLING]
        val out = textFeature(AiFeature.SPELLING, key, choice, spellingRequestJson(choice.model, words, theirWords, choice.reasoning))
        val arr = JSONObject(out).optJSONArray("pairs") ?: return emptyMap()
        val asked = words.toSet()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val w = o.optString("word").trim()
            val other = o.optString("other").trim()
            if (w in asked && other.isNotEmpty() && other != w && other.length <= 40) w to other else null
        }.toMap()
    }

    private fun textFeature(
        feature: AiFeature, key: String, choice: AiModel, json: String,
        client: OkHttpClient = aiHttp, onCall: (Call) -> Unit = {},
    ): String {
        val start = SystemClock.elapsedRealtime()
        try {
            val answer = responsesCall(feature, key, choice.model, choice.reasoning, json, client, onCall)
            AiStatus.report(feature, "${choice.model}: replied in ${SystemClock.elapsedRealtime() - start} ms")
            return answer
        } catch (e: Exception) {
            if (e is ApiError) AiStatus.report(feature, "${choice.model}: ${friendly(e)}")
            else AiStatus.report(feature, "${choice.model}: request interrupted or timed out; local predictions stay available")
            throw e
        }
    }

    /** One /v1/responses call; returns the model's text. */
    private fun responsesCall(
        feature: AiFeature, key: String, model: String, effort: String?, json: String,
        client: OkHttpClient = aiHttp, onCall: (Call) -> Unit = {},
    ): String {
        val req = Request.Builder().url("$API/responses")
            .header("Authorization", "Bearer $key")
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()
        val t0 = SystemClock.elapsedRealtime()
        val call = client.newCall(req)
        onCall(call)
        var recorded = false
        var possiblyBilled = !call.isCanceled()
        try { call.execute().use { r ->
            val text = r.body?.string()
            val ms = SystemClock.elapsedRealtime() - t0
            if (!r.isSuccessful) {
                possiblyBilled = false
                val msg = errorMessage(r.code, text)
                Log.w(TAG, "ai $model effort=$effort -> HTTP ${r.code} in ${ms}ms: ${msg.take(200)}")
                throw ApiError(r.code, msg)
            }
            val root = JSONObject(text ?: "{}")
            UsageStore.record(feature, root.optString("model").ifBlank { model }, TokenUsage.parse(root.optJSONObject("usage")),
                root.optString("service_tier", "default"))
            recorded = true
            Log.i(TAG, "ai $model effort=$effort -> OK in ${ms}ms, status=${root.optString("status")}")
            return outputText(root) ?: throw ApiError(0, "empty answer")
        } } finally {
            if (possiblyBilled && !recorded) UsageStore.record(feature, model, TokenUsage(reported = false))
        }
    }
}
