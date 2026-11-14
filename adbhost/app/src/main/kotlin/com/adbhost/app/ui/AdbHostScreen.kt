package com.adbhost.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adbhost.app.core.TrustEntry
import com.adbhost.app.engine.AdbServiceController
import com.adbhost.app.engine.ReceivedFile
import com.adbhost.app.engine.SettingsStore
import com.adbhost.app.engine.AuthRequest
import com.adbhost.app.engine.LogLine
import com.adbhost.app.engine.ServiceState
import com.adbhost.app.engine.ShizukuStatus
import com.adbhost.app.platform.ReceivedFileActions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

private val dateTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

@Composable
fun AdbHostScreen(controller: AdbServiceController) {
  val state by controller.state.collectAsStateWithLifecycle()
  val trustEntries by controller.trustEntries.collectAsStateWithLifecycle()
  val logs by controller.logs.collectAsStateWithLifecycle()
  val receivedFiles by controller.receivedFiles.collectAsStateWithLifecycle()
  var pendingAuth by remember { mutableStateOf<AuthRequest?>(null) }

  LaunchedEffect(controller) {
    controller.authorizationRequests.collect { request -> pendingAuth = request }
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(WindowInsets.systemBars.asPaddingValues())
      .padding(horizontal = 16.dp, vertical = 16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    ServiceCard(state = state, onStart = controller::start, onStop = controller::stop)
    ModeCard(controller = controller, settings = controller.settings)
    ReceivedFilesCard(controller = controller, files = receivedFiles, onClear = controller::clearReceivedFiles)
    TrustCard(entries = trustEntries, onRevoke = controller::revokeTrust, onRevokeAll = controller::revokeAllTrust)
    LogCard(logs = logs, onClear = controller::clearLogs)
  }

  pendingAuth?.let { request ->
    LaunchedEffect(request) {
      delay(30_000)
      pendingAuth = null
    }
    AuthorizeDialog(
      request = request,
      onResult = { auth, allowed, persist ->
        auth.respond(allowed, persist)
        pendingAuth = null
      }
    )
  }
}

@Composable
private fun SectionCard(
  title: String,
  trailing: (@Composable () -> Unit)? = null,
  content: @Composable ColumnScope.() -> Unit
) {
  ElevatedCard(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp)
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          title,
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.weight(1f)
        )
        trailing?.invoke()
      }
      Spacer(modifier = Modifier.height(8.dp))
      content()
    }
  }
}

@Composable
private fun ServiceCard(state: ServiceState, onStart: (Int) -> Unit, onStop: () -> Unit) {
  var portText by remember(state.port) {
    mutableStateOf(if (state.port in 3..65535) state.port.toString() else "5555")
  }
  var showPortError by remember { mutableStateOf(false) }
  val port = portText.toIntOrNull()
  val portValid = port != null && port in 3..65535

  SectionCard(
    title = "ADB 服务",
    trailing = {
      Switch(
        checked = state.running,
        onCheckedChange = { checked ->
          if (checked) {
            if (portValid) {
              showPortError = false
              onStart(port)
            } else {
              showPortError = true
            }
          } else {
            onStop()
          }
        }
      )
    }
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      if (state.running) {
        AddressRow(address = "${state.ip}:${state.port}")
        ConnectionHint(connected = state.connectedCount)
      } else {
        PortField(
          value = portText,
          onValueChange = { input ->
            portText = input.filter(Char::isDigit).take(5)
            showPortError = false
          }
        )
        if (showPortError && !portValid) {
          Text(
            "端口需在 3-65535 之间",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
          )
        }
      }
      state.error?.takeIf { it.isNotEmpty() }?.let { error ->
        Text(
          error,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
    }
  }
}

@Composable
private fun AddressRow(address: String) {
  val context = LocalContext.current
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    Box(
      modifier = Modifier
        .size(8.dp)
        .background(color = MaterialTheme.colorScheme.primary, shape = CircleShape)
    )
    Text(
      address,
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.primary,
      modifier = Modifier.clickable {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("adb address", address))
        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
      }
    )
  }
}

@Composable
private fun ConnectionHint(connected: Int) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    Box(
      modifier = Modifier
        .size(8.dp)
        .background(
          color = if (connected > 0) {
            MaterialTheme.colorScheme.primary
          } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
          },
          shape = CircleShape
        )
    )
    Text(
      if (connected > 0) "已连接 $connected 台主机" else "等待主机接入…",
      style = MaterialTheme.typography.bodyMedium,
      color = if (connected > 0) {
        MaterialTheme.colorScheme.onSurface
      } else {
        MaterialTheme.colorScheme.onSurfaceVariant
      }
    )
  }
}

