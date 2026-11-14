package com.adbhost.app.platform

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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.adbhost.app.MainActivity
import com.adbhost.app.engine.AdbServiceController
import com.adbhost.app.engine.ServiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class AdbForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null
    private var lastState: ServiceState? = null
    private var lastReceivedCount = 0

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        when (intent?.action) {
            ACTION_STOP -> {
                AdbServiceController.sharedInstance?.stop()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val controller = obtainController()
                observeState(controller)
                scope.launch(Dispatchers.Default) { controller.start(controller.settings.port) }
            }
            else -> AdbServiceController.sharedInstance?.let { observeState(it) }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun obtainController(): AdbServiceController =
        AdbServiceController.sharedInstance ?: AdbServiceController(applicationContext)

    private fun observeState(controller: AdbServiceController) {
        stateJob?.cancel()
        lastState = controller.state.value
        lastReceivedCount = controller.receivedFiles.value.size
        stateJob = scope.launch {
            launch {
                controller.state.collect { state ->
                    lastState = state
                    notifyCombined()
                }
            }
            launch {
                controller.receivedFiles.collect { files ->
                    lastReceivedCount = files.size
                    notifyCombined()
                }
            }
        }
    }

    private fun goForeground() {
        val controller = AdbServiceController.sharedInstance
        val state = controller?.state?.value ?: ServiceState(false, DEFAULT_PORT, "", null)
        val notification = buildNotification(state, controller?.receivedFiles?.value?.size ?: 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notifyCombined() {
        val state = lastState ?: return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(state, lastReceivedCount))
    }

    private fun buildNotification(state: ServiceState, receivedCount: Int): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            REQUEST_CODE_STOP,
            Intent(this, AdbForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val openIntent = PendingIntent.getActivity(
            this,
            REQUEST_CODE_OPEN,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = if (state.running) {
            "${state.ip}:${state.port}" +
                (if (state.connectedCount > 0) " · 已连接 ${state.connectedCount} 台" else "") +
                (if (receivedCount > 0) " · 已接收 $receivedCount 个文件" else "")
        } else {
            state.error ?: "正在启动…"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("AdbHost ADB 服务")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "停止服务", stopIntent)
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "ADB 服务",
            NotificationManager.IMPORTANCE_LOW,
        )
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "adbhost"
        const val ACTION_START = "com.adbhost.app.action.ADB_START"
        const val ACTION_STOP = "com.adbhost.app.action.ADB_STOP"
        private const val NOTIFICATION_ID = 1
        private const val REQUEST_CODE_STOP = 2
        private const val REQUEST_CODE_OPEN = 3
        private const val DEFAULT_PORT = 5555

        fun startIntent(context: Context): Intent =
            Intent(context, AdbForegroundService::class.java).setAction(ACTION_START)

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, startIntent(context))
        }
    }
}
