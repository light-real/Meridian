package com.meridian.app.core.domain

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

/** 盘口数量用的紧凑写法：1.2K / 3.45M / 0.0123。 */
fun BigDecimal.orderBookQty(): String {
    val abs = abs()
    return when {
        abs >= BigDecimal("1000000") ->
            divide(BigDecimal("1000000"), 2, RoundingMode.DOWN).stripTrailingZeros().toPlainString() + "M"
        abs >= BigDecimal("1000") ->
            divide(BigDecimal("1000"), 2, RoundingMode.DOWN).stripTrailingZeros().toPlainString() + "K"
        abs >= BigDecimal("1") -> setScale(2, RoundingMode.DOWN).stripTrailingZeros().toPlainString()
        else -> setScale(4, RoundingMode.DOWN).stripTrailingZeros().toPlainString()
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
