package com.yboard.keyboard.usage

import org.json.JSONObject

/** Accounts for one speech connection once, even when completed events are repeated or arrive late. */
class LiveUsageMeter(private val model: String, private val bytesPerSecond: Double = 48_000.0) {
    private val seen = HashSet<String>()
    private var bytes = 0L
    private var input = 0L
    private var output = 0L
    private var cached = 0L
    private var writes30 = 0L
    private var writes12 = 0L
    private var audio = 0L
    private var seconds = 0.0
    private var reported = 0
    private var durationReports = 0
    private var finished = false

    @Synchronized fun sent(count: Int) { if (!finished && count > 0) bytes += count }
    @Synchronized fun completed(id: String, usage: JSONObject?) {
        if (finished || !seen.add(id)) return
        val u = TokenUsage.parse(usage)
        input += u.input; output += u.output; cached += u.cached
        writes30 += u.write30m; writes12 += u.write12h; audio += u.audioInput; seconds += u.seconds
        if (u.reported) reported++
        if (usage?.has("seconds") == true) durationReports++
    }

    @Synchronized fun finish(): TokenUsage? {
        if (finished) return null
        finished = true
        if (bytes == 0L && seen.isEmpty()) return null
        val estimateDuration = UsagePricing.isDurationModel(model) && durationReports == 0
        return TokenUsage(input, output, cached, writes30, writes12, audio,
            if (estimateDuration) (bytes / bytesPerSecond).coerceAtMost(86400.0) else seconds,
            reported = seen.isNotEmpty() && reported == seen.size,
            estimatedSeconds = estimateDuration,
        )
    }
}
