package com.adbhost.app.engine

import android.content.Context
import com.adbhost.app.core.AdbConnection
import com.adbhost.app.core.ServiceHandler
import java.io.File

class ServiceDispatcher(
    private val syncRoot: File,
    private val rootMode: () -> Boolean,
    private val log: (String) -> Unit,
    private val appContext: Context? = null,
    private val onFileReceived: (File, String) -> Unit = { _, _ -> },
) {

    fun open(service: String, peerLocalId: Long, remoteId: Long, conn: AdbConnection): ServiceHandler = when {
        service == SERVICE_FEATURES -> FeaturesService(conn, remoteId, peerLocalId)
        service.startsWith(SERVICE_SYNC) -> SyncService(syncRoot, conn, remoteId, peerLocalId, log, onFileReceived)
        isInstallRequest(service) -> InstallService.create(
            appContext ?: throw ServiceRejectedException("install 不可用"),
            service,
            syncRoot,
            conn,
            remoteId,
            peerLocalId,
            rootMode(),
            log,
        )
        service == ShellRequestParser.SHELL_PREFIX ||
            service.startsWith("${ShellRequestParser.SHELL_PREFIX}:") ||
            service.startsWith("${ShellRequestParser.SHELL_PREFIX},") ->
            ShellRequestParser.parse(service)?.let { request ->
                ShellService(request, rootMode(), conn, remoteId, peerLocalId, log)
            } ?: reject(service, "无法解析 shell 服务名")
        service.startsWith(SERVICE_REJECTED_PREFIXES) -> reject(service, "拒绝服务")
        else -> reject(service, "未知服务")
    }

    private fun isInstallRequest(service: String): Boolean {
        if (appContext == null) return false
        val command = ShellRequestParser.parse(service)?.command?.replace("'", "") ?: return false
        return command.startsWith("pm install") || command.startsWith("cmd package install")
    }

    private fun reject(service: String, reason: String): Nothing {
        log("$reason:$service")
        throw ServiceRejectedException(reason)
    }

    class ServiceRejectedException(message: String) : IllegalArgumentException(message)

    companion object {
        const val SERVICE_FEATURES = "features"
        const val SERVICE_SYNC = "sync"
        private const val SERVICE_REJECTED_PREFIXES = "root"
    }
}
