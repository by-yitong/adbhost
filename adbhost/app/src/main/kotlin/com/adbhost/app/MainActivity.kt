package com.adbhost.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.adbhost.app.engine.AdbServiceController
import com.adbhost.app.ui.AdbHostScreen
import com.adbhost.app.ui.theme.AdbHostTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      AdbHostTheme {
        Surface(
          modifier = Modifier.fillMaxSize(),
          color = MaterialTheme.colorScheme.background
        ) {
          val controller = AdbServiceController.sharedInstance ?: AdbServiceController(applicationContext)
          NotificationPermissionEffect()
          AdbHostScreen(controller = controller)
        }
      }
    }
  }
}

@Composable
private fun NotificationPermissionEffect() {
  val context = LocalContext.current
  val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
  LaunchedEffect(Unit) {
    if (
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
      context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
      launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
  }
}
