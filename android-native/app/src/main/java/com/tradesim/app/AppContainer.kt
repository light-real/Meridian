package com.tradesim.app

import android.content.Context
import androidx.room.Room
import com.tradesim.app.core.data.local.TradeSimDatabase
import com.tradesim.app.core.data.network.BinanceApi
import com.tradesim.app.core.data.network.BinanceStreamClient
import com.tradesim.app.core.data.network.HttpClient
import com.tradesim.app.core.data.repository.AccountRepository
import com.tradesim.app.core.data.repository.AuthRepository
import com.tradesim.app.core.data.repository.CloudMapper
import com.tradesim.app.core.data.repository.FavoritesRepository
import com.tradesim.app.core.data.repository.MarketRepository
import com.tradesim.app.core.data.session.SecurePrefs
import com.tradesim.app.core.data.session.SessionStore
import com.tradesim.app.core.data.settings.SettingsStore
import com.tradesim.app.core.data.supabase.SupabaseClient
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor

class AppContainer(context: Context) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val restClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .addInterceptor(
            HttpLoggingInterceptor().apply {
                level = if (BuildConfig.DEBUG) {
                    HttpLoggingInterceptor.Level.BASIC
                } else {
                    HttpLoggingInterceptor.Level.NONE
                }
                redactHeader("Authorization")
                redactHeader("apikey")
            },
        )
        .build()

    private val wsClient = restClient.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val httpClient = HttpClient(restClient, json)

    val settingsStore = SettingsStore(context)

    private val securePrefs = SecurePrefs(context)
    private val sessionStore = SessionStore(securePrefs, json)

    private val supabase = SupabaseClient(
        http = httpClient,
        json = json,
        rawBaseUrl = BuildConfig.SUPABASE_URL,
        anonKey = BuildConfig.SUPABASE_ANON_KEY,
    )

    val authRepository = AuthRepository(supabase, sessionStore)

    private val database = Room.databaseBuilder(
        context.applicationContext,
        TradeSimDatabase::class.java,
        "tradesim.db",
    ).build()

    private val cloudMapper = CloudMapper()

    val accountRepository = AccountRepository(
        database = database,
        authRepository = authRepository,
        supabase = supabase,
        cloudMapper = cloudMapper,
    )

    val favoritesRepository = FavoritesRepository(
        settingsStore = settingsStore,
        authRepository = authRepository,
        supabase = supabase,
    )

    private val binanceApi = BinanceApi(httpClient, json)
    private val streamClient = BinanceStreamClient(wsClient, json)

    val marketRepository = MarketRepository(binanceApi, streamClient)

    val hasSession: Boolean get() = sessionStore.current != null
    val shouldStartAtMarket: Boolean get() = sessionStore.current != null || settingsStore.isGuest
}
