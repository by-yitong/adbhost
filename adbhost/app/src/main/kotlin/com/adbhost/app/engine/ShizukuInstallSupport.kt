package com.adbhost.app.engine

import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper

enum class ShizukuStatus { READY, NOT_GRANTED, NOT_RUNNING, NOT_INSTALLED }

object ShizukuInstallSupport {

    fun status(): ShizukuStatus {
        val binder = runCatching { Shizuku.getBinder() }.getOrNull() ?: return ShizukuStatus.NOT_INSTALLED
        if (!binder.isBinderAlive || !runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            return ShizukuStatus.NOT_RUNNING
        }
        val granted = runCatching {
            IShizukuService.Stub.asInterface(ShizukuBinderWrapper(binder)).checkSelfPermission()
        }.getOrDefault(false)
        return if (granted) ShizukuStatus.READY else ShizukuStatus.NOT_GRANTED
    }

    fun requestPermission() {
        runCatching { Shizuku.requestPermission(0) }
    }

    fun install(apk: java.io.File, allowTest: Boolean, allowDowngrade: Boolean, grantRuntimePermissions: Boolean): InstallOutcome {
        val binder = Shizuku.getBinder() ?: return InstallOutcome(false, "Failure [Shizuku binder 不可用]")
        val service = IShizukuService.Stub.asInterface(ShizukuBinderWrapper(binder))
        val argv = buildList {
            add("pm")
            add("install")
            if (allowTest) add("-t")
            if (allowDowngrade) add("-d")
            if (grantRuntimePermissions) add("-g")
            add("-S")
            add(apk.length().toString())
        }
        val remote = service.newProcess(argv.toTypedArray(), null, null)
        android.os.ParcelFileDescriptor.AutoCloseOutputStream(remote.outputStream).use { out ->
            apk.inputStream().use { it.copyTo(out) }
        }
        val output = android.os.ParcelFileDescriptor.AutoCloseInputStream(remote.inputStream).bufferedReader().readText().trim()
        val error = runCatching {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(remote.errorStream).bufferedReader().readText().trim()
        }.getOrDefault("")
        val code = remote.waitFor()
        return when {
            code == 0 && output.contains("Success") -> InstallOutcome(true, "Success")
            output.isNotBlank() -> InstallOutcome(false, output)
            error.isNotBlank() -> InstallOutcome(false, error)
            else -> InstallOutcome(false, "pm install exited with $code")
        }
    }
}

data class InstallOutcome(val success: Boolean, val message: String)
