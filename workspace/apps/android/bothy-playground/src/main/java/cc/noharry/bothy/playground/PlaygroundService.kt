package cc.noharry.bothy.playground

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the MCP server and the world's clock running while Bothy is in front: in a demo the playground sits behind
 * Bothy's floating card or beside it in split screen, and the activity alone would be stopped there.
 */
class PlaygroundService : Service() {
    private var server: McpHttpServer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        val store = Playground.store
        HeadUnitModes.load(this)
        val engine = AMapNavEngine(this, store, BuildConfig.AMAP_CONFIGURED).apply { realNav = HeadUnitModes.realNav }
        val navigation = NavController(
            store, engine, focus = { Playground.mapCues.tryEmit(it) }, mapWatching = { Playground.mapShowing }, app = AmapApp(this),
        )
        Playground.engine = engine
        Playground.navigation = navigation
        if (HeadUnitModes.realNav) navigation.setSimulated(false)
        server = McpHttpServer(McpHttpServer.DEFAULT_PORT, playgroundEndpoints(store, navigation)).also { mcp ->
            try {
                mcp.start()
                store.setServer(ServerState(ServerStatus.RUNNING, "http://127.0.0.1:${mcp.boundPort}"))
                // Bothy may hold the tools of the other mode from before this start: told once the server answers.
                Assistant.toolsChanged(this)
            } catch (taken: IOException) {
                store.setServer(ServerState(ServerStatus.FAILED, problem = "端口 ${McpHttpServer.DEFAULT_PORT} 被占用：${taken.message}"))
            }
        }
        scope.launch {
            var last = System.nanoTime()
            while (isActive) {
                delay(TICK_MS)
                val now = System.nanoTime()
                store.tick((now - last) / 1e9)
                last = now
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        server?.close()
        super.onDestroy()
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "演示场服务", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, PlaygroundActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        val notification = builder
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("Bothy Playground 在运行")
            .setContentText("虚拟座舱和小家的 MCP 服务：127.0.0.1:${McpHttpServer.DEFAULT_PORT}")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL = "playground"
        private const val NOTIFICATION_ID = 1
        private const val TICK_MS = 500L

        fun start(context: Context) {
            val intent = Intent(context, PlaygroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }
    }
}
