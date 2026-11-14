package com.adbhost.app.engine

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.adbhost.app.core.RsaIdentity
import com.adbhost.app.core.TrustEntry
import com.adbhost.app.core.TrustStore
import com.adbhost.app.platform.AdbForegroundService
import java.io.File
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class ServiceState(
    val running: Boolean,
    val port: Int,
    val ip: String,
    val error: String?,
    val connectedCount: Int = 0,
)

data class AuthRequest(val fingerprint: String, val name: String, val kind: String, val respond: (allow: Boolean, persist: Boolean) -> Unit)

data class LogLine(val time: Long, val text: String)

class AdbServiceController(private val context: Context) {

    companion object {
        @Volatile
        var sharedInstance: AdbServiceController? = null
            private set

        private const val MAX_LOG_LINES = 50
        private const val AUTH_TIMEOUT_SECONDS = 30L
        private const val PORT_IN_USE_MESSAGE = "端口被占用,请换端口(如 5556)"
    }

    val settings = SettingsStore(context)

    private val identityDir = File(context.filesDir, "identity")
    private val identity by lazy { RsaIdentity(File(identityDir, "adbkey")) }
    private val trust by lazy { TrustStore(File(context.filesDir, "trusted_hosts")) }
    private val syncRoot: File = (context.getExternalFilesDir(null) ?: File(context.filesDir, "sync"))
        .apply { mkdirs() }

    private var server: AdbServer? = null
    private val lifecycleLock = Any()

    @Volatile
    private var serviceActive = false

    private val stateFlow = MutableStateFlow(ServiceState(false, settings.port, NetworkUtils.localIpv4Text(), null))
    val state: StateFlow<ServiceState> = stateFlow

    val authorizationRequests = MutableSharedFlow<AuthRequest>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val trustFlow = MutableStateFlow<List<TrustEntry>>(emptyList())
    val trustEntries: StateFlow<List<TrustEntry>> = trustFlow

    private val receivedStore by lazy {
        ReceivedFileStore(File(context.filesDir, "received_files.txt"), syncRoot)
    }
    private val receivedFlow = MutableStateFlow<List<ReceivedFile>>(emptyList())
    val receivedFiles: StateFlow<List<ReceivedFile>> = receivedFlow

    private val logsFlow = MutableStateFlow<List<LogLine>>(emptyList())
    val logs: StateFlow<List<LogLine>> = logsFlow

    init {
        sharedInstance = this
        refreshTrustEntries()
        refreshReceivedFiles()
    }

    fun start(port: Int) {
        settings.port = port
        synchronized(lifecycleLock) {
            if (serviceActive) return
            serviceActive = true
        }
        runCatching {
            ContextCompat.startForegroundService(context, AdbForegroundService.startIntent(context))
        }.onFailure {
            serviceActive = false
            stateFlow.value = ServiceState(false, port, NetworkUtils.localIpv4Text(), "前台服务启动失败:${it.message}")
            appendLog("前台服务启动失败:${it.message}")
            return
        }
        thread(name = "adb-server-start", isDaemon = true) {
            try {
                val instance = AdbServer(
                    port = port,
                    identity = identity,
                    trust = trust,
                    identityDir = identityDir,
                    syncRoot = syncRoot,
                    tlsMode = settings::tlsMode,
                    rootMode = settings::rootMode,
                    authorize = ::authorizeBlocking,
                    authorizeTls = ::authorizeCertBlocking,
                    appContext = context,
                    log = ::appendLog,
                    onFileReceived = ::onFileReceived,
                    onConnectionsChanged = ::onConnectionsChanged,
                )
                instance.start()
                server = instance
                stateFlow.value = ServiceState(true, port, NetworkUtils.localIpv4Text(), null)
                appendLog("ADB 服务已启动 ${NetworkUtils.localIpv4Text()}:$port")
            } catch (e: java.net.BindException) {
                serviceActive = false
                stateFlow.value = ServiceState(false, port, NetworkUtils.localIpv4Text(), PORT_IN_USE_MESSAGE)
                appendLog("启动失败:端口 $port 被占用")
            } catch (e: Exception) {
                serviceActive = false
                stateFlow.value = ServiceState(false, port, NetworkUtils.localIpv4Text(), "启动失败:${e.message}")
                appendLog("启动失败:${e.message}")
            }
        }
    }

    fun stop() {
        val instance = synchronized(lifecycleLock) {
            if (!serviceActive) return
            serviceActive = false
            val current = server
            server = null
            current
        }
        instance?.stop()
        stateFlow.value = ServiceState(false, settings.port, NetworkUtils.localIpv4Text(), null)
        appendLog("ADB 服务已停止")
        runCatching { context.stopService(Intent(context, AdbForegroundService::class.java)) }
    }

