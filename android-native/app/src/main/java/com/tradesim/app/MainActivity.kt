package com.tradesim.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.tradesim.app.core.designsystem.TradeSimTheme
import com.tradesim.app.navigation.AppRoot
import com.tradesim.app.ui.LocalAppContainer

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as TradeSimApplication).container
        setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                TradeSimTheme {
                    AppRoot()
                }
            }
        }
    }
}
