package com.tradesim.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.tradesim.app.MainActivity
import com.tradesim.app.TradeSimApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 常驻监控：每 15 秒拉一次价格，检查强平 / 止盈止损 / 限价成交。
 *
 * 用 REST 而不是 WebSocket：走代理时短请求比长连接抗造，断了下一轮自己就恢复了。
 * 没有仓位也没有挂单时会自动退出，不占着通知栏。
 */
class MonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(FOREGROUND_ID, buildOngoing("正在连接行情…"))
        if (monitorJob?.isActive != true) {
            monitorJob = scope.launch { monitor() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun monitor() {
        val container = (application as TradeSimApplication).container

        while (currentCoroutineContext().isActive) {
            val account = runCatching { container.accountRepository.getAccountSnapshot() }
                .getOrNull()

            if (account == null) {
                delay(POLL_INTERVAL_MS)
                continue
            }

            val symbols = (account.positions.map { it.symbol } + account.limitOrders.map { it.symbol })
                .distinct()

            if (symbols.isEmpty()) {
                stopSelf()
                return
            }

            updateOngoing(account.positions.size, account.limitOrders.size)

            val marks = runCatching {
                container.marketRepository.tickers(symbols)
                    .associate { it.symbol to it.lastPrice }
            }.getOrElse { emptyMap() }

            if (marks.isNotEmpty()) {
                runCatching {
                    val messages = mutableListOf<String>()

                    container.accountRepository.checkLiquidations(marks).forEach {
                        messages += "${it.baseAsset} 仓位已强制平仓"
                    }
                    container.accountRepository.checkTriggers(marks).forEach {
                        messages += "${it.baseAsset} 仓位已触发止盈止损"
                    }
                    container.accountRepository.checkLimitOrders(marks).forEach {
                        messages += "${it.baseAsset} 限价委托已成交"
                    }

                    messages.forEach { alert(it) }
                }
            }

            delay(POLL_INTERVAL_MS)
        }
    }

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ONGOING,
                    "后台监控",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "常驻通知，显示当前监控的仓位数量"
                    setShowBadge(false)
                },
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ALERT,
                    "交易提醒",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "强平、止盈止损、限价成交提醒"
                },
            )
        }
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun buildOngoing(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ONGOING)
            .setContentTitle("TradeSim 监控中")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(contentIntent())
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    private fun updateOngoing(positionCount: Int, orderCount: Int) {
        val text = buildString {
            append("$positionCount 个仓位")
            if (orderCount > 0) append(" · $orderCount 张委托")
        }
        getSystemService(NotificationManager::class.java)
            ?.notify(FOREGROUND_ID, buildOngoing(text))
    }

    private fun alert(message: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val notification = NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setContentTitle("TradeSim")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentIntent(contentIntent())
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        manager.notify(System.currentTimeMillis().toInt(), notification)
    }

    companion object {
        private const val CHANNEL_ONGOING = "monitor_ongoing"
        private const val CHANNEL_ALERT = "monitor_alert"
        private const val FOREGROUND_ID = 1001
        private const val POLL_INTERVAL_MS = 15_000L

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, MonitorService::class.java),
                )
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, MonitorService::class.java)) }
        }
    }
}
