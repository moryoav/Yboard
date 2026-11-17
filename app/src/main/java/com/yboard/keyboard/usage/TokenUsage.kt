package com.yboard.keyboard.usage

import org.json.JSONObject

/** API counters only. No prompt, output text, recording, or credential is retained. */
data class TokenUsage(
    val input: Long = 0,
    val output: Long = 0,
    val cached: Long = 0,
    val write30m: Long = 0,
    val write12h: Long = 0,
    val audioInput: Long = 0,
    val seconds: Double = 0.0,
    val reported: Boolean = true,
    val estimatedSeconds: Boolean = false,
) {
    val writes get() = write30m + write12h
    val ordinaryInput get() = input - cached - writes
    init {
        require(listOf(input, output, cached, write30m, write12h, audioInput).all { it in 0..10_000_000_000L })
        require(cached + writes <= input && audioInput <= input)
        require(seconds.isFinite() && seconds in 0.0..86400.0)
    }

    companion object {
        fun parse(usage: JSONObject?, fallbackSeconds: Double = 0.0): TokenUsage {
            if (usage == null) return TokenUsage(seconds = fallbackSeconds, reported = false, estimatedSeconds = fallbackSeconds > 0)
            return runCatching {
                val details = usage.optJSONObject("input_tokens_details") ?: usage.optJSONObject("input_token_details")
                    ?: usage.optJSONObject("prompt_tokens_details")
                val writes = details?.optLong("cache_write_tokens") ?: 0
                val writeDetails = details?.optJSONObject("cache_write_tokens_details")
                val longWrites = writeDetails?.optLong("ttl_12h_tokens") ?: 0
                val shortWrites = writeDetails?.optLong("ttl_30m_tokens") ?: (writes - longWrites)
                require(writes == shortWrites + longWrites)
                val hasTokens = usage.has("input_tokens") || usage.has("prompt_tokens") || usage.has("output_tokens")
                val hasDuration = usage.has("seconds") && usage.optDouble("seconds", -1.0) >= 0
                TokenUsage(
                    input = usage.optLong("input_tokens", usage.optLong("prompt_tokens")),
                    output = usage.optLong("output_tokens", usage.optLong("completion_tokens")),
                    cached = details?.optLong("cached_tokens") ?: 0,
                    write30m = shortWrites, write12h = longWrites,
                    audioInput = details?.optLong("audio_tokens") ?: 0,
                    seconds = if (hasDuration) usage.getDouble("seconds") else fallbackSeconds,
                    reported = hasTokens || hasDuration,
                    estimatedSeconds = !hasDuration && fallbackSeconds > 0,
                )
            }.getOrElse { TokenUsage(seconds = fallbackSeconds.coerceIn(0.0, 86400.0), reported = false, estimatedSeconds = fallbackSeconds > 0) }
        }
    }
}

data class UsageCharge(
    val input: Long = 0, val output: Long = 0, val cached: Long = 0,
    val writes: Long = 0, val speech: Long = 0, val incomplete: Boolean = false,
) {
    /** USD in billionths, so tiny keyboard requests do not round down to zero cents. */
    val total get() = input + output + cached + writes + speech
}
