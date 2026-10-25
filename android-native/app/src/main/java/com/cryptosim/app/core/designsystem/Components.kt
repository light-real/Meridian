package com.cryptosim.app.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cryptosim.app.core.domain.money
import com.cryptosim.app.core.model.Side
import java.math.BigDecimal
import java.math.RoundingMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CsTopBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = {
            Column {
                Text(
                    text = title,
                    color = CsColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        color = CsColors.TextMuted,
                        fontSize = 11.sp,
                    )
                }
            }
        },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.Outlined.ArrowBack,
                        contentDescription = "返回",
                        tint = CsColors.TextPrimary,
                    )
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = CsColors.Background,
            titleContentColor = CsColors.TextPrimary,
            navigationIconContentColor = CsColors.TextPrimary,
            actionIconContentColor = CsColors.TextSecondary,
        ),
    )
}

@Composable
fun CsCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = CsColors.Surface,
        border = BorderStroke(1.dp, CsColors.Border),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content = content,
        )
    }
}

@Composable
fun CsPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = CsColors.Primary,
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = color,
            contentColor = Color.White,
            disabledContainerColor = color.copy(alpha = 0.25f),
            disabledContentColor = Color.White.copy(alpha = 0.45f),
        ),
    ) {
        Text(
            text = text,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
        )
    }
}

@Composable
fun CsTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = CsColors.Primary,
) {
    TextButton(onClick = onClick, modifier = modifier) {
        Text(text = text, color = color, fontSize = 13.sp)
    }
}

@Composable
fun CsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingContent: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = label?.let {
            {
                Text(
                    text = it,
                    color = CsColors.TextMuted,
                    fontSize = 12.sp,
                )
            }
        },
        placeholder = {
            Text(
                text = placeholder,
                color = CsColors.TextFaint,
                fontSize = 14.sp,
            )
        },
        trailingIcon = trailingContent,
        singleLine = true,
        enabled = enabled,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = keyboardType,
        ),
        visualTransformation = visualTransformation,
        textStyle = CsNumericTextStyle,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = CsColors.TextPrimary,
            unfocusedTextColor = CsColors.TextPrimary,
            disabledTextColor = CsColors.TextMuted,
            focusedBorderColor = CsColors.Primary,
            unfocusedBorderColor = CsColors.BorderStrong,
            focusedContainerColor = CsColors.SurfaceVariant,
            unfocusedContainerColor = CsColors.SurfaceVariant,
            disabledContainerColor = CsColors.SurfaceVariant,
            cursorColor = CsColors.Primary,
            focusedLabelColor = CsColors.Primary,
            unfocusedLabelColor = CsColors.TextMuted,
        ),
    )
}

@Composable
fun CsSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    activeColor: Color = CsColors.Primary,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CsColors.SurfaceVariant)
            .padding(4.dp),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) activeColor.copy(alpha = 0.16f) else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = if (selected) activeColor else CsColors.TextMuted,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

@Composable
fun CsChip(
    text: String,
    selected: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    activeColor: Color = CsColors.Primary,
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(50),
        color = if (selected) activeColor.copy(alpha = 0.16f) else CsColors.SurfaceVariant,
        border = BorderStroke(
            1.dp,
            if (selected) activeColor.copy(alpha = 0.5f) else CsColors.Border,
        ),
    ) {
        Text(
            text = text,
            color = if (selected) activeColor else CsColors.TextMuted,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
fun CsStatRow(
    label: String,
    value: String,
    valueColor: Color = CsColors.TextPrimary,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, color = CsColors.TextMuted, fontSize = 12.sp)
        Text(
            text = value,
            color = valueColor,
            style = CsNumericTextStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun CsSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = CsColors.TextSecondary,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier,
    )
}

@Composable
fun CsEmptyState(
    title: String,
    hint: String? = null,
    icon: ImageVector = Icons.Outlined.Inbox,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = CsColors.TextFaint,
            modifier = Modifier.size(36.dp),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(text = title, color = CsColors.TextMuted, fontSize = 14.sp)
        if (hint != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = hint, color = CsColors.TextFaint, fontSize = 12.sp)
        }
    }
}

