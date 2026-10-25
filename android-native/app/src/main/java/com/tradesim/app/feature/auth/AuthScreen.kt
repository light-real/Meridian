package com.tradesim.app.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tradesim.app.core.designsystem.CsColors
import com.tradesim.app.core.designsystem.CsPrimaryButton
import com.tradesim.app.core.designsystem.CsSegmented
import com.tradesim.app.core.designsystem.CsTextField
import com.tradesim.app.core.designsystem.CsTextButton
import com.tradesim.app.ui.LocalAppContainer
import com.tradesim.app.ui.appViewModelFactory

@Composable
fun AuthScreen(onLoggedIn: () -> Unit) {
    val container = LocalAppContainer.current
    val viewModel: AuthViewModel = viewModel(
        factory = appViewModelFactory {
            AuthViewModel(
                authRepository = container.authRepository,
                accountRepository = container.accountRepository,
                favoritesRepository = container.favoritesRepository,
                settingsStore = container.settingsStore,
            )
        },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.navigateToMarket) {
        if (state.navigateToMarket) {
            viewModel.consumeNavigation()
            onLoggedIn()
        }
    }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        containerColor = CsColors.Background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            Spacer(modifier = Modifier.height(56.dp))

            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(CsColors.Primary, CsColors.Up),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "C",
                    color = androidx.compose.ui.graphics.Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "TradeSim",
                color = CsColors.TextPrimary,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "合约交易 · USDT 永续",
                color = CsColors.TextMuted,
                fontSize = 13.sp,
            )

            Spacer(modifier = Modifier.height(40.dp))

            CsSegmented(
                options = listOf("登录", "注册"),
                selectedIndex = state.mode,
                onSelect = viewModel::setMode,
            )

            Spacer(modifier = Modifier.height(20.dp))

            CsTextField(
                value = state.email,
                onValueChange = viewModel::onEmailChange,
                label = "邮箱",
                placeholder = "请输入邮箱",
                keyboardType = KeyboardType.Email,
                enabled = !state.loading,
            )

            Spacer(modifier = Modifier.height(12.dp))

            CsTextField(
                value = state.password,
                onValueChange = viewModel::onPasswordChange,
                label = "密码",
                placeholder = "请输入密码（至少 6 位）",
                keyboardType = KeyboardType.Password,
                visualTransformation = if (state.showPassword) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                enabled = !state.loading,
                trailingContent = {
                    IconButton(onClick = viewModel::togglePasswordVisibility) {
                        Icon(
                            imageVector = if (state.showPassword) {
                                Icons.Outlined.VisibilityOff
                            } else {
                                Icons.Outlined.Visibility
                            },
                            contentDescription = if (state.showPassword) "隐藏密码" else "显示密码",
                            tint = CsColors.TextMuted,
                        )
                    }
                },
            )

            if (state.error != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = state.error.orEmpty(),
                    color = CsColors.Down,
                    fontSize = 12.sp,
                )
            }

            Spacer(modifier = Modifier.height(22.dp))

            CsPrimaryButton(
                text = when {
                    state.loading -> "处理中…"
                    state.mode == 0 -> "登录"
                    else -> "注册"
                },
                onClick = viewModel::submit,
                enabled = !state.loading,
            )

            Spacer(modifier = Modifier.height(10.dp))

            CsTextButton(
                text = "暂不登录，以游客模式使用",
                onClick = viewModel::continueAsGuest,
                modifier = Modifier.fillMaxWidth(),
                color = CsColors.TextMuted,
            )

            if (!state.isConfigured) {
                Spacer(modifier = Modifier.height(18.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(CsColors.Warning.copy(alpha = 0.1f))
                        .padding(12.dp),
                ) {
                    Text(
                        text = "未配置 Supabase，当前只能使用游客模式",
                        color = CsColors.Warning,
                        fontSize = 12.sp,
                    )
                }
            }

            Spacer(modifier = Modifier.height(36.dp))

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
