package ai.androidclaw.daemon

import ai.androidclaw.R
import ai.androidclaw.agent.AgentManager
import ai.androidclaw.config.ConfigManager
import ai.androidclaw.feishu.FeishuBotService
import ai.androidclaw.feishu.FeishuConfigManager
import ai.androidclaw.ui.MainActivity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AgentDaemonService : LifecycleService() {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var configManager: ConfigManager
    private lateinit var feishuConfigManager: FeishuConfigManager
    private lateinit var agentManager: AgentManager
    private lateinit var feishuBotService: FeishuBotService

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true

        configManager = ConfigManager.getInstance(this)
        feishuConfigManager = FeishuConfigManager.getInstance(this)
        agentManager = AgentManager.getInstance(this)
        feishuBotService = FeishuBotService.getInstance(this)

        startForeground(NOTIFICATION_ID, buildNotification("服务启动中"))
        DaemonWorkScheduler.schedule(this)

        startHealthLoop()
        registerNetworkCallback()
        Log.d(TAG, "AgentDaemonService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureComponentsReady()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        unregisterNetworkCallback()
        scope.cancel()
        DaemonWorkScheduler.schedule(this)
        Log.w(TAG, "AgentDaemonService destroyed, scheduled self-heal worker")
    }

    private fun startHealthLoop() {
        scope.launch {
            while (isActive) {
                try {
                    ensureComponentsReady()
                    updateNotification()
                } catch (e: Exception) {
                    Log.e(TAG, "Health loop error", e)
                }
                delay(30_000)
            }
        }
    }

    private fun ensureComponentsReady() {
        val modelConfig = configManager.getModelConfig()
        if (!agentManager.isReady() && modelConfig.apiKey.isNotBlank()) {
            agentManager.initialize(modelConfig)
            Log.d(TAG, "Agent initialized in daemon")
        }

        if (feishuConfigManager.isConfigured() && feishuConfigManager.isEnabled()) {
            feishuBotService.ensureRunning()
        }
    }

    private fun updateNotification() {
        val state = when {
            !configManager.isConfigured() -> "等待模型配置"
            !feishuConfigManager.isEnabled() -> "飞书机器人未启用"
            !feishuConfigManager.isConfigured() -> "等待飞书配置"
            feishuBotService.isWsConnected() -> "飞书已连接，等待消息"
            else -> "飞书重连中"
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun buildNotification(content: String): Notification {
        ensureNotificationChannel()
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            1001,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("AndroidClaw Agent")
            .setContentText(content)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Agent Daemon",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "用于保持Agent后台运行与飞书消息连接"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun registerNetworkCallback() {
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch {
                    Log.d(TAG, "Network available, ensure feishu running")
                    feishuBotService.ensureRunning()
                    updateNotification()
                }
            }
        }
        connectivityManager?.registerNetworkCallback(request, networkCallback!!)
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        runCatching {
            connectivityManager?.unregisterNetworkCallback(callback)
        }
        networkCallback = null
        connectivityManager = null
    }

    companion object {
        private const val TAG = "AgentDaemonService"
        private const val CHANNEL_ID = "agent_daemon_channel"
        private const val NOTIFICATION_ID = 10010

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, AgentDaemonService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Missing required permission for foreground service", e)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Unable to start service from current app state", e)
            }
        }
    }
}
