package com.yboard.keyboard.voice

import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject

/** Reads Responses SSE, publishing only complete JSON strings, never partial words or escapes. */
internal object PredictionStream {
    private val arrayStart = Regex("^\\s*\\{\\s*\"next\"\\s*:\\s*\\[")

    fun completeOptions(text: String, lastWord: String?): List<String> {
        val start = arrayStart.find(text)?.range?.last?.plus(1) ?: return emptyList()
        var index = start
        val options = ArrayList<String>()
        while (index < text.length && options.size < 3) {
            while (index < text.length && text[index].isWhitespace()) index++
            if (index >= text.length || text[index] != '"') break
            val begin = index++
            var escaped = false
            var closed = false
            while (index < text.length) {
                val char = text[index++]
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') { closed = true; break }
            }
            if (!closed) break
            val decoded = runCatching { JSONArray("[${text.substring(begin, index)}]").getString(0) }.getOrNull() ?: break
            options.add(decoded)
            while (index < text.length && text[index].isWhitespace()) index++
            if (index >= text.length || text[index] != ',') break
            index++
        }
        return clean(options, lastWord)
    }

    fun clean(options: List<String>, lastWord: String?): List<String> = options.map { it.trim().trim('"', '\'').replace(Regex("\\s+"), " ") }
        .filter { it.isNotEmpty() && it.length <= 40 && it.split(' ').size <= 3 && it.any(Char::isLetter) }
        .filter { it.all { char -> char.isLetter() || Character.getType(char) == Character.NON_SPACING_MARK.toInt() || char in " '’׳״-" } }
        .filterNot { lastWord != null && it.substringBefore(' ').equals(lastWord, true) }
        .distinctBy { it.substringBefore(' ').lowercase() }.take(3)

    /** Returns the terminal response, including usage even for an incomplete/failed response. */
    fun read(source: BufferedSource, lastWord: String?, onOptions: (List<String>) -> Unit): JSONObject {
        val text = StringBuilder()
        val data = StringBuilder()
        var last = emptyList<String>()
        var terminal: JSONObject? = null
        var bytes = 0
        fun event() {
            if (data.isEmpty()) return
            val raw = data.toString()
            data.setLength(0)
            if (raw == "[DONE]") return
            val value = JSONObject(raw)
            when (value.optString("type")) {
                "response.output_text.delta" -> {
                    text.append(value.optString("delta"))
                    check(text.length <= 8192) { "Prediction output is too long" }
                    val ready = completeOptions(text.toString(), lastWord)
                    if (ready.isNotEmpty() && ready != last) { last = ready; onOptions(ready) }
                }
                "response.completed", "response.incomplete", "response.failed" -> terminal = value.getJSONObject("response")
                "error" -> throw OpenAi.ApiError(0, value.optString("message", "Prediction stream failed"))
            }
        }
        while (!source.exhausted()) {
            val line = source.readUtf8LineStrict(65_536)
            bytes += line.length
            check(bytes <= 262_144) { "Prediction stream is too long" }
            if (line.isEmpty()) event()
            else if (line.startsWith("data:")) {
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.substring(5).trimStart(' '))
            }
            terminal?.let { return it }
        }
        event()
        return terminal ?: throw java.io.IOException("Prediction stream ended before completion")
    }
}