    fun revokeAllTrust() {
        val entries = runCatching { trust.listEntries() }.getOrDefault(emptyList())
        entries.forEach { entry ->
            if (entry.kind == TrustStore.KIND_CERT) {
                trust.removeCertFingerprint(entry.fingerprint)
            } else {
                trust.removeAdbPublicKey(entry.fingerprint)
            }
        }
        val dropped = server?.disconnectAll() ?: 0
        refreshTrustEntries()
        appendLog("已撤销全部授权(${entries.size} 项)" + if (dropped > 0) ",断开 $dropped 个连接" else "")
    }

    fun clearReceivedFiles() {
        val removed = runCatching { receivedStore.clear() }.getOrDefault(0)
        refreshReceivedFiles()
        appendLog("接收记录已清空($removed 项)")
    }

    private fun onFileReceived(file: File, relativePath: String) {
        runCatching { receivedStore.record(file, relativePath) }
        refreshReceivedFiles()
        appendLog("收到文件 $relativePath")
    }

    private fun onConnectionsChanged(count: Int) {
        stateFlow.update { current ->
            if (current.connectedCount == count) current else current.copy(connectedCount = count)
        }
    }

    private fun refreshReceivedFiles() {
        receivedFlow.value = runCatching { receivedStore.list() }.getOrDefault(emptyList())
    }

    fun receivedFileAt(relativePath: String): File? {
        val candidate = File(syncRoot, relativePath)
        val rootPath = runCatching { syncRoot.canonicalPath }.getOrDefault(syncRoot.absolutePath)
        val resolved = runCatching { candidate.canonicalPath }.getOrDefault(candidate.absolutePath)
        if (!resolved.startsWith(rootPath + File.separator)) return null
        return candidate.takeIf { it.isFile }
    }

    fun revokeTrust(entry: TrustEntry) {
        val removed = when (entry.kind) {
            TrustStore.KIND_CERT -> trust.removeCertFingerprint(entry.fingerprint)
            else -> trust.removeAdbPublicKey(entry.fingerprint)
        }
        val dropped = server?.disconnectFingerprint(entry.fingerprint) ?: 0
        refreshTrustEntries()
        appendLog(
            "撤销信任 ${entry.fingerprint}" +
                (if (removed) "" else "(未找到记录)") +
                (if (dropped > 0) ",已断开 $dropped 个连接" else ""),
        )
    }

    fun shizukuStatus(): ShizukuStatus = ShizukuInstallSupport.status()

    fun requestShizukuPermission() {
        ShizukuInstallSupport.requestPermission()
        appendLog("已请求 Shizuku 授权,请在弹窗中允许")
    }

    fun clearLogs() {
        logsFlow.value = emptyList()
    }

    fun authorizeCertBlocking(fingerprint: String, name: String): Pair<Boolean, Boolean> {
        val granted = authorizeBlocking(fingerprint, name, TrustStore.KIND_CERT)
        val persisted = granted && trust.trustedCertFingerprints().contains(fingerprint)
        return granted to persisted
    }

    private fun authorizeBlocking(fingerprint: String, name: String, kind: String): Boolean {
        val queue = SynchronousQueue<Pair<Boolean, Boolean>>()
        val emitted = runCatching {
            authorizationRequests.tryEmit(
                AuthRequest(fingerprint, name, kind) { allow, persist -> queue.put(allow to persist) },
            )
        }.getOrDefault(false)
        val polled = if (emitted) {
            runCatching { queue.poll(AUTH_TIMEOUT_SECONDS, TimeUnit.SECONDS) }.getOrNull()
        } else {
            null
        }
        var granted = polled?.first == true
        var persist = polled?.second ?: false
        if (polled == null && autoAcceptDebugEffective()) {
            granted = true
            persist = true
            appendLog("已自动允许(debug 构建)$fingerprint")
        }
        if (granted && persist && kind == TrustStore.KIND_CERT) {
            trust.addCertFingerprint(fingerprint, name)
        }
        appendLog("授权${if (granted) "允许" else "拒绝"} kind=$kind name=$name fingerprint=$fingerprint")
        refreshTrustEntries()
        return granted
    }

    private fun autoAcceptDebugEffective(): Boolean =
        settings.autoAcceptDebug &&
            (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private fun refreshTrustEntries() {
        trustFlow.value = runCatching { trust.listEntries() }.getOrDefault(emptyList())
    }

    private fun appendLog(text: String) {
        logsFlow.update { current ->
            val next = current + LogLine(System.currentTimeMillis(), text)
            if (next.size > MAX_LOG_LINES) next.takeLast(MAX_LOG_LINES) else next
        }
    }
}

