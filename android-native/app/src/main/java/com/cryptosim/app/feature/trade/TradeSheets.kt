package com.cryptosim.app.feature.trade

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cryptosim.app.core.designsystem.CoinAvatar
import com.cryptosim.app.core.designsystem.CsCard
import com.cryptosim.app.core.designsystem.CsChip
import com.cryptosim.app.core.designsystem.CsColors
import com.cryptosim.app.core.designsystem.CsDivider
import com.cryptosim.app.core.designsystem.CsPrimaryButton
import com.cryptosim.app.core.designsystem.CsStatRow
import com.cryptosim.app.core.designsystem.CsTextField
import com.cryptosim.app.core.designsystem.PriceText
import com.cryptosim.app.core.domain.money
import com.cryptosim.app.core.domain.percent
import com.cryptosim.app.core.domain.price
import com.cryptosim.app.core.domain.quantity
import com.cryptosim.app.core.domain.signed
import com.cryptosim.app.core.model.Side
import com.cryptosim.app.core.model.SymbolInfo
import java.math.BigDecimal
import java.math.RoundingMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SymbolPickerSheet(
    state: TradeUiState,
    onDismiss: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSelect: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val query = state.symbolQuery.trim().uppercase()
    val filtered = remember(state.symbols, query) {
        if (query.isEmpty()) {
            state.symbols
        } else {
            state.symbols.filter {
                it.symbol.contains(query) || it.baseAsset.contains(query)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = CsColors.SurfaceElevated,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            Text(
                text = "选择交易对",
                color = CsColors.TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(10.dp))
            CsTextField(
                value = state.symbolQuery,
                onValueChange = onQueryChange,
                placeholder = "搜索币种，如 BTC",
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (filtered.isEmpty()) {
                Text(
                    text = "未找到相关币种",
                    color = CsColors.TextFaint,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(filtered, key = { it.symbol }) { info ->
                        SymbolPickerRow(
                            info = info,
                            selected = info.symbol == state.symbol,
                            onClick = { onSelect(info.symbol) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SymbolPickerRow(
    info: SymbolInfo,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoinAvatar(baseAsset = info.baseAsset, size = 32.dp)
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = info.baseAsset,
                color = if (selected) CsColors.Primary else CsColors.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = info.symbol,
                color = CsColors.TextFaint,
                fontSize = 10.sp,
            )
        }
        if (selected) {
            Text(
                text = "当前",
                color = CsColors.Primary,
                fontSize = 10.sp,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClosePositionSheet(
    state: TradeUiState,
    sheet: CloseSheetState,
    onDismiss: () -> Unit,
    onQuantityChange: (String) -> Unit,
    onPercent: (BigDecimal) -> Unit,
    onConfirm: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val info = state.symbolInfo
    val position = sheet.position
    val baseAsset = position.baseAsset
    val priceScale = info?.pricePrecision ?: 2
    val quantityScale = info?.quantityPrecision ?: 3
    val preview = sheet.preview
    val pnlColor = if (preview.pnl.signum() >= 0) CsColors.Up else CsColors.Down
    val confirmColor = if (position.side == Side.LONG) CsColors.Down else CsColors.Up
    val haptic = LocalHapticFeedback.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = CsColors.SurfaceElevated,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (position.side == Side.LONG) "平多仓" else "平空仓",
                    color = CsColors.TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = position.symbol,
                    color = CsColors.TextMuted,
                    fontSize = 11.sp,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("当前价格", color = CsColors.TextMuted, fontSize = 11.sp)
                PriceText(
                    text = sheet.markPrice.price(priceScale),
                    color = if (position.side == Side.LONG) CsColors.Down else CsColors.Up,
                    fontSize = 15.sp,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            CsTextField(
                value = sheet.quantityInput,
                onValueChange = onQuantityChange,
                label = "平仓数量",
                placeholder = "0.00",
                keyboardType = KeyboardType.Decimal,
                trailingContent = {
                    Text(
                        text = baseAsset,
                        color = CsColors.TextMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(end = 10.dp),
                    )
                },
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    "25%" to BigDecimal("0.25"),
                    "50%" to BigDecimal("0.50"),
                    "75%" to BigDecimal("0.75"),
                    "100%" to BigDecimal.ONE,
                ).forEach { (label, percent) ->
                    CsChip(
                        text = label,
                        onClick = { onPercent(percent) },
                        activeColor = confirmColor,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            CsDivider()
            Spacer(modifier = Modifier.height(10.dp))

            CsStatRow(
                label = "最多可平",
                value = position.quantity.quantity(quantityScale) + " $baseAsset",
            )
            CsStatRow(
                label = "平仓比例",
                value = preview.ratioPercent.setScale(2, RoundingMode.HALF_UP).toPlainString() + "%",
            )
            CsStatRow(
                label = "预计盈亏",
                value = preview.pnl.signed(2) + " USDT",
                valueColor = pnlColor,
            )
            CsStatRow(
                label = "平仓手续费",
                value = preview.fee.money(2) + " USDT",
            )
            CsStatRow(
                label = "返还保证金",
                value = preview.returnAmount.money(2) + " USDT",
            )

            if (preview.clamped) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "输入数量超过持仓，将按最多可平数量处理",
                    color = CsColors.Warning,
                    fontSize = 10.sp,
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            CsPrimaryButton(
                text = if (position.side == Side.LONG) "确认平多" else "确认平空",
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onConfirm()
                },
                enabled = preview.canClose,
                color = confirmColor,
            )
        }
    }
}
