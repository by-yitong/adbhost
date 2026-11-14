package com.adbhost.app.engine.integration

import com.adbhost.app.core.AdbCommand
import com.adbhost.app.core.AdbMessage
import com.adbhost.app.core.RsaIdentity
import com.adbhost.app.core.ShellProtocol
import com.adbhost.app.core.TrustStore
import com.adbhost.app.core.sha1FingerprintHex
import com.adbhost.app.engine.AdbServer
import com.adbhost.app.engine.DeviceCertificate
import com.adbhost.app.engine.ShellV2Encoder
import com.adbhost.app.engine.ShellV2Framer
import java.io.DataInputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category

interface RealAdbIntegration

@Category(RealAdbIntegration::class)
class RealAdbHostIntegrationTest {

    private val serverLogs = CopyOnWriteArrayList<String>()
    private var server: AdbServer? = null
    private val testRoots = ArrayList<File>()

    @Before
    fun requireIntegrationGate() {
        assumeTrue("run with ADBHOST_INTEGRATION=1", System.getenv("ADBHOST_INTEGRATION") == "1")
    }

    @After
    fun cleanup() {
        server?.stop()
        val killed = runCatching {
            adb(File(System.getProperty("java.io.tmpdir")), "kill-server", timeoutSeconds = 10).code == 0
        }.getOrDefault(false)
        if (!killed) {
            println("[it] kill-server timed out, killing by port $TEST_ADB_SERVER_PORT")
            killAdbServerPort()
        }
        testRoots.forEach { root -> root.deleteRecursively() }
        testRoots.clear()
    }

