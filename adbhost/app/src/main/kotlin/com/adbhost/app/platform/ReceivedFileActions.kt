package com.adbhost.app.platform

import android.os.Handler
import android.os.Looper

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import kotlin.concurrent.thread

object ReceivedFileActions {

    fun mimeOf(fileName: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileName.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    fun open(context: Context, file: File): Boolean {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriFor(context, file), mimeOf(file.name))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrElse { false }
    }

    fun share(context: Context, file: File) {
        val intent = Intent(Intent.ACTION_SEND)
            .setType(mimeOf(file.name))
            .putExtra(Intent.EXTRA_STREAM, uriFor(context, file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(intent, "用其他应用打开").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(chooser) }
    }

    fun export(context: Context, file: File, onResult: (Boolean, String) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            onResult(false, "legacy")
            return
        }
        thread(name = "export-${file.name}") {
            val mime = mimeOf(file.name)
            val image = mime.startsWith("image/")
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    if (image) Environment.DIRECTORY_PICTURES + "/AdbHost" else Environment.DIRECTORY_DOWNLOADS + "/AdbHost",
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collection = if (image) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Downloads.EXTERNAL_CONTENT_URI
            }
            val ok = runCatching {
                val uri = context.contentResolver.insert(collection, values)
                    ?: error("insert failed")
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                } ?: error("output stream unavailable")
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                context.contentResolver.update(uri, done, null, null)
            }
            Handler(Looper.getMainLooper()).post {
                onResult(
                    ok.isSuccess,
                    if (ok.isSuccess) {
                        if (image) "已保存到相册 AdbHost" else "已保存到下载 AdbHost"
                    } else {
                        "保存失败:${ok.exceptionOrNull()?.message}"
                    },
                )
            }
        }
    }
}
