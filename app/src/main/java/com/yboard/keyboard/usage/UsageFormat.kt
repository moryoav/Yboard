package com.yboard.keyboard.usage

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

object UsageFormat {
    fun money(nanos: Long): String {
        if (nanos == 0L) return "$0.00"
        if (nanos in 1..999) return "<$0.000001"
        val places = if (nanos < 10_000_000) 6 else 4
        return "$" + BigDecimal.valueOf(nanos, 9).setScale(places, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }
    fun count(value: Long): String = NumberFormat.getIntegerInstance(Locale.getDefault()).format(value)
}
