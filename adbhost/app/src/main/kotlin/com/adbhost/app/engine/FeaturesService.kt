package com.adbhost.app.engine

import com.adbhost.app.core.AdbCommand
import com.adbhost.app.core.AdbConnection
import com.adbhost.app.core.AdbMessage
import com.adbhost.app.core.ServiceHandler
import java.util.concurrent.atomic.AtomicBoolean

class FeaturesService(
    private val conn: AdbConnection,
    private val remoteId: Long,
    private val peerLocalId: Long,
) : ServiceHandler {

    private val closed = AtomicBoolean(false)

    init {
        Thread({
            runCatching { Thread.sleep(REPLY_DELAY_MS) }
            if (closed.get()) return@Thread
            runCatching {
                conn.sendMessage(
                    AdbMessage(AdbCommand.WRTE, remoteId, peerLocalId, FEATURE_LIST.toByteArray(Charsets.UTF_8)),
                )
                conn.closeService(remoteId)
            }
        }, "features-reply-$remoteId").apply { isDaemon = true }.start()
    }

    override fun onPayload(data: ByteArray) = Unit

    override fun onClosed() {
        close()
    }

    override fun close() {
        closed.set(true)
    }

    companion object {
        const val FEATURE_LIST = "shell_v2,stat_v2"
        private const val REPLY_DELAY_MS = 50L
    }
}