    @Test
    fun realAdb37TlsHandshakeAuthorizationShellAndFileSync() {
        val home = prepareAdbHome()
        val hostKey = RsaIdentity(File(home, ".android/adbkey"))
        val expectedFingerprint = sha1FingerprintHex(hostKey.keyPair.public.encoded)
        println("[it] expected host fingerprint=$expectedFingerprint")
        val defects = ArrayList<String>()
        val trustDir = tempDir("trust")
        val trust = TrustStore(trustDir)
        val recordedFingerprint = AtomicReference<String?>()
        val recordedName = AtomicReference<String?>()
        val recordedKind = AtomicReference<String?>()
        val allowNext = AtomicBoolean(true)
        val serverRoot = tempDir("server")
        val syncRoot = File(serverRoot, "syncroot").apply { mkdirs() }
        startServer(
            TLS_PORT,
            tls = true,
            trust = trust,
            syncRoot = syncRoot,
            authorize = { fingerprint, name, kind ->
                recordedFingerprint.set(fingerprint)
                recordedName.set(name)
                recordedKind.set(kind)
                val allowed = allowNext.get()
                if (allowed && kind == TrustStore.KIND_CERT) trust.addCertFingerprint(fingerprint)
                println("[it] authorize decision fingerprint=$fingerprint name=$name kind=$kind allowed=$allowed")
                allowed
            },
        )

        val connect = adb(home, "connect", "127.0.0.1:$TLS_PORT", trace = "connect,auth,sockets")
        println("[it] adb connect out=${connect.out.trim()} err=${connect.err.trim()}")
        assertTrue("adb connect should report connected", connect.out.contains("connected to 127.0.0.1:$TLS_PORT"))
        val authDeadline = System.currentTimeMillis() + 10000
        while (recordedFingerprint.get() == null && System.currentTimeMillis() < authDeadline) Thread.sleep(100)
        assertEquals("authorize callback must see the adbkey-derived fingerprint", expectedFingerprint, recordedFingerprint.get())
        assertEquals(TrustStore.KIND_CERT, recordedKind.get())
        println("[it] authorize name=${recordedName.get()}")
        assertTrue(trust.trustedCertFingerprints().contains(expectedFingerprint))
        Thread.sleep(1500)

        val devices = adb(home, "devices")
        println("[it] adb devices:\n${devices.out.trim()}")
        if (!devices.out.lines().any { it.startsWith("127.0.0.1:$TLS_PORT") && it.trim().endsWith("\tdevice") }) {
            defects.add("adb devices must list 127.0.0.1:$TLS_PORT as device after TLS connect (got: ${devices.out.replace("\n", " | ")})")
        }

        allowNext.set(false)
        trust.removeCertFingerprint(expectedFingerprint)
        killAdbServerPort()
        startServerWithTrace(home)
        val rejected = adb(home, "connect", "127.0.0.1:$TLS_PORT", trace = "connect,auth", timeoutSeconds = 10)
        println("[it] revoke-reconnect out=${rejected.out.trim()} err=${rejected.err.trim()}")
        Thread.sleep(1500)
        val devicesAfterRevoke = adb(home, "devices", timeoutSeconds = 10)
        println("[it] devices after revoke:\n${devicesAfterRevoke.out.trim()}")
        if (devicesAfterRevoke.out.lines().any { it.startsWith("127.0.0.1:$TLS_PORT") && it.trim().endsWith("\tdevice") }) {
            defects.add("revoked host still online after revoke+reconnect")
        }
        if (serverLogs.none { it.contains("主机拒绝") || it.contains("rejected") || it.contains("指纹") }) {
            defects.add("server must log the rejected TLS handshake for revoked host")
        }

        allowNext.set(true)
        killAdbServerPort()
        startServerWithTrace(home)
        val reconnected = adb(home, "connect", "127.0.0.1:$TLS_PORT", timeoutSeconds = 10)
        println("[it] re-grant reconnect out=${reconnected.out.trim()}")
        Thread.sleep(1500)
        val devicesReGranted = adb(home, "devices", timeoutSeconds = 10)
        println("[it] devices after re-grant:\n${devicesReGranted.out.trim()}")
        if (!devicesReGranted.out.lines().any { it.startsWith("127.0.0.1:$TLS_PORT") && it.trim().endsWith("\tdevice") }) {
            defects.add("re-granted host must be online again (got: ${devicesReGranted.out.replace("\n", " | ")})")
        }
        assertTrue(serverLogs.any { it.contains("TLS 已升级 fingerprint=$expectedFingerprint") })

        val source = File(tempDir("data"), "push_src.txt")
        source.writeText(buildString {
            repeat(8192) { index -> append("line-$index-ABCDEFGHIJKLMNOPQRSTUVWXYZ-abcdefghijklmnopqrstuvwxyz\n") }
        })
        assertTrue("test payload must exceed MAX_PAYLOAD", source.length() > 256 * 1024)

        val pushTmp = adb(home, "-s", "127.0.0.1:$TLS_PORT", "push", source.absolutePath, "/data/local/tmp/it_push.txt", timeoutSeconds = 10, trace = "sync")
        println("[it] push /data/local/tmp out=${pushTmp.out.trim()} code=${pushTmp.code}")
        val pushedTmp = File(syncRoot, "tmp/it_push.txt")
        if (pushTmp.code != 0 || !pushedTmp.exists() || pushedTmp.readBytes().toList() != source.readBytes().toList()) {
            defects.add("adb push /data/local/tmp: code=${pushTmp.code} fileExists=${pushedTmp.exists()}")
        }

        val pushSd = adb(home, "-s", "127.0.0.1:$TLS_PORT", "push", source.absolutePath, "/sdcard/it_sd.txt", timeoutSeconds = 10)
        println("[it] push /sdcard out=${pushSd.out.trim()} code=${pushSd.code}")
        val pushedSd = File(syncRoot, "it_sd.txt")
        if (pushSd.code != 0 || !pushedSd.exists() || pushedSd.readBytes().toList() != source.readBytes().toList()) {
            defects.add("adb push /sdcard: code=${pushSd.code} fileExists=${pushedSd.exists()}")
        }

        val pullTarget = File(tempDir("data"), "pulled.txt")
        val pull = adb(home, "-s", "127.0.0.1:$TLS_PORT", "pull", "/data/local/tmp/it_push.txt", pullTarget.absolutePath, timeoutSeconds = 10)
        println("[it] pull out=${pull.out.trim()} code=${pull.code}")
        if (pull.code != 0 || !pullTarget.exists() || pullTarget.readBytes().toList() != source.readBytes().toList()) {
            defects.add("adb pull: code=${pull.code} fileExists=${pullTarget.exists()}")
        }

        val localUser = ProcessBuilder("id", "-un").start().inputStream.readBytes().toString(Charsets.UTF_8).trim()
        val whoami = adb(home, "-s", "127.0.0.1:$TLS_PORT", "shell", "whoami", timeoutSeconds = 10)
        println("[it] shell whoami out=${whoami.out.trim()} code=${whoami.code}")
        if (whoami.code != 0 || whoami.out.trim() != localUser) {
            defects.add("adb shell whoami: code=${whoami.code} out=${whoami.out.trim()} expected=$localUser")
        }
        val echo = adb(home, "-s", "127.0.0.1:$TLS_PORT", "shell", "echo hello_from_adbhost37", timeoutSeconds = 10)
        println("[it] shell echo out=${echo.out.trim()} code=${echo.code}")
        if (echo.code != 0 || echo.out.trim() != "hello_from_adbhost37") {
            defects.add("adb shell echo: code=${echo.code} out=${echo.out.trim()}")
        }

        assertTrue("collected defects: $defects", defects.isEmpty())
    }

