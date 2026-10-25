package com.tradesim.app.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.tradesim.app.core.designsystem.CsColors
import com.tradesim.app.feature.auth.AuthScreen
import com.tradesim.app.feature.history.HistoryScreen
import com.tradesim.app.feature.market.MarketScreen
import com.tradesim.app.feature.positions.PositionsScreen
import com.tradesim.app.feature.profile.ProfileScreen
import com.tradesim.app.feature.trade.TradeScreen
import com.tradesim.app.ui.LocalAppContainer

object Routes {
    const val AUTH = "auth"
    const val MARKET = "market"
    const val POSITIONS = "positions"
    const val HISTORY = "history"
    const val PROFILE = "profile"
    const val TRADE = "trade/{symbol}"

    /** 从交易页打开的记录页，带返回按钮，不占底部 tab。 */
    const val TRADE_HISTORY = "trade-history"

    fun trade(symbol: String): String = "trade/$symbol"
}

private data class BottomItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val bottomItems = listOf(
    BottomItem(Routes.MARKET, "行情", Icons.Outlined.ShowChart),
    BottomItem(Routes.POSITIONS, "持仓", Icons.Outlined.AccountBalanceWallet),
    BottomItem(Routes.HISTORY, "记录", Icons.Outlined.ReceiptLong),
    BottomItem(Routes.PROFILE, "我的", Icons.Outlined.Person),
)

@Composable
fun AppRoot() {
    val container = LocalAppContainer.current
    val navController = rememberNavController()
    val startDestination = remember {
        if (container.shouldStartAtMarket) Routes.MARKET else Routes.AUTH
    }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in bottomItems.map { it.route }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = CsColors.Background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar) {
                Column {
                    HorizontalDivider(color = CsColors.Border)
                    NavigationBar(
                        modifier = Modifier.navigationBarsPadding(),
                        containerColor = CsColors.Surface,
                        tonalElevation = 0.dp,
                    ) {
                        bottomItems.forEach { item ->
                            NavigationBarItem(
                                selected = currentRoute == item.route,
                                onClick = {
                                    if (currentRoute != item.route) {
                                        navController.navigate(item.route) {
                                            popUpTo(
                                                navController.graph.findStartDestination().id,
                                            ) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                },
                                icon = {
                                    Icon(
                                        imageVector = item.icon,
                                        contentDescription = item.label,
                                        modifier = Modifier.size(22.dp),
                                    )
                                },
                                label = { Text(text = item.label, fontSize = 11.sp) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = CsColors.Primary,
                                    selectedTextColor = CsColors.Primary,
                                    unselectedIconColor = CsColors.TextMuted,
                                    unselectedTextColor = CsColors.TextMuted,
                                    indicatorColor = CsColors.Primary.copy(alpha = 0.1f),
                                ),
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.AUTH) {
                AuthScreen(
                    onLoggedIn = {
                        navController.navigate(Routes.MARKET) {
                            popUpTo(Routes.AUTH) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable(Routes.MARKET) {
                MarketScreen(
                    onOpenTrade = { symbol -> navController.navigate(Routes.trade(symbol)) },
                )
            }
            composable(Routes.POSITIONS) {
                PositionsScreen(
                    onOpenTrade = { symbol -> navController.navigate(Routes.trade(symbol)) },
                )
            }
            composable(Routes.HISTORY) {
                HistoryScreen()
            }
            composable(Routes.TRADE_HISTORY) {
                HistoryScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.PROFILE) {
                ProfileScreen(
                    onOpenAuth = { navController.navigate(Routes.AUTH) },
                    onLoggedOut = {
                        navController.navigate(Routes.AUTH) {
                            popUpTo(Routes.MARKET) { inclusive = true }
                        }
                    },
                )
            }
            composable(
                route = Routes.TRADE,
                arguments = listOf(navArgument("symbol") { type = NavType.StringType }),
            ) { entry ->
                TradeScreen(
                    symbol = entry.arguments?.getString("symbol").orEmpty(),
                    onBack = { navController.popBackStack() },
                    onOpenHistory = { navController.navigate(Routes.TRADE_HISTORY) },
                )
            }
        }
    }
}
