package com.tradesim.app.feature.trade

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tradesim.app.core.designsystem.CsColors
import com.tradesim.app.core.designsystem.CsNumericTextStyle
import com.tradesim.app.core.domain.TradingEngine
import com.tradesim.app.core.domain.money
import com.tradesim.app.core.model.MarginMode
import java.math.BigDecimal
import kotlin.math.roundToInt

/** 调整杠杆：步进 + 滑杆，1-150 倍。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeverageSheet(
    current: Int,
    cash: BigDecimal,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var sliderValue by remember { mutableFloatStateOf(current.toFloat().coerceIn(1f, 150f)) }
    val leverage = sliderValue.roundToInt().coerceIn(1, 150)
    val maxNotional = remember(leverage, cash) {
        TradingEngine.maxNotional(cash, leverage)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = CsColors.Surface,
        dragHandle = { BottomSheetDefaults.DragHandle(color = CsColors.BorderStrong) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp),
        ) {
            Text(
                text = "调整杠杆",
                color = CsColors.TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(CsColors.SurfaceVariant),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { sliderValue = (leverage - 1).coerceAtLeast(1).toFloat() },
                    enabled = leverage > 1,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Remove,
                        contentDescription = "降低杠杆",
                        tint = if (leverage > 1) CsColors.TextSecondary else CsColors.TextFaint,
                    )
                }
                Text(
                    text = "${leverage}x",
                    modifier = Modifier.weight(1f),
                    color = CsColors.TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                IconButton(
                    onClick = { sliderValue = (leverage + 1).coerceAtMost(150).toFloat() },
                    enabled = leverage < 150,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = "提高杠杆",
                        tint = if (leverage < 150) CsColors.TextSecondary else CsColors.TextFaint,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = it },
                valueRange = 1f..150f,
                colors = SliderDefaults.colors(
                    thumbColor = CsColors.Primary,
                    activeTrackColor = CsColors.Primary,
                    inactiveTrackColor = CsColors.SurfaceVariant,
                    activeTickColor = CsColors.OnPrimary,
                    inactiveTickColor = CsColors.TextFaint,
                ),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                listOf(1, 30, 60, 90, 120, 150).forEach { tick ->
                    Text(
                        text = "${tick}x",
                        color = if (tick == leverage) CsColors.TextPrimary else CsColors.TextFaint,
                        fontSize = 10.sp,
                        fontWeight = if (tick == leverage) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            BulletLine(
                text = "当前杠杆倍数最高可开：${maxNotional.money(0)} USDT",
                color = CsColors.TextSecondary,
            )
            BulletLine(
                text = "杠杆调整会立即影响接下来的开仓",
                color = CsColors.TextSecondary,
            )
            BulletLine(
                text = "选择超过 10x 杠杆交易会增加强行平仓风险，请注意控制仓位。",
                color = CsColors.TextSecondary,
            )

            Spacer(modifier = Modifier.height(22.dp))

            Button(
                onClick = { onConfirm(leverage) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CsColors.Primary,
                    contentColor = CsColors.OnPrimary,
                ),
            ) {
                Text(text = "确认", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** 保证金模式：两张说明卡片，点选即刻生效。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarginModeSheet(
    current: MarginMode,
    onDismiss: () -> Unit,
    onSelect: (MarginMode) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = CsColors.Surface,
        dragHandle = { BottomSheetDefaults.DragHandle(color = CsColors.BorderStrong) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp),
        ) {
            Text(
                text = "保证金模式",
                color = CsColors.TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )

            Spacer(modifier = Modifier.height(18.dp))

            MarginModeCard(
                title = "全仓",
                description = "保证金资产相同的全仓仓位共享该资产的全仓保证金。" +
                    "在强平事件中，交易者可能会损失全部该保证金和该保证金资产下的所有全仓仓位。",
                selected = current == MarginMode.CROSS,
                onClick = { onSelect(MarginMode.CROSS) },
            )

            Spacer(modifier = Modifier.height(12.dp))

            MarginModeCard(
                title = "逐仓",
                description = "一定数量保证金被分配到仓位上。如果仓位保证金亏损到低于维持保证金的水平，" +
                    "仓位将被强平。逐仓下这个仓位的最大亏损就是这笔保证金。",
                selected = current == MarginMode.ISOLATED,
                onClick = { onSelect(MarginMode.ISOLATED) },
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "* 调整保证金模式仅对当前合约生效。",
                color = CsColors.TextFaint,
                fontSize = 11.sp,
            )

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
private fun MarginModeCard(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) CsColors.Primary.copy(alpha = 0.05f) else CsColors.Surface,
            )
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) CsColors.TextPrimary else CsColors.Border,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Text(
            text = title,
            color = CsColors.TextPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = description,
            color = CsColors.TextMuted,
            fontSize = 12.5.sp,
            lineHeight = 20.sp,
        )
    }
}

@Composable
private fun BulletLine(text: String, color: androidx.compose.ui.graphics.Color) {
    Row(
        modifier = Modifier.padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "·",
            color = CsColors.TextFaint,
            fontSize = 12.5.sp,
            style = CsNumericTextStyle,
        )
        Spacer(modifier = Modifier.padding(start = 6.dp))
        Text(
            text = text,
            color = color,
            fontSize = 12.5.sp,
            lineHeight = 19.sp,
        )
    }
}
