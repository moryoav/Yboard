package com.yboard.keyboard.settings

import org.json.JSONObject
import java.time.LocalTime

/** A daily interval in the phone's local time. The end is exclusive; equal times mean all day. */
data class QuietHours(
    val enabled: Boolean = false,
    val startMinute: Int = 22 * 60,
    val endMinute: Int = 7 * 60,
    val muteSound: Boolean = true,
    val muteVibration: Boolean = true,
) {
    fun isActiveAt(time: LocalTime): Boolean {
        if (!enabled || startMinute !in 0..1439 || endMinute !in 0..1439) return false
        val minute = time.hour * 60 + time.minute
        return when {
            startMinute == endMinute -> true
            startMinute < endMinute -> minute >= startMinute && minute < endMinute
            else -> minute >= startMinute || minute < endMinute
        }
    }

    fun suppressesSoundAt(time: LocalTime) = muteSound && isActiveAt(time)
    fun suppressesVibrationAt(time: LocalTime) = muteVibration && isActiveAt(time)

    fun toJson() = JSONObject().apply {
        put("enabled", enabled)
        put("startMinute", startMinute); put("endMinute", endMinute)
        put("muteSound", muteSound); put("muteVibration", muteVibration)
    }

    companion object {
        fun fromJson(json: JSONObject?): QuietHours {
            val defaults = QuietHours()
            if (json == null) return defaults
            return QuietHours(
                enabled = json.optBoolean("enabled", defaults.enabled),
                startMinute = json.optInt("startMinute", defaults.startMinute).takeIf { it in 0..1439 } ?: defaults.startMinute,
                endMinute = json.optInt("endMinute", defaults.endMinute).takeIf { it in 0..1439 } ?: defaults.endMinute,
                muteSound = json.optBoolean("muteSound", defaults.muteSound),
                muteVibration = json.optBoolean("muteVibration", defaults.muteVibration),
            )
        }
    }
}
