package com.adbhost.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Checkbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adbhost.app.core.TrustEntry
import com.adbhost.app.engine.AuthRequest

internal fun formatFingerprint(fingerprint: String): String =
  fingerprint.replace(":", "").chunked(2).chunked(8).joinToString("\n") { line -> line.joinToString(":") }

@Composable
fun AuthorizeDialog(request: AuthRequest, onResult: (AuthRequest, Boolean, Boolean) -> Unit) {
  var rememberHost by remember { mutableStateOf(true) }
  AlertDialog(
    onDismissRequest = { onResult(request, false, false) },
    title = { Text("允许调试连接?", style = MaterialTheme.typography.titleLarge) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          "${request.name} 请求连接到本设备的 ADB 服务",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface
        )
        Text(
          "指纹",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
          formatFingerprint(request.fingerprint),
          fontFamily = FontFamily.Monospace,
          fontSize = 11.sp,
          lineHeight = 16.sp,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
          Checkbox(checked = rememberHost, onCheckedChange = { rememberHost = it })
          Text(
            "记住该主机",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
          )
        }
      }
    },
    dismissButton = {
      TextButton(onClick = { onResult(request, false, false) }) { Text("拒绝") }
    },
    confirmButton = {
      TextButton(onClick = { onResult(request, true, rememberHost) }) { Text("允许") }
    }
  )
}

@Composable
fun RevokeTrustDialog(entry: TrustEntry, onConfirm: (TrustEntry) -> Unit, onDismiss: () -> Unit) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("撤销授权?") },
    text = {
      Text(
        "撤销后,${entry.name} 需重新授权才能连接。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface
      )
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("取消") }
    },
    confirmButton = {
      TextButton(onClick = { onConfirm(entry) }) {
        Text("撤销", color = MaterialTheme.colorScheme.error)
      }
    }
  )
}

@Composable
fun RevokeAllTrustDialog(count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("撤销全部授权?") },
    text = {
      Text(
        "将清除 $count 项授权记录并断开所有已连接的电脑,它们需重新授权才能连接。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface
      )
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("取消") }
    },
    confirmButton = {
      TextButton(onClick = onConfirm) {
        Text("全部撤销", color = MaterialTheme.colorScheme.error)
      }
    }
  )
}

@Composable
fun ClearReceivedDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("清空接收的文件?") },
    text = {
      Text(
        "将删除接收记录和已接收的文件本体,需要保留的请先保存到下载或相册。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface
      )
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("取消") }
    },
    confirmButton = {
      TextButton(onClick = onConfirm) {
        Text("清空", color = MaterialTheme.colorScheme.error)
      }
    }
  )
}