@Composable
private fun PortField(value: String, onValueChange: (String) -> Unit) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    Text(
      "端口",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    OutlinedTextField(
      value = value,
      onValueChange = onValueChange,
      singleLine = true,
      shape = RoundedCornerShape(8.dp),
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
      textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
      modifier = Modifier.width(140.dp),
      colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Color.Transparent,
        unfocusedBorderColor = Color.Transparent,
        disabledBorderColor = Color.Transparent,
        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
        cursorColor = MaterialTheme.colorScheme.primary
      )
    )
  }
}

@Composable
private fun ModeCard(controller: AdbServiceController, settings: SettingsStore) {
  var tlsMode by rememberSaveable { mutableStateOf(settings.tlsMode) }
  var rootMode by rememberSaveable { mutableStateOf(settings.rootMode) }
  var autoStart by rememberSaveable { mutableStateOf(settings.autoStart) }

  SectionCard(title = "模式") {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
      ShizukuRow(controller)
      ToggleRow(
        title = "TLS 加密",
        subtitle = "现代 adb 主机使用;关闭后走经典 RSA 兼容模式",
        checked = tlsMode,
        onCheckedChange = { tlsMode = it; settings.tlsMode = it }
      )
      ToggleRow(
        title = "Root 提权",
        subtitle = "shell 以 root 执行;需设备已 root,是否生效由系统决定",
        checked = rootMode,
        onCheckedChange = { rootMode = it; settings.rootMode = it }
      )
      ToggleRow(
        title = "开机自启",
        subtitle = "设备开机后自动启动 ADB 服务",
        checked = autoStart,
        onCheckedChange = { autoStart = it; settings.autoStart = it }
      )
    }
  }
}

@Composable
private fun ShizukuRow(controller: AdbServiceController) {
  var status by remember { mutableStateOf(controller.shizukuStatus()) }
  val label = when (status) {
    ShizukuStatus.READY -> "已就绪——adb install 静默安装"
    ShizukuStatus.NOT_GRANTED -> "未授权——点击授权"
    ShizukuStatus.NOT_RUNNING -> "Shizuku 未运行"
    ShizukuStatus.NOT_INSTALLED -> "未安装 Shizuku(可选,用于静默安装)"
  }
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable {
        if (status == ShizukuStatus.NOT_GRANTED) {
          controller.requestShizukuPermission()
        }
        status = controller.shizukuStatus()
      },
    verticalAlignment = Alignment.CenterVertically
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(
        "Shizuku 静默安装",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface
      )
      Text(
        label,
        style = MaterialTheme.typography.bodySmall,
        color = if (status == ShizukuStatus.READY) {
          MaterialTheme.colorScheme.primary
        } else {
          MaterialTheme.colorScheme.onSurfaceVariant
        }
      )
    }
    if (status == ShizukuStatus.NOT_GRANTED) {
      TextButton(onClick = {
        controller.requestShizukuPermission()
        status = controller.shizukuStatus()
      }) { Text("授权") }
    }
  }
}

@Composable
private fun ToggleRow(
  title: String,
  subtitle: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(
        title,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface
      )
      Text(
        subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    }
    Switch(checked = checked, onCheckedChange = onCheckedChange)
  }
}


