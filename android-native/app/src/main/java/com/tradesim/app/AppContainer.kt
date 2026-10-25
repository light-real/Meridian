package com.tradesim.app

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.tradesim.app.core.data.local.TradeSimDatabase
import com.tradesim.app.core.data.market.MarketDataHub
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
import com.tradesim.app.service.MonitorService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor

private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE positions ADD COLUMN marginMode TEXT NOT NULL DEFAULT 'ISOLATED'",
        )
    }
}

private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE positions ADD COLUMN takeProfitPrice TEXT")
        db.execSQL("ALTER TABLE positions ADD COLUMN stopLossPrice TEXT")
    }
}

private val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `limit_orders` (
                `id` TEXT NOT NULL,
                `symbol` TEXT NOT NULL,
                `baseAsset` TEXT NOT NULL,
                `side` TEXT NOT NULL,
                `notional` TEXT NOT NULL,
                `limitPrice` TEXT NOT NULL,
                `leverage` INTEGER NOT NULL,
                `marginMode` TEXT NOT NULL,
                `takeProfitPrice` TEXT,
                `stopLossPrice` TEXT,
                `createdAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_limit_orders_symbol` ON `limit_orders` (`symbol`)")
    }
}

private val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE limit_orders ADD COLUMN frozenCash TEXT NOT NULL DEFAULT '0'")
    }
}

class AppContainer(context: Context) {

    private val appContext = context.applicationContext
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

    /** 应用级协程域：必须在使用它的 repository / hub 之前初始化。 */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val securePrefs = SecurePrefs(context)
    private val sessionStore = SessionStore(securePrefs, json)

    private val supabase = SupabaseClient(
        http = httpClient,
        json = json,
        rawBaseUrl = BuildConfig.SUPABASE_URL,
        anonKey = BuildConfig.SUPABASE_ANON_KEY,
    )

    val authRepository = AuthRepository(supabase, sessionStore)

    private val binanceApi = BinanceApi(httpClient, json)
    private val streamClient = BinanceStreamClient(wsClient, json)

    val marketRepository = MarketRepository(binanceApi, streamClient)

    private val database = Room.databaseBuilder(
        context.applicationContext,
        TradeSimDatabase::class.java,
        "tradesim.db",
    )
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
        .build()

    private val cloudMapper = CloudMapper()

    val accountRepository = AccountRepository(
        database = database,
        authRepository = authRepository,
        supabase = supabase,
        cloudMapper = cloudMapper,
        marketRepository = marketRepository,
    )

    val marketDataHub = MarketDataHub(
        marketRepository = marketRepository,
        accountRepository = accountRepository,
        scope = appScope,
    )

    val favoritesRepository = FavoritesRepository(
        settingsStore = settingsStore,
        authRepository = authRepository,
        supabase = supabase,
    )

    val hasSession: Boolean get() = sessionStore.current != null
    val shouldStartAtMarket: Boolean get() = sessionStore.current != null || settingsStore.isGuest

    init {
        marketDataHub.start()

        // 每 60 秒检查一次资金费结算。资金费 8 小时一次，这个频率足够。
        appScope.launch {
            while (isActive) {
                delay(60_000L)
                runCatching { settleFunding() }
            }
        }

        // 有挂单、或仓位设了止盈止损、或仓位有强平价（逐仓）时，才拉起常驻服务。
        // 全仓且没设止盈止损的仓位不占后台监控。
        appScope.launch {
            combine(
                accountRepository.account
                    .map { account ->
                        account.limitOrders.isNotEmpty() ||
                            account.positions.any { position ->
                                position.takeProfitPrice != null ||
                                    position.stopLossPrice != null ||
                                    position.liquidationPrice.signum() > 0
                            }
                    }
                    .distinctUntilChanged(),
                settingsStore.monitorEnabled,
            ) { hasWork, enabled -> hasWork && enabled }
                .distinctUntilChanged()
                .collect { running ->
                    if (running) {
                        MonitorService.start(appContext)
                    } else {
                        MonitorService.stop(appContext)
                    }
                }
        }
    }

    private suspend fun settleFunding() {
        val positions = accountRepository.getAccountSnapshot().positions
        if (positions.isEmpty()) return
        val now = System.currentTimeMillis()

        positions.map { it.symbol }.distinct().forEach { symbol ->
            val rate = runCatching { marketRepository.fundingRate(symbol) }.getOrNull()
                ?: return@forEach
            if (rate.nextFundingTime <= 0L) return@forEach

            // Binance 的 nextFundingTime 指向下一次结算，减 8 小时就是刚过去的那次
            val boundary = rate.nextFundingTime - FUNDING_INTERVAL_MS
            if (now < boundary) return@forEach

            val settled = settingsStore.fundingSettledAt(symbol)
            if (settled <= 0L) {
                // 第一次见：只记基线，不倒扣它之前的历史，避免一开 App 就被扣钱
                settingsStore.setFundingSettledAt(symbol, boundary)
                return@forEach
            }
            if (settled >= boundary) return@forEach

            accountRepository.applyFunding(
                symbol = symbol,
                rate = rate.rate,
                markPrice = rate.markPrice,
                since = boundary,
            )
            settingsStore.setFundingSettledAt(symbol, boundary)
        }
    }

    private companion object {
        const val FUNDING_INTERVAL_MS = 8L * 60L * 60L * 1000L
    }
}
