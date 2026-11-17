package com.yboard.keyboard

import com.yboard.keyboard.usage.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class UsageCostTest {
    @Test fun cachedInputIsNotChargedTwiceAndReasoningIsAlreadyInOutput() {
        val u = TokenUsage.parse(JSONObject("""{"input_tokens":1000,"input_tokens_details":{"cached_tokens":800},"output_tokens":100,"output_tokens_details":{"reasoning_tokens":60}}"""))
        assertEquals(200, u.ordinaryInput)
        assertEquals(100, u.output)
        val cost = UsagePricing.estimate("gpt-6-luna", u)
        assertEquals(20_000, cost.input)
        assertEquals(8_000, cost.cached)
        assertEquals(50_000, cost.output)
        assertEquals(78_000, cost.total)
        assertFalse(cost.incomplete)
    }

    @Test fun pricesBothCacheWriteLifetimesWithoutAddingOrdinaryInputAgain() {
        val u = TokenUsage.parse(JSONObject("""{"input_tokens":12000,"input_tokens_details":{"cached_tokens":4000,"cache_write_tokens":7000,"cache_write_tokens_details":{"ttl_30m_tokens":3000,"ttl_12h_tokens":4000}},"output_tokens":100}"""))
        assertEquals(1000, u.ordinaryInput)
        assertEquals(7000, u.writes)
        assertEquals(15_450_000, UsagePricing.estimate("gpt-6.1-terra", u).total)
        val defaultTtl = TokenUsage.parse(JSONObject("""{"input_tokens":1000,"input_tokens_details":{"cache_write_tokens":1000},"output_tokens":0}"""))
        assertEquals(1_250_000, UsagePricing.estimate("gpt-6.1-terra", defaultTtl).total)
    }

    @Test fun missingMalformedAndUnknownUsageIsNeverPresentedAsFullyPriced() {
        assertFalse(TokenUsage.parse(null).reported)
        assertFalse(TokenUsage.parse(JSONObject("{}")).reported)
        assertFalse(TokenUsage.parse(JSONObject("""{"input_tokens":10,"input_tokens_details":{"cached_tokens":11}}""")).reported)
        assertFalse(TokenUsage.parse(JSONObject("""{"input_tokens":-5}""")).reported)
        assertTrue(UsagePricing.estimate("new-unpriced-model", TokenUsage(input = 10, output = 3)).incomplete)
        assertTrue(UsagePricing.estimate("gpt-6-luna", TokenUsage(reported = false)).incomplete)
        assertTrue(UsagePricing.estimate("gpt-6-luna", TokenUsage(input = 10), "unknown-tier").incomplete)
    }

    @Test fun responseModelSnapshotsAndActualServiceTierUseTheCorrectRates() {
        val u = TokenUsage(input = 1000, output = 20)
        val standard = UsagePricing.estimate("gpt-6.1-terra", u)
        assertEquals(standard, UsagePricing.estimate("gpt-6.1-terra-2026-10-26", u))
        assertEquals(standard.total * 2, UsagePricing.estimate("gpt-6.1-terra", u, "priority").total)
        assertEquals(standard.total / 2, UsagePricing.estimate("gpt-6.1-terra", u, "flex").total)
        val long = TokenUsage(input = 300_000, output = 100)
        assertEquals(600_750_000, UsagePricing.estimate("gpt-6.1-terra", long).total)
    }

    @Test fun speechUsesItsPublishedMinuteRateAndKeepsReportedTokenCounts() {
        val reported = TokenUsage.parse(JSONObject("""{"type":"duration","seconds":120}"""))
        assertEquals(34_000_000, UsagePricing.estimate("gpt-live-transcribe", reported).total)
        assertEquals(9_000_000, UsagePricing.estimate("gpt-transcribe", reported).total)
        assertFalse(reported.estimatedSeconds)
        val estimated = TokenUsage.parse(null, fallbackSeconds = 60.0)
        assertTrue(estimated.estimatedSeconds)
        assertEquals(4_500_000, UsagePricing.estimate("gpt-transcribe", estimated).total)
        assertFalse(UsagePricing.estimate("gpt-transcribe", estimated).incomplete)
        val audio = TokenUsage.parse(JSONObject("""{"type":"tokens","input_tokens":13,"input_token_details":{"audio_tokens":13,"text_tokens":0},"output_tokens":9}"""))
        assertEquals(13, audio.input)
        assertEquals(13, audio.audioInput)
    }

    @Test fun liveSpeechCountsReportedDurationOnceAndIgnoresLateOrDuplicateEvents() {
        val meter = LiveUsageMeter("gpt-live-transcribe")
        meter.sent(48_000 * 3)
        val event = JSONObject("""{"type":"duration","seconds":2}""")
        meter.completed("item1:0", event)
        meter.completed("item1:0", event)
        val result = meter.finish()!!
        assertEquals(2.0, result.seconds, 0.0)
        assertFalse(result.estimatedSeconds)
        meter.completed("late:0", event)
        assertNull(meter.finish())
        val fallback = LiveUsageMeter("gpt-live-transcribe")
        fallback.sent(96_000)
        assertEquals(2.0, fallback.finish()!!.seconds, 0.0)
        assertNull(LiveUsageMeter("gpt-live-transcribe").finish())
    }

    @Test fun dayWeekAndMonthUseLocalCalendarBoundariesIncludingDaylightSaving() {
        val now = Instant.parse("2026-11-17T10:30:00Z")
        val zone = ZoneId.of("Asia/Jerusalem")
        assertEquals(Instant.parse("2026-11-16T22:00:00Z").toEpochMilli(), UsagePeriod.TODAY.start(now, zone))
        assertEquals(Instant.parse("2026-11-10T22:00:00Z").toEpochMilli(), UsagePeriod.WEEK.start(now, zone))
        assertEquals(Instant.parse("2026-10-18T21:00:00Z").toEpochMilli(), UsagePeriod.MONTH.start(now, zone))
    }

    @Test fun tinyKeyboardCostsRemainVisible() {
        assertEquals("$0.00", UsageFormat.money(0))
        assertEquals("<$0.000001", UsageFormat.money(500))
        assertEquals("$0.000078", UsageFormat.money(78_000))
        assertEquals("$0.034", UsageFormat.money(34_000_000))
    }
}
