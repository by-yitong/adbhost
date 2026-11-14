package com.adbhost.app.engine

import android.content.Context
import com.adbhost.app.core.AdbCommand
import com.adbhost.app.core.AdbConnection
import com.adbhost.app.core.AdbMessage
import com.adbhost.app.core.ServiceHandler
import com.adbhost.app.core.ShellProtocol
import java.io.File

object InstallService {

    fun create(
        context: Context,
        command: String,
        syncRoot: File,
        conn: AdbConnection,
        remoteId: Long,
        peerLocalId: Long,
        rootMode: Boolean,
        log: (String) -> Unit,
    ): ServiceHandler {
        val shellCommand = ShellRequestParser.parse(command)?.command?.replace("'", "")
            ?: return failedHandler(conn, remoteId, peerLocalId, "Failure [cannot parse install command]", command, log)
        val body = shellCommand
            .removePrefix("cmd package install")
            .removePrefix("pm install")
            .trim()
        val args = InstallArgsParser.parse("pm install $body")
        if (args.parseError != null) {
            return failedHandler(conn, remoteId, peerLocalId, "Failure [${args.parseError}]", command, log)
        }
        val mapped = args.apkPaths.associateWith { path ->
            SyncPathMapper.toLocal(syncRoot, path)?.takeIf { it.isFile }
        }
        val missing = mapped.filterValues { it == null }.keys
        if (missing.isNotEmpty()) {
            return failedHandler(conn, remoteId, peerLocalId, "Failure [${missing.first()}: file not found in device storage]", command, log)
        }

        if (rootMode && RootChecker.isSuAvailable()) {
            val rewritten = rewritePaths(shellCommand, mapped)
            log("install root 模式:路径已映射,原生执行 pm")
            return ShellService(
                ShellRequest(ShellRequestParser.VERSION_V2, pty = false, raw = true, command = rewritten),
                rootMode = true,
                conn = conn,
                remoteId = remoteId,
                peerLocalId = peerLocalId,
                log = log,
            )
        }

        val apk = mapped.values.first()!!
        if (apksOf(args).size > 1) {
            return failedHandler(conn, remoteId, peerLocalId, "Failure [multiple apk not supported]", command, log)
        }
        log("install 请求 ${apk.name} ${apk.length()}B")
        runCatching { File(syncRoot, ".install_debug").appendText("${System.currentTimeMillis()} create mode-selected\n") }
        return when (ShizukuInstallSupport.status()) {
            ShizukuStatus.READY -> {
                log("install 走 Shizuku 静默安装")
                AsyncInstallHandler(conn, remoteId, peerLocalId, command, log) {
                    runCatching {
                        ShizukuInstallSupport.install(apk, args.allowTest, args.allowDowngrade, args.grantRuntimePermissions)
                    }.getOrElse { InstallOutcome(false, "Failure [Shizuku: ${it.message}]") }
                }
            }
            else -> {
                log("install 走系统确认安装(PackageInstaller)")
                AsyncInstallHandler(conn, remoteId, peerLocalId, command, log) {
                    PackageInstallerSupport.install(context, apk, args.allowTest, args.allowDowngrade, args.grantRuntimePermissions)
                }
            }
        }
    }

    private fun apksOf(args: InstallArgs): List<File> = emptyList()

    private fun rewritePaths(shellCommand: String, mapped: Map<String, File?>): String {
        var rewritten = shellCommand
        mapped.forEach { (remote, local) ->
            local?.let { rewritten = rewritten.replace(remote, it.absolutePath) }
        }
        return rewritten
    }

    private fun failedHandler(
        conn: AdbConnection,
        remoteId: Long,
        peerLocalId: Long,
        message: String,
        command: String,
        log: (String) -> Unit,
    ): ServiceHandler {
        log("install 失败: $message")
        return AsyncInstallHandler(conn, remoteId, peerLocalId, command, log) { InstallOutcome(false, message) }
    }
}

class AsyncInstallHandler(
    private val conn: AdbConnection,
    private val remoteId: Long,
    private val peerLocalId: Long,
    private val command: String,
    private val log: (String) -> Unit,
    private val task: () -> InstallOutcome,
) : ServiceHandler {

    private val closed = BooleanArray(1)

    init {
        Thread({
            val outcome = runCatching { task() }.getOrElse {
                InstallOutcome(false, "Failure [${it.message}]")
            }
            if (closed[0]) return@Thread
            val text = (outcome.message + "\n").toByteArray(Charsets.UTF_8)
            val stdout = if (v2()) ShellV2Encoder.encode(ShellProtocol.STDOUT, text) else text
            runCatching {
                conn.sendMessage(AdbMessage(AdbCommand.WRTE, remoteId, peerLocalId, stdout))
                if (v2()) {
                    conn.sendMessage(
                        AdbMessage(AdbCommand.WRTE, remoteId, peerLocalId, ShellV2Encoder.exit(if (outcome.success) 0 else 1)),
                    )
                }
                conn.closeService(remoteId)
            }
            log("install ${if (outcome.success) "成功" else "失败"}: ${outcome.message.take(120)}")
        }, "install-$remoteId").apply { isDaemon = true }.start()
    }

    private fun v2(): Boolean = command.startsWith("shell,v2") || command.contains(",v2,")

    override fun onPayload(data: ByteArray) = Unit

    override fun onClosed() {
        closed[0] = true
    }

    override fun close() {
        closed[0] = true
    }
}