    @Test
    fun rawJvmTlsClientCompletesCnxnStlsHandshakeAndShellV2() {
        val trust = TrustStore(tempDir("trust"))
        val acceptedFingerprint = AtomicReference<String?>()
        val serverRoot = tempDir("server")
        startServer(
            RAW_PORT,
            tls = true,
            trust = trust,
            syncRoot = File(serverRoot, "syncroot").apply { mkdirs() },
            authorize = { fingerprint, _, _ ->
                acceptedFingerprint.set(fingerprint)
                true
            },
        )

        val raw = Socket()
        raw.connect(InetSocketAddress("127.0.0.1", RAW_PORT), 5000)
        raw.soTimeout = 15000
        val output = raw.getOutputStream()
        val input = DataInputStream(raw.getInputStream())

        AdbMessage(AdbCommand.CNXN, VERSION, AdbMessage.MAX_PAYLOAD.toLong(), "host::features=shell_v2,stat_v2".toByteArray())
            .also { it.encode(output) }
        output.flush()

        val first = readRawMessage(input)
        assertEquals("first reply must be CNXN", AdbCommand.CNXN, first.command)
        assertEquals("magic must invert command", first.command xor 0xFFFFFFFFL, first.magic)
        assertEquals("checksum must be payload sum", first.payload.fold(0L) { acc, byte -> acc + (byte.toLong() and 0xFF) } and 0xFFFFFFFFL, first.checksum)
        assertEquals("device maxPayload must be 256KiB", AdbMessage.MAX_PAYLOAD.toLong(), first.arg1)
        val banner = first.payload.toString(Charsets.UTF_8)
        println("[it] raw bytes CNXN arg0=${first.arg0} arg1=${first.arg1} banner=$banner")
        assertTrue(banner.startsWith("device::ro.product.name=adbhost"))
        assertTrue(banner.contains("features=shell_v2,stat_v2"))

        val second = readRawMessage(input)
        assertEquals("second message must be A_STLS", AdbCommand.STLS, second.command)
        assertEquals(0x01000000L, second.arg0)
        assertEquals(0, second.payload.size)
        println("[it] raw bytes STLS arg0=${second.arg0} arg1=${second.arg1} len=${second.payload.size}")

        AdbMessage(AdbCommand.STLS, 0x01000000L, 0L, ByteArray(0)).encode(output)
        output.flush()

        val clientIdentity = RsaIdentity(File(tempDir("client"), "client_key"))
        val clientCertificate = DeviceCertificate.loadOrCreate(tempDir("clientcert"), clientIdentity)
        val tls = tlsClientOver(raw, clientIdentity, clientCertificate)
        tls.startHandshake()
        println("[it] TLS1.3 negotiated protocol=${tls.session.protocol} cipher=${tls.session.cipherSuite}")
        assertEquals("TLSv1.3", tls.session.protocol)

        AdbMessage(AdbCommand.CNXN, VERSION, AdbMessage.MAX_PAYLOAD.toLong(), "host::features=shell_v2,stat_v2".toByteArray())
            .encode(tls.outputStream)
        tls.outputStream.flush()
        val inTlsCnxn = AdbMessage.read(tls.inputStream, AdbMessage.MAX_PAYLOAD)
        assertEquals("server must reply CNXN inside TLS", AdbCommand.CNXN, checkNotNull(inTlsCnxn).command)
        assertTrue(inTlsCnxn.payload.toString(Charsets.UTF_8).startsWith("device::"))

        val deadline = System.currentTimeMillis() + 5000
        while (acceptedFingerprint.get() == null && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertEquals(
            "server must observe our certificate fingerprint",
            sha1FingerprintHex(clientCertificate.publicKey.encoded),
            acceptedFingerprint.get(),
        )

        AdbMessage(AdbCommand.OPEN, 100L, 0L, "shell,v2,raw:echo ping-raw-42".toByteArray()).encode(tls.outputStream)
        tls.outputStream.flush()
        val okay = AdbMessage.read(tls.inputStream, AdbMessage.MAX_PAYLOAD)
        assertEquals(AdbCommand.OKAY, checkNotNull(okay).command)
        assertEquals(100L, okay.arg1)
        val remoteId = okay.arg0
        assertTrue(remoteId > 0)

        val framer = ShellV2Framer()
        var stdoutPayload: ByteArray? = null
        var exitCode: Int? = null
        var closedByServer = false
        while (!closedByServer) {
            val message = AdbMessage.read(tls.inputStream, AdbMessage.MAX_PAYLOAD) ?: break
            when (message.command) {
                AdbCommand.OKAY -> Unit
                AdbCommand.WRTE -> {
                    framer.feed(message.payload).forEach { frame ->
                        println("[it] shell v2 frame stream=${frame.streamId} payload=${frame.payload.toString(Charsets.UTF_8)}")
                        when (frame.streamId) {
                            ShellProtocol.STDOUT -> stdoutPayload = frame.payload
                            ShellProtocol.EXIT -> exitCode = readExitCode(frame.payload)
                        }
                    }
                }
                AdbCommand.CLSE -> closedByServer = true
            }
        }
        assertEquals("shell v2 EXIT frame must carry code 0", 0, exitCode)
        assertTrue("server must CLSE after process exit", closedByServer)
        assertEquals(
            "shell v2 STDOUT frame must carry command output (pump threads not started at ShellService.kt:153-154?)",
            "ping-raw-42\n",
            stdoutPayload?.toString(Charsets.UTF_8),
        )
        tls.close()
        raw.close()
    }

    @Test
    fun classicRsaModeAgainstRealAdb37() {
        val home = prepareAdbHome()
        val trust = TrustStore(tempDir("trust"))
        val publicKeyLine = File(home, ".android/adbkey.pub").readText().trim()
        trust.addAdbPublicKey(publicKeyLine)
        println("[it] classic trust preloaded adbkey.pub=${publicKeyLine.take(40)}...")
        val serverRoot = tempDir("server")
        startServer(
            CLASSIC_PORT,
            tls = false,
            trust = trust,
            syncRoot = File(serverRoot, "syncroot").apply { mkdirs() },
            authorize = { _, _, _ -> true },
        )

        val connect = adb(home, "connect", "127.0.0.1:$CLASSIC_PORT", trace = "connect,auth", timeoutSeconds = 10)
        println("[it] classic connect out=${connect.out.trim()} err=${connect.err.trim()}")
        Thread.sleep(1000)
        val devices = adb(home, "devices")
        println("[it] classic devices:\n${devices.out.trim()}")
        val online = devices.out.lines().any { it.startsWith("127.0.0.1:$CLASSIC_PORT") && it.trim().endsWith("\tdevice") }
        val authEvidence = serverLogs.any { it.contains("authorized via user consent") || it.contains("host connected banner=") }
        if (online) {
            if (!authEvidence) {
                println("[it] PROTOCOL VIOLATION: device reported online without any AUTH exchange; server observed: ${serverLogs.joinToString(" | ")}")
            }
            val shell = adb(home, "-s", "127.0.0.1:$CLASSIC_PORT", "shell", "echo classic_ok", timeoutSeconds = 10)
            println("[it] classic shell out=${shell.out.trim()} code=${shell.code}")
            if (!authEvidence) {
                assertTrue("classic online must follow AUTH exchange (TOKEN/SIGNATURE) before CNXN", false)
            }
            assertEquals(0, shell.code)
            assertEquals("classic_ok", shell.out.trim())
        } else {
            println(
                "[it] classic mode not completed against adb37 host (TLS-first client); " +
                    "server observed: ${serverLogs.joinToString(" | ")}",
            )
        }
        assertTrue(
            "server must have logged the host CNXN handshake at minimum",
            serverLogs.any { it.contains("主机接入") },
        )
    }

    private fun startServer(
        port: Int,
        tls: Boolean,
        trust: TrustStore,
        syncRoot: File,
        authorize: (String, String, String) -> Boolean,
        authorizeTls: (String, String) -> Pair<Boolean, Boolean> = { fp, name ->
            val allowed = authorize(fp, name, TrustStore.KIND_CERT)
            val persisted = allowed && trust.trustedCertFingerprints().contains(fp)
            allowed to persisted
        },
    ) {
        val root = tempDir("identity")
        val instance = AdbServer(
            port = port,
            identity = RsaIdentity(File(root, "device_adbkey")),
            trust = trust,
            identityDir = File(root, "devicecert"),
            syncRoot = syncRoot,
            tlsMode = { tls },
            rootMode = { false },
            authorize = authorize,
            authorizeTls = authorizeTls,
            log = { message ->
                serverLogs.add(message)
                println("[server] $message")
            },
        )
        instance.start()
        server = instance
        Thread.sleep(300)
    }

    private fun prepareAdbHome(): File {
        val home = tempDir("adbhome")
        adb(home, "kill-server")
        val started = startServerWithTrace(home)
        println("[it] adb start-server code=${started.code} out=${started.out.trim()}")
        val publicKey = File(home, ".android/adbkey.pub")
        val deadline = System.currentTimeMillis() + 15000
        while (!publicKey.exists() && System.currentTimeMillis() < deadline) Thread.sleep(200)
        assertTrue("adb server must generate ~/.android/adbkey under test HOME", publicKey.exists())
        return home
    }

    private fun killAdbServerPort() {
        runCatching {
            File("/tmp/adbsrv.log").takeIf { it.exists() }?.let { log ->
                println("[it] ===== adb server trace tail =====")
                log.readText().takeLast(6000).lines().forEach { if (it.isNotBlank()) println("[srv] $it") }
            }
        }
        runCatching {
            val probe = ProcessBuilder("bash", "-c", "ss -tlnp | grep ':$TEST_ADB_SERVER_PORT ' || true").start()
            val listing = probe.inputStream.readBytes().toString(Charsets.UTF_8)
            probe.waitFor(5, TimeUnit.SECONDS)
            Regex("pid=(\\d+)").findAll(listing).forEach { match ->
                val pid = match.groupValues[1]
                runCatching { ProcessBuilder("kill", "-9", pid).start().waitFor(3, TimeUnit.SECONDS) }
                println("[it] killed wedged adb server pid=$pid on port $TEST_ADB_SERVER_PORT")
            }
        }
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            val probe = ProcessBuilder("bash", "-c", "ss -tln | grep ':$TEST_ADB_SERVER_PORT ' || true").start()
            val remaining = probe.inputStream.readBytes().toString(Charsets.UTF_8)
            probe.waitFor(5, TimeUnit.SECONDS)
            if (remaining.isBlank()) return
            Thread.sleep(200)
        }
        println("[it] port $TEST_ADB_SERVER_PORT still busy after kill")
    }