@Composable
fun CsErrorState(
    message: String,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = CsColors.Down,
            modifier = Modifier.size(32.dp),
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(text = message, color = CsColors.TextMuted, fontSize = 13.sp)
        if (onRetry != null) {
            Spacer(modifier = Modifier.height(8.dp))
            CsTextButton(text = "重试", onClick = onRetry)
        }
    }
}

@Composable
fun CsLoading(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 56.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            color = CsColors.Primary,
            strokeWidth = 2.dp,
            modifier = Modifier.size(28.dp),
        )
    }
}

@Composable
fun CsDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier = modifier, color = CsColors.Border)
}

@Composable
fun CoinAvatar(
    baseAsset: String,
    size: Dp = 40.dp,
    modifier: Modifier = Modifier,
) {
    val color = coinColor(baseAsset)
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.28f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = baseAsset.take(1).uppercase(),
            color = color,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.38f).sp,
        )
    }
}

@Composable
fun PriceText(
    text: String,
    color: Color = CsColors.TextPrimary,
    fontSize: TextUnit = 14.sp,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        color = color,
        style = CsNumericTextStyle.copy(fontSize = fontSize),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/**
 * 价格变化时短暂闪一下背景色，让行情有“在跳动”的反馈。
 */
@Composable
fun FlashPriceText(
    text: String,
    color: Color = CsColors.TextPrimary,
    fontSize: TextUnit = 14.sp,
    modifier: Modifier = Modifier,
    flashUp: Boolean = true,
) {
    val flash = remember { Animatable(0f) }
    var previous by remember { mutableStateOf(text) }

    LaunchedEffect(text) {
        if (previous != text) {
            flash.snapTo(1f)
            flash.animateTo(0f, animationSpec = tween(durationMillis = 420))
            previous = text
        }
    }

    val flashColor = if (flashUp) CsColors.Up else CsColors.Down
    Text(
        text = text,
        color = color,
        style = CsNumericTextStyle.copy(fontSize = fontSize),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(RoundedCornerShape(5.dp))
            .background(flashColor.copy(alpha = 0.16f * flash.value))
            .padding(horizontal = 2.dp, vertical = 1.dp),
    )
}

@Composable
fun ChangeBadge(
    percent: BigDecimal,
    modifier: Modifier = Modifier,
) {
    val up = percent.signum() >= 0
    val color = if (up) CsColors.Up else CsColors.Down
    Text(
        text = (if (up) "+" else "") + percent.setScale(2, RoundingMode.HALF_UP).toPlainString() + "%",
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
fun SideBadge(side: Side, leverage: Int, modifier: Modifier = Modifier) {
    val long = side == Side.LONG
    val color = if (long) CsColors.Up else CsColors.Down
    Text(
        text = (if (long) "做多" else "做空") + " ${leverage}x",
        color = color,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
fun LabelValue(
    label: String,
    value: String,
    valueColor: Color = CsColors.TextPrimary,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(text = label, color = CsColors.TextMuted, fontSize = 10.sp)
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = value,
            color = valueColor,
            style = CsNumericTextStyle.copy(fontSize = 12.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun MoneyText(
    amount: BigDecimal,
    modifier: Modifier = Modifier,
    color: Color = CsColors.TextPrimary,
    fontSize: TextUnit = 14.sp,
) {
    Text(
        text = amount.money(2),
        color = color,
        style = CsNumericTextStyle.copy(fontSize = fontSize),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

private fun coinColor(baseAsset: String): Color = when (baseAsset.uppercase()) {
    "BTC" -> Color(0xFFF7931A)
    "ETH" -> Color(0xFF627EEA)
    "BNB" -> Color(0xFFF3BA2F)
    "SOL" -> Color(0xFF9945FF)
    "XRP" -> Color(0xFF00AAE4)
    "DOGE" -> Color(0xFFC2A633)
    "ADA" -> Color(0xFF0033AD)
    "AVAX" -> Color(0xFFE84142)
    "DOT" -> Color(0xFFE6007A)
    "LINK" -> Color(0xFF2A5ADA)
    else -> Color(0xFF60A5FA)
}
