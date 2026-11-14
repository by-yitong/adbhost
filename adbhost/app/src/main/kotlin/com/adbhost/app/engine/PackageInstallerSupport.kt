package com.adbhost.app.engine

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import java.io.File

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object PackageInstallerSupport {

    private const val ACTION_RESULT = "com.adbhost.app.INSTALL_RESULT"
    private const val TIMEOUT_MINUTES = 5L

    fun install(
        context: Context,
        apk: File,
        allowTest: Boolean,
        allowDowngrade: Boolean,
        grantRuntimePermissions: Boolean,
    ): InstallOutcome {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        setInstallFlagsBits(params, buildList {
            if (allowTest) add(INSTALL_ALLOW_TEST)
            if (allowDowngrade) add(INSTALL_ALLOW_DOWNSGRADE)
            if (grantRuntimePermissions) add(INSTALL_GRANT_RUNTIME_PERMISSIONS)
        })

        val sessionId = installer.createSession(params)
        debug(context, "session created id=$sessionId")
        val session = installer.openSession(sessionId)
        try {
            session.openWrite("adb_install.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            debug(context, "apk written ${apk.length()}B")
            val latch = CountDownLatch(1)
            val result = arrayOfNulls<InstallOutcome>(1)
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context, intent: Intent) {
                    val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                    val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                    result[0] = when (status) {
                        PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                            if (confirm != null) {
                                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                try {
                                    receiverContext.startActivity(confirm)
                                    debug(receiverContext, "startActivity ok")
                                } catch (backgroundDenied: Exception) {
                                    debug(receiverContext, "startActivity failed: ${backgroundDenied.message}")
                                    notifyConfirm(receiverContext, confirm)
                                }
                            }
                            null
                        }
                        PackageInstaller.STATUS_SUCCESS -> InstallOutcome(true, "Success")
                        else -> InstallOutcome(false, "Failure [$message]")
                    }
                    if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) latch.countDown()
                }
            }
            debug(context, "committing...")
            context.registerReceiver(receiver, IntentFilter(ACTION_RESULT), Context.RECEIVER_EXPORTED)
            val intent = Intent(ACTION_RESULT).setPackage(context.packageName)
            val flag = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            val sender = PendingIntent.getBroadcast(context, sessionId, intent, flag)
            try {
                session.commit(sender.intentSender)
            } finally {
                session.close()
            }
            val gotIt = latch.await(TIMEOUT_MINUTES, TimeUnit.MINUTES)
            debug(context, "latch done got=$gotIt result=${result[0]}")
            context.unregisterReceiver(receiver)
            return result[0] ?: InstallOutcome(false, "Failure [timeout waiting for user confirmation]")
        } catch (e: SecurityException) {
            debug(context, "SecurityException: ${e.message}")
            runCatching { session.abandon() }
            return InstallOutcome(
                false,
                "Failure [需要权限]: 请在系统设置中允许 AdbHost 安装未知应用 (${e.message})",
            )
        } catch (e: Exception) {
            runCatching { session.abandon() }
            return InstallOutcome(false, "Failure [${e.message}]")
        }
    }

    private const val INSTALL_ALLOW_TEST = 0x00000004
    private const val INSTALL_ALLOW_DOWNSGRADE = 0x00000080
    private const val INSTALL_GRANT_RUNTIME_PERMISSIONS = 0x00000100

    private fun notifyConfirm(context: Context, confirm: Intent) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val channelId = "adbhost_install"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                android.app.NotificationChannel(channelId, "安装确认", android.app.NotificationManager.IMPORTANCE_HIGH),
            )
        }
        val tap = PendingIntent.getActivity(context, 1001, confirm, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        val notification = android.app.Notification.Builder(context, channelId)
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle("电脑请求安装 APK")
            .setContentText("点此打开安装确认页")
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        manager.notify(2001, notification)
        debug(context, "notification posted")
    }

    private fun debug(context: Context, text: String) {
        runCatching {
            val dir = context.getExternalFilesDir(null) ?: return
            File(dir, ".install_debug").appendText("${System.currentTimeMillis()} $text\n")
        }
    }

    private fun setInstallFlagsBits(params: PackageInstaller.SessionParams, bits: List<Int>) {
        runCatching {
            val field = PackageInstaller.SessionParams::class.java.getField("installFlags")
            val current = field.getInt(params)
            field.setInt(params, current or bits.fold(0) { acc, bit -> acc or bit })
        }
    }
}
