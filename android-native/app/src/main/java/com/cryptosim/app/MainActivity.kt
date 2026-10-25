package com.cryptosim.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.cryptosim.app.core.designsystem.CryptoSimTheme
import com.cryptosim.app.navigation.AppRoot
import com.cryptosim.app.ui.LocalAppContainer

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as CryptoSimApplication).container
        setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                CryptoSimTheme {
                    AppRoot()
                }
            }
        }
    }
}