    private fun startServerWithTrace(home: File): AdbResult {
        val traceFile = File("/tmp/adbsrv.log")
        traceFile.delete()
        val process = ProcessBuilder(adbBinary(), "-P", TEST_ADB_SERVER_PORT.toString(), "nodaemon", "server").apply {
            environment()["HOME"] = home.absolutePath
            environment()["ADB_TRACE"] = "all"
            redirectErrorStream(true)
            redirectOutput(traceFile)
        }.start()
        val deadline = System.currentTimeMillis() + 10000
        while (!File(home, ".android/adbkey").exists() && System.currentTimeMillis() < deadline) Thread.sleep(200)
        Thread.sleep(500)
        return AdbResult(0, "traced server started", "")
    }

    private fun adb(home: File, vararg args: String, trace: String? = null, timeoutSeconds: Long = 30): AdbResult {
        val command = mutableListOf(adbBinary(), "-P", TEST_ADB_SERVER_PORT.toString())
        command.addAll(args)
        val process = ProcessBuilder(command).apply {
            environment()["HOME"] = home.absolutePath
            if (trace != null) environment()["ADB_TRACE"] = trace
            redirectErrorStream(true)
        }.start()
        val buffer = java.io.ByteArrayOutputStream()
        val reader = Thread { runCatching { process.inputStream.copyTo(buffer) } }
        reader.isDaemon = true
        reader.start()
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            reader.join(2000)
            val partial = buffer.toString(Charsets.UTF_8).trim()
            println("[it] adb ${args.joinToString(" ")} TIMED OUT after ${timeoutSeconds}s partial=$partial")
            return AdbResult(-1, partial, "TIMEOUT")
        }
        reader.join(5000)
        return AdbResult(process.exitValue(), buffer.toString(Charsets.UTF_8), "")
    }

    private fun adbBinary(): String =
        listOf(
            System.getenv("ANDROID_HOME")?.let { File(it, "platform-tools/adb") },
            File("/home/admins/Android/Sdk/platform-tools/adb"),
        ).firstOrNull { it != null && it.canExecute() }?.absolutePath ?: "adb"

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory("adbhost-it-$prefix").toFile().also { testRoots.add(it) }

    private fun waitForFile(target: File): File {
        val deadline = System.currentTimeMillis() + 5000
        while (!target.exists() && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertTrue("expected sync file $target", target.exists())
        return target
    }

    private fun readRawMessage(input: DataInputStream): RawAdbMessage {
        val header = ByteArray(24)
        input.readFully(header)
        fun le32(offset: Int): Long {
            var value = 0L
            for (index in 3 downTo 0) value = (value shl 8) or (header[offset + index].toLong() and 0xFF)
            return value
        }
        val payload = ByteArray(le32(12).toInt())
        input.readFully(payload)
        return RawAdbMessage(le32(0), le32(4), le32(8), payload, le32(16), le32(20))
    }

    private fun tlsClientOver(raw: Socket, identity: RsaIdentity, certificate: X509Certificate): SSLSocket {
        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(null, "client".toCharArray())
        keyStore.setKeyEntry("client", identity.keyPair.private, "client".toCharArray(), arrayOf(certificate))
        val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        factory.init(keyStore, "client".toCharArray())
        val context = SSLContext.getInstance("TLS")
        context.init(factory.keyManagers, arrayOf(TRUST_ALL), null)
        val ssl = context.socketFactory.createSocket(raw, raw.inetAddress.hostAddress, raw.port, true) as SSLSocket
        ssl.useClientMode = true
        if (ssl.enabledProtocols.contains("TLSv1.3")) ssl.enabledProtocols = arrayOf("TLSv1.3")
        return ssl
    }

    private fun readExitCode(payload: ByteArray): Int {
        var value = 0L
        for (index in 3 downTo 0) value = (value shl 8) or (payload[index].toLong() and 0xFF)
        return value.toInt()
    }

    private class RawAdbMessage(
        val command: Long,
        val arg0: Long,
        val arg1: Long,
        val payload: ByteArray,
        val checksum: Long,
        val magic: Long,
    )

    private class AdbResult(val code: Int, val out: String, val err: String)

    private object TRUST_ALL : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    companion object {
        private const val TEST_ADB_SERVER_PORT = 5039
        private const val TLS_PORT = 9091
        private const val RAW_PORT = 9092
        private const val CLASSIC_PORT = 9093
        private const val VERSION = 0x01000001L
    }
}
