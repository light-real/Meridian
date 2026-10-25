package com.cryptosim.app.core.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val TIME_FORMATTER = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.getDefault())
private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.getDefault())
private val FULL_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

fun BigDecimal.money(scale: Int = 2): String =
    setScale(scale, RoundingMode.HALF_UP).toPlainString()

fun BigDecimal.price(scale: Int): String =
    setScale(scale, RoundingMode.HALF_UP).toPlainString()

fun BigDecimal.quantity(scale: Int): String =
    setScale(scale, RoundingMode.DOWN).stripTrailingZeros().toPlainString().ifEmpty { "0" }

fun BigDecimal.signed(scale: Int = 2): String {
    val value = setScale(scale, RoundingMode.HALF_UP)
    return if (value.signum() >= 0) "+${value.toPlainString()}" else value.toPlainString()
}

fun BigDecimal.percent(scale: Int = 2): String =
    setScale(scale, RoundingMode.HALF_UP).toPlainString() + "%"

fun BigDecimal.compactVolume(): String {
    val abs = abs()
    return when {
        abs >= BigDecimal("1000000000") -> divide(BigDecimal("100000000"), 1, RoundingMode.DOWN).toPlainString() + "亿"
        abs >= BigDecimal("10000000") -> divide(BigDecimal("10000"), 1, RoundingMode.DOWN).toPlainString() + "万"
        else -> setScale(0, RoundingMode.DOWN).toPlainString()
    }
}

fun formatTime(millis: Long, pattern: String = "MM-dd HH:mm"): String {
    val formatter = when (pattern) {
        "yyyy-MM-dd" -> DATE_FORMATTER
        "yyyy-MM-dd HH:mm:ss" -> FULL_TIME_FORMATTER
        else -> TIME_FORMATTER
    }
    return Instant.ofEpochMilli(millis)
        .atZone(ZoneId.systemDefault())
        .format(formatter)
}

fun formatDate(millis: Long): String = formatTime(millis, "yyyy-MM-dd")

fun formatFullTime(millis: Long): String = formatTime(millis, "yyyy-MM-dd HH:mm:ss")

fun formatKlineTime(millis: Long, interval: String): String {
    val pattern = when (interval) {
        "1M" -> "yyyy-MM"
        "1w", "1d", "3d" -> "MM-dd"
        "4h", "6h", "8h", "12h" -> "MM-dd HH:00"
        else -> "MM-dd HH:mm"
    }
    val formatter = DateTimeFormatter.ofPattern(pattern, Locale.getDefault())
    return Instant.ofEpochMilli(millis)
        .atZone(ZoneId.systemDefault())
        .format(formatter)
}
