package com.yboard.keyboard.usage

import kotlin.math.roundToLong

/** Standard OpenAI API USD prices, checked 2026-11-17. Not ChatGPT credits. */
object UsagePricing {
    const val CHECKED = "2026-11-17"
    const val SOURCE = "https://developers.openai.com/api/docs/pricing"
    private data class Rates(val input: Double, val cached: Double?, val output: Double, val cacheWrites: Boolean = false, val longContext: Boolean = false)
    private val text = mapOf(
        "gpt-6-luna" to Rates(.10, .01, .50, true, true),
        "gpt-6.1-terra" to Rates(1.0, .05, 5.0, true, true),
        "gpt-6.1-sol" to Rates(2.0, .10, 10.0, true, true),
        "gpt-6-sol" to Rates(2.0, .20, 10.0, true, true),
        "gpt-6-astra" to Rates(10.0, 1.0, 50.0, true, true),
        "gpt-5.6-sol" to Rates(4.0, .40, 20.0, true, true),
        "gpt-5.6-terra" to Rates(2.0, .20, 12.0, true, true),
        "gpt-5.6-luna" to Rates(.20, .02, 1.20, true, true),
        "gpt-5.5" to Rates(5.0, .50, 30.0, longContext = true),
        "gpt-5.4" to Rates(2.50, .25, 15.0, longContext = true),
        "gpt-5.4-mini" to Rates(.75, .075, 4.50),
        "gpt-5.4-nano" to Rates(.20, .02, 1.25),
        "gpt-5.2" to Rates(1.75, .175, 14.0),
        "gpt-5.1" to Rates(1.25, .125, 10.0),
        "gpt-5" to Rates(1.25, .125, 10.0),
        "gpt-5-mini" to Rates(.25, .025, 2.0),
        "gpt-5-nano" to Rates(.05, .005, .40),
        "gpt-4.1" to Rates(2.0, .50, 8.0),
        "gpt-4.1-mini" to Rates(.40, .10, 1.60),
        "gpt-4o" to Rates(2.50, 1.25, 10.0),
        "gpt-4o-mini" to Rates(.15, .075, .60),
    )
    private val duration = mapOf("gpt-live-transcribe" to .017, "gpt-realtime-whisper" to .017,
        "gpt-transcribe" to .0045, "whisper-1" to .006)

    fun canonical(model: String) = model.removeSuffix(Regex("-20[0-9]{2}-[0-9]{2}-[0-9]{2}$").find(model)?.value.orEmpty())
    fun isDurationModel(model: String) = canonical(model) in duration

    fun estimate(model: String, usage: TokenUsage, serviceTier: String = "default"): UsageCharge {
        val name = canonical(model)
        duration[name]?.let { rate ->
            return UsageCharge(speech = (usage.seconds * rate / 60 * 1_000_000_000).roundToLong(),
                incomplete = (!usage.reported && !usage.estimatedSeconds) || (usage.seconds <= 0 && usage.input + usage.output > 0))
        }
        val rates = text[name] ?: return UsageCharge(incomplete = true)
        if (usage.audioInput > 0) return UsageCharge(incomplete = true)
        val factor = when (serviceTier) {
            "default", "standard", "auto", "" -> 1.0
            "priority", "fast" -> if (rates.cacheWrites || name in listOf("gpt-5.4", "gpt-5.4-mini")) 2.0 else return UsageCharge(incomplete = true)
            "flex", "batch" -> .5
            "ultrafast" -> if (name == "gpt-5.6-sol") 6.0 else return UsageCharge(incomplete = true)
            else -> return UsageCharge(incomplete = true)
        }
        val longContext = rates.longContext && usage.input > 272_000
        val inputFactor = if (longContext) 2.0 else 1.0
        fun price(tokens: Long, rate: Double?) = if (rate == null) 0L else (tokens * rate * 1000 * factor).roundToLong()
        return UsageCharge(
            input = price(usage.ordinaryInput, rates.input * inputFactor),
            cached = price(usage.cached, rates.cached?.times(inputFactor)),
            writes = if (rates.cacheWrites) price(usage.write30m, rates.input * inputFactor * 1.25) +
                price(usage.write12h, rates.input * inputFactor * 2.5) else 0,
            output = price(usage.output, rates.output * if (longContext) 1.5 else 1.0),
            incomplete = !usage.reported || usage.audioInput > 0 || (usage.cached > 0 && rates.cached == null) || (usage.writes > 0 && !rates.cacheWrites),
        )
    }
}
