package com.tradesim.app.feature.trade.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tradesim.app.core.designsystem.CsColors
import com.tradesim.app.core.domain.formatKlineTime
import com.tradesim.app.core.domain.price
import com.tradesim.app.core.model.Kline
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private const val MIN_VISIBLE = 5
private const val MAX_VISIBLE = 300

@Composable
fun KlineChart(
    klines: List<Kline>,
    interval: String,
    priceScale: Int,
    showMA5: Boolean,
    showMA10: Boolean,
    showVolume: Boolean,
    modifier: Modifier = Modifier,
    onCrosshairChange: (Kline?) -> Unit = {},
) {
    var visibleCount by remember { mutableIntStateOf(60) }
    var visibleStart by remember { mutableIntStateOf(0) }
    var crosshairIndex by remember { mutableStateOf<Int?>(null) }
    var followLatest by remember { mutableStateOf(true) }

    val leftPad = 8.dp
    val rightPad = 58.dp
    val topPad = 10.dp
    val bottomPad = if (showVolume) 54.dp else 24.dp

    LaunchedEffect(klines.size) {
        if (klines.isEmpty()) {
            visibleStart = 0
            visibleCount = 1
            return@LaunchedEffect
        }
        val maxCount = min(MAX_VISIBLE, klines.size).coerceAtLeast(1)
        val minCount = min(MIN_VISIBLE, maxCount)
        visibleCount = visibleCount.coerceIn(minCount, maxCount)
        if (followLatest) {
            visibleStart = max(0, klines.size - visibleCount)
        }
        visibleStart = visibleStart.coerceIn(0, max(0, klines.size - visibleCount))
    }

    LaunchedEffect(crosshairIndex, klines, visibleStart) {
        val selected = crosshairIndex?.let { index -> klines.getOrNull(visibleStart + index) }
        onCrosshairChange(selected)
    }

    LaunchedEffect(crosshairIndex) {
        if (crosshairIndex != null) {
            delay(2_500L)
            crosshairIndex = null
        }
    }

    val ma5 = remember(klines, showMA5) {
        if (showMA5) movingAverage(klines, 5) else emptyList()
    }
    val ma10 = remember(klines, showMA10) {
        if (showMA10) movingAverage(klines, 10) else emptyList()
    }
    val textMeasurer = rememberTextMeasurer()
    val axisStyle = TextStyle(color = CsColors.TextMuted, fontSize = 9.sp)
    val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))

    Canvas(
        modifier = modifier
            .pointerInput(klines.size, showVolume) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    if (klines.isEmpty()) return@detectTransformGestures
                    val left = leftPad.toPx()
                    val right = rightPad.toPx()
                    val chartWidth = (size.width - left - right).coerceAtLeast(1f)
                    val oldCount = visibleCount
                    val maxCount = min(MAX_VISIBLE, klines.size).coerceAtLeast(1)
                    val minCount = min(MIN_VISIBLE, maxCount)
                    val newCount = (oldCount / zoom).roundToInt().coerceIn(minCount, maxCount)
                    val step = chartWidth / oldCount

                    if (newCount != oldCount) {
                        val anchor = ((centroid.x - left) / step)
                            .roundToInt()
                            .coerceIn(0, oldCount - 1)
                        val anchorIndex = visibleStart + anchor
                        val newStart = (anchorIndex - anchor * (newCount.toFloat() / oldCount) - pan.x / step)
                            .roundToInt()
                        visibleStart = newStart.coerceIn(0, max(0, klines.size - newCount))
                        visibleCount = newCount
                    } else if (pan.x != 0f) {
                        visibleStart = (visibleStart - pan.x / step)
                            .roundToInt()
                            .coerceIn(0, max(0, klines.size - oldCount))
                    }
                    followLatest = visibleStart + visibleCount >= klines.size - 1
                }
            }
            .pointerInput(klines.size) {
                fun indexAt(x: Float): Int? {
                    if (klines.isEmpty()) return null
                    val left = leftPad.toPx()
                    val right = rightPad.toPx()
                    val chartWidth = (size.width - left - right).coerceAtLeast(1f)
                    val step = chartWidth / visibleCount.coerceAtLeast(1)
                    val index = ((x - left) / step).toInt()
                    return if (index in 0 until visibleCount) index else null
                }

                detectDragGesturesAfterLongPress(
                    onDragStart = { offset -> crosshairIndex = indexAt(offset.x) },
                    onDrag = { change, _ -> crosshairIndex = indexAt(change.position.x) },
                    onDragEnd = { },
                    onDragCancel = { crosshairIndex = null },
                )
            },
    ) {
        drawRect(color = CsColors.Surface)

        if (klines.isEmpty()) return@Canvas

        val left = leftPad.toPx()
        val right = rightPad.toPx()
        val top = topPad.toPx()
        val bottom = bottomPad.toPx()
        val chartWidth = size.width - left - right
        val chartHeight = size.height - top - bottom
        if (chartWidth <= 0f || chartHeight <= 0f) return@Canvas

        val end = min(visibleStart + visibleCount, klines.size)
        val start = visibleStart.coerceIn(0, end)
        val visible = klines.subList(start, end)
        if (visible.isEmpty()) return@Canvas

        var minPrice = visible.first().low
        var maxPrice = visible.first().high
        visible.forEach {
            if (it.low < minPrice) minPrice = it.low
            if (it.high > maxPrice) maxPrice = it.high
        }
        if (maxPrice <= minPrice) {
            val delta = maxPrice.multiply(BigDecimal("0.01")).max(BigDecimal("0.00000001"))
            minPrice = minPrice.subtract(delta)
            maxPrice = maxPrice.add(delta)
        }
        val rawRange = maxPrice.subtract(minPrice)
        val padding = rawRange.multiply(BigDecimal("0.08"))
        val chartMin = minPrice.subtract(padding)
        val chartMax = maxPrice.add(padding)
        val chartRange = chartMax.subtract(chartMin).toFloat().coerceAtLeast(0.0000001f)

        fun priceToY(price: BigDecimal): Float {
            val ratio = chartMax.subtract(price).toFloat() / chartRange
            return top + ratio * chartHeight
        }

        // 横向网格和价格轴
        for (i in 0..4) {
            val y = top + chartHeight / 4f * i
            drawLine(
                color = CsColors.Grid,
                start = Offset(left, y),
                end = Offset(left + chartWidth, y),
                strokeWidth = 1f,
            )
            val priceAtLine = chartMax.subtract(
                chartRange.toDouble()
                    .let { BigDecimal.valueOf(it) }
                    .multiply(BigDecimal(i))
                    .divide(BigDecimal(4), 8, RoundingMode.HALF_UP),
            )
            val label = priceAtLine.price(priceScale.coerceIn(2, 8))
            val layout = textMeasurer.measure(label, style = axisStyle)
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(
                    x = size.width - layout.size.width - 2.dp.toPx(),
                    y = y - layout.size.height / 2f,
                ),
            )
        }

        val step = chartWidth / visible.size
        val candleWidth = (step * 0.62f).coerceAtLeast(1f)

        // 成交量区
        if (showVolume) {
            val volumeTop = size.height - bottom + 4.dp.toPx()
            val volumeHeight = (bottom - 18.dp.toPx()).coerceAtLeast(8.dp.toPx())
            val maxVolume = visible.maxOf { it.volume }.toFloat().coerceAtLeast(0.000001f)
            visible.forEachIndexed { index, kline ->
                val x = left + (index + 0.5f) * step
                val ratio = kline.volume.toFloat() / maxVolume
                val barHeight = (ratio * volumeHeight).coerceAtLeast(1f)
                val color = if (kline.close >= kline.open) CsColors.Up else CsColors.Down
                drawRect(
                    color = color.copy(alpha = 0.32f),
                    topLeft = Offset(x - candleWidth / 2f, volumeTop + volumeHeight - barHeight),
                    size = Size(candleWidth, barHeight),
                )
            }
            drawLine(
                color = CsColors.Border,
                start = Offset(left, volumeTop),
                end = Offset(left + chartWidth, volumeTop),
                strokeWidth = 1f,
            )
        }

        // K 线
        visible.forEachIndexed { index, kline ->
            val x = left + (index + 0.5f) * step
            val openY = priceToY(kline.open)
            val closeY = priceToY(kline.close)
            val highY = priceToY(kline.high)
            val lowY = priceToY(kline.low)
            val color = if (kline.close >= kline.open) CsColors.Up else CsColors.Down
            drawLine(
                color = color,
                start = Offset(x, highY),
                end = Offset(x, lowY),
                strokeWidth = 1f,
            )
            val bodyTop = min(openY, closeY)
            val bodyHeight = max(1f, max(openY, closeY) - bodyTop)
            drawRect(
                color = color,
                topLeft = Offset(x - candleWidth / 2f, bodyTop),
                size = Size(candleWidth, bodyHeight),
            )
        }

        // 均线
        fun drawMa(data: List<BigDecimal?>, color: Color) {
            if (data.isEmpty()) return
            val path = Path()
            var started = false
            visible.forEachIndexed { index, _ ->
                val value = data.getOrNull(start + index) ?: return@forEachIndexed
                val x = left + (index + 0.5f) * step
                val y = priceToY(value)
                if (!started) {
                    path.moveTo(x, y)
                    started = true
                } else {
                    path.lineTo(x, y)
                }
            }
            if (started) {
                drawPath(path = path, color = color, style = Stroke(width = 1.5.dp.toPx()))
            }
        }
        if (showMA5) drawMa(ma5, Color(0xFFEAB308))
        if (showMA10) drawMa(ma10, Color(0xFF3B82F6))

        // 时间轴
        val labelEvery = max(1, visible.size / 5)
        val timeY = size.height - 4.dp.toPx() - 10.dp.toPx()
        visible.forEachIndexed { index, kline ->
            if (index % labelEvery == 0) {
                val x = left + (index + 0.5f) * step
                val label = formatKlineTime(kline.openTime, interval)
                val layout = textMeasurer.measure(label, style = axisStyle)
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(
                        x = x - layout.size.width / 2f,
                        y = timeY,
                    ),
                )
            }
        }

        // 十字光标
        val selectedIndex = crosshairIndex
        if (selectedIndex != null && selectedIndex in visible.indices) {
            val kline = visible[selectedIndex]
            val x = left + (selectedIndex + 0.5f) * step
            val y = priceToY(kline.close)
            drawLine(
                color = CsColors.TextSecondary.copy(alpha = 0.55f),
                start = Offset(x, top),
                end = Offset(x, top + chartHeight),
                strokeWidth = 1f,
                pathEffect = dash,
            )
            drawLine(
                color = CsColors.TextSecondary.copy(alpha = 0.55f),
                start = Offset(left, y),
                end = Offset(left + chartWidth, y),
                strokeWidth = 1f,
                pathEffect = dash,
            )
            val label = kline.close.price(priceScale.coerceIn(2, 8))
            val layout = textMeasurer.measure(label, style = axisStyle.copy(color = Color.White))
            val boxWidth = layout.size.width + 8.dp.toPx()
            val boxHeight = layout.size.height + 4.dp.toPx()
            drawRect(
                color = if (kline.close >= kline.open) CsColors.Up else CsColors.Down,
                topLeft = Offset(left + chartWidth, y - boxHeight / 2f),
                size = Size(boxWidth, boxHeight),
            )
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(
                    x = left + chartWidth + 4.dp.toPx(),
                    y = y - layout.size.height / 2f,
                ),
            )
        }
    }
}

private fun movingAverage(klines: List<Kline>, period: Int): List<BigDecimal?> {
    if (klines.isEmpty() || period <= 0) return emptyList()
    val result = ArrayList<BigDecimal?>(klines.size)
    var sum = BigDecimal.ZERO
    klines.forEachIndexed { index, kline ->
        sum = sum.add(kline.close)
        if (index >= period) {
            sum = sum.subtract(klines[index - period].close)
        }
        result.add(
            if (index >= period - 1) {
                sum.divide(BigDecimal(period), 8, RoundingMode.HALF_UP)
            } else {
                null
            },
        )
    }
    return result
}