@Composable
private fun ReceivedFilesCard(
  controller: AdbServiceController,
  files: List<ReceivedFile>,
  onClear: () -> Unit
) {
  val context = LocalContext.current
  var pendingClear by remember { mutableStateOf(false) }
  var legacyExport by remember { mutableStateOf<ReceivedFile?>(null) }
  val legacyLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.CreateDocument("*/*")
  ) { uri ->
    val target = legacyExport ?: return@rememberLauncherForActivityResult
    legacyExport = null
    val source = controller.receivedFileAt(target.relativePath)
    if (uri == null || source == null) return@rememberLauncherForActivityResult
    runCatching {
      context.contentResolver.openOutputStream(uri)?.use { out ->
        source.inputStream().use { it.copyTo(out) }
      } ?: throw IllegalStateException("无法写入所选位置")
      Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
    }.onFailure {
      Toast.makeText(context, "保存失败:${it.message}", Toast.LENGTH_SHORT).show()
    }
  }

  fun resolve(file: ReceivedFile) = controller.receivedFileAt(file.relativePath)

  SectionCard(
    title = "接收的文件",
    trailing = {
      if (files.isNotEmpty()) {
        TextButton(onClick = { pendingClear = true }) { Text("清空") }
      }
    }
  ) {
    if (files.isEmpty()) {
      Text(
        "电脑 push 的文件会出现在这里",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    } else {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        files.take(50).forEach { file ->
          Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
              modifier = Modifier
                .weight(1f)
                .clickable {
                  val resolved = resolve(file)
                  if (resolved == null) {
                    Toast.makeText(context, "文件已不存在", Toast.LENGTH_SHORT).show()
                  } else if (!ReceivedFileActions.open(context, resolved)) {
                    Toast.makeText(context, "没有应用能打开此文件", Toast.LENGTH_SHORT).show()
                  }
                }
            ) {
              Text(
                file.relativePath,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
              Text(
                "${humanSize(file.size)} · ${dateTimeFormat.format(Date(file.receivedAt))}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
            TextButton(onClick = {
              val resolved = resolve(file)
              when {
                resolved == null -> Toast.makeText(context, "文件已不存在", Toast.LENGTH_SHORT).show()
                android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q -> {
                  legacyExport = file
                  legacyLauncher.launch(file.relativePath.substringAfterLast('/'))
                }
                else -> ReceivedFileActions.export(context, resolved) { _, message ->
                  Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
              }
            }) { Text("保存") }
            TextButton(onClick = {
              resolve(file)?.let { ReceivedFileActions.share(context, it) }
            }) { Text("分享") }
          }
        }
      }
    }
  }

  if (pendingClear) {
    ClearReceivedDialog(
      onConfirm = {
        onClear()
        pendingClear = false
      },
      onDismiss = { pendingClear = false }
    )
  }
}

private fun humanSize(bytes: Long): String = when {
  bytes >= 1L shl 30 -> "%.1f GB".format(bytes / 1073741824.0)
  bytes >= 1L shl 20 -> "%.1f MB".format(bytes / 1048576.0)
  bytes >= 1L shl 10 -> "%.1f KB".format(bytes / 1024.0)
  else -> "$bytes B"
}

@Composable
private fun TrustCard(entries: List<TrustEntry>, onRevoke: (TrustEntry) -> Unit, onRevokeAll: () -> Unit) {
  var pendingRevoke by remember { mutableStateOf<TrustEntry?>(null) }
  var pendingRevokeAll by remember { mutableStateOf(false) }

  SectionCard(
    title = "已授权主机",
    trailing = {
      if (entries.isNotEmpty()) {
        TextButton(onClick = { pendingRevokeAll = true }) { Text("全部撤销") }
      }
    }
  ) {
    if (entries.isEmpty()) {
      Text(
        "尚无授权主机——电脑连接后在手机上确认指纹",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    } else {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        entries.forEach { entry ->
          Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                entry.name.ifEmpty { "FP ${entry.fingerprint.take(11)}" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
              )
              Text(
                entry.fingerprint,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
              )
              Text(
                dateTimeFormat.format(Date(entry.addedAt)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
            TextButton(onClick = { pendingRevoke = entry }) { Text("撤销") }
          }
        }
      }
    }
  }

  pendingRevoke?.let { entry ->
    RevokeTrustDialog(
      entry = entry,
      onConfirm = { target ->
        onRevoke(target)
        pendingRevoke = null
      },
      onDismiss = { pendingRevoke = null }
    )
  }

  if (pendingRevokeAll) {
    RevokeAllTrustDialog(
      count = entries.size,
      onConfirm = {
        onRevokeAll()
        pendingRevokeAll = false
      },
      onDismiss = { pendingRevokeAll = false }
    )
  }
}

@Composable
private fun LogCard(logs: List<LogLine>, onClear: () -> Unit) {
  val context = LocalContext.current
  SectionCard(
    title = "连接日志",
    trailing = {
      if (logs.isNotEmpty()) {
        Row {
          TextButton(onClick = {
            val text = logs.asReversed().joinToString("\n") { "${timeFormat.format(Date(it.time))}  ${it.text}" }
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("adbhost logs", text))
            Toast.makeText(context, "日志已复制", Toast.LENGTH_SHORT).show()
          }) { Text("复制") }
          TextButton(onClick = onClear) { Text("清空") }
        }
      }
    }
  ) {
    if (logs.isEmpty()) {
      Text(
        "暂无日志",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    } else {
      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        logs.asReversed().take(50).forEach { line ->
          Text(
            "${timeFormat.format(Date(line.time))}  ${line.text}",
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            lineHeight = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }
    }
  }
}

