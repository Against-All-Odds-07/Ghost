package com.example

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import com.google.accompanist.permissions.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Power
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.device.ScreenStreamManager
import com.example.ui.RemoteControlViewModel
import com.example.ui.ServerState
import com.example.ui.theme.*

class MainActivity : ComponentActivity() {
  private val viewModel: RemoteControlViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      MyApplicationTheme {
        Scaffold(
          modifier = Modifier.fillMaxSize().testTag("main_screen"),
          containerColor = MaterialTheme.colorScheme.background
        ) { innerPadding ->
          RemoteControlScreen(
            viewModel = viewModel,
            modifier = Modifier.padding(innerPadding)
          )
        }
      }
    }
  }

  override fun onResume() {
    super.onResume()
    viewModel.refreshSettingsState()
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteControlScreen(viewModel: RemoteControlViewModel, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val serverState by viewModel.serverState.collectAsStateWithLifecycle()
  val tailscaleIp by viewModel.tailscaleIp.collectAsStateWithLifecycle()
  val localIp by viewModel.localIp.collectAsStateWithLifecycle()
  val pairingCode by viewModel.pairingCode.collectAsStateWithLifecycle()
  val deviceInfo by viewModel.deviceInfo.collectAsStateWithLifecycle()
  val pairedClients by viewModel.pairedClients.collectAsStateWithLifecycle()
  val bootStartEnabled by viewModel.bootStartEnabled.collectAsStateWithLifecycle()
  val isBatteryOptimized by viewModel.isBatteryOptimizationIgnored.collectAsStateWithLifecycle()

  val isServerRunning by remember { derivedStateOf { serverState is ServerState.Running } }
  val isServerStoppedOrError by remember { derivedStateOf { serverState is ServerState.Stopped || serverState is ServerState.Error } }

  val isProjectionActive by ScreenStreamManager.isProjectionActive.collectAsStateWithLifecycle()
  val mediaProjectionManager = remember {
    context.getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
  }

  val screenCaptureLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.StartActivityForResult()
  ) { result ->
    if (result.resultCode == Activity.RESULT_OK && result.data != null) {
      val dm = context.resources.displayMetrics
      ScreenStreamManager.startProjection(
        context = context,
        resultCode = result.resultCode,
        data = result.data!!,
        screenWidth = dm.widthPixels,
        screenHeight = dm.heightPixels,
        densityDpi = dm.densityDpi
      )
    }
  }

  var showSettingsSheet by remember { mutableStateOf(false) }

  // Permission launcher for Android 13+ Notifications
  var hasNotificationPermission by remember {
    mutableStateOf(
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
          context,
          Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
      } else {
        true
      }
    )
  }

  val notificationPermissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission(),
    onResult = { isGranted ->
      hasNotificationPermission = isGranted
    }
  )

  Column(
    modifier = modifier.fillMaxSize()
  ) {
    // Header
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 24.dp, vertical = 20.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.weight(1f)
      ) {
        Box(
          modifier = Modifier
            .size(40.dp)
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)),
          contentAlignment = Alignment.Center
        ) {
          Icon(
            imageVector = Icons.Outlined.PhoneAndroid,
            contentDescription = "Ghost App Icon",
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(24.dp)
          )
        }
        Text(
          text = "Ghost",
          fontSize = 20.sp,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onBackground
        )
      }
      
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IconButton(
          onClick = { viewModel.detectIps() },
          modifier = Modifier
            .size(44.dp)
            .testTag("refresh_ip_button")
            .background(MaterialTheme.colorScheme.secondary, CircleShape)
        ) {
          Icon(
            imageVector = Icons.Filled.Refresh,
            contentDescription = "Refresh Network and IP",
            tint = MaterialTheme.colorScheme.onSecondary,
            modifier = Modifier.size(20.dp)
          )
        }

        IconButton(
          onClick = { showSettingsSheet = true },
          modifier = Modifier
            .size(44.dp)
            .testTag("open_settings_button")
            .background(MaterialTheme.colorScheme.secondary, CircleShape)
        ) {
          Icon(
            imageVector = Icons.Filled.Settings,
            contentDescription = "Settings and Boot Persistence",
            tint = MaterialTheme.colorScheme.onSecondary,
            modifier = Modifier.size(20.dp)
          )
        }
      }
    }

    // Body
    Column(
      modifier = Modifier
        .weight(1f)
        .padding(horizontal = 16.dp)
        .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
      // Connection Details Card
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .testTag("ip_display_card")
          .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(32.dp))
          .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(32.dp))
          .padding(24.dp)
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            // Pulsing dot
            val infiniteTransition = rememberInfiniteTransition(label = "pulse")
            val alpha by infiniteTransition.animateFloat(
              initialValue = 0.3f,
              targetValue = 1f,
              animationSpec = infiniteRepeatable(
                animation = tween(1000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
              ),
              label = "pulseAlpha"
            )
            
            Box(
              modifier = Modifier
                .size(8.dp)
                .background(if (isServerRunning) MaterialTheme.colorScheme.tertiary.copy(alpha = alpha) else Color.Gray, CircleShape)
            )
            Text(
              text = if (isServerRunning) "SERVER ACTIVE" else "SERVER STOPPED",
              fontSize = 14.sp,
              fontWeight = FontWeight.Medium,
              color = if (isServerRunning) MaterialTheme.colorScheme.tertiary else Color.Gray,
              letterSpacing = 1.sp
            )
          }

          if (bootStartEnabled) {
            Surface(
              shape = RoundedCornerShape(8.dp),
              color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
            ) {
              Text(
                text = "AUTO-BOOT ON",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
              )
            }
          }
        }
        
        Text(
          text = if (tailscaleIp != null) "Listening on Tailscale mesh VPN" else "Listening on Local Network interface",
          fontSize = 12.sp,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
        )

        // Tailscale IP Block
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background, RoundedCornerShape(16.dp))
            .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
            .padding(16.dp)
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text = if (tailscaleIp != null) "TAILSCALE IP" else "LOCAL LAN IP",
              fontSize = 11.sp,
              fontWeight = FontWeight.Bold,
              color = Muted
            )
            Text(
              text = deviceInfo.networkType,
              fontSize = 11.sp,
              fontWeight = FontWeight.Medium,
              color = MaterialTheme.colorScheme.primary
            )
          }
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = tailscaleIp ?: localIp ?: "127.0.0.1",
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onBackground
          )
        }

        Spacer(modifier = Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          Row(
            modifier = Modifier
              .weight(1f)
              .background(MaterialTheme.colorScheme.secondary, RoundedCornerShape(12.dp))
              .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text("Port", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSecondary.copy(alpha = 0.6f))
            Text("8765", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondary)
          }
          Row(
            modifier = Modifier
              .weight(1f)
              .background(MaterialTheme.colorScheme.secondary, RoundedCornerShape(12.dp))
              .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text("Protocol", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSecondary.copy(alpha = 0.6f))
            Text("WS / JSON", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondary)
          }
        }
      }

      // Pairing Card
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .testTag("pairing_code_card")
          .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(32.dp))
          .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = "STATIC PAIRING PIN",
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
            letterSpacing = 1.5.sp
          )
          IconButton(
            onClick = { showSettingsSheet = true },
            modifier = Modifier.size(32.dp).testTag("edit_pairing_code_button")
          ) {
            Icon(
              imageVector = Icons.Filled.Edit,
              contentDescription = "Edit Custom PIN in Settings",
              tint = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
              modifier = Modifier.size(18.dp)
            )
          }
        }
        
        Spacer(modifier = Modifier.height(14.dp))
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp),
          horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          pairingCode.forEach { char ->
            Box(
              modifier = Modifier
                .weight(1f)
                .height(56.dp)
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                .border(1.dp, MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.25f), RoundedCornerShape(12.dp)),
              contentAlignment = Alignment.Center
            ) {
              Text(
                text = char.toString(),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary
              )
            }
          }
        }
        
        Text(
          text = "Enter this static PIN in the Ghost client, or customize your PIN anytime in Settings (⚙️).",
          fontSize = 12.sp,
          color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f),
          textAlign = androidx.compose.ui.text.style.TextAlign.Center,
          lineHeight = 17.sp,
          modifier = Modifier.padding(horizontal = 8.dp)
        )

        if (pairedClients.isNotEmpty()) {
          Spacer(modifier = Modifier.height(10.dp))
          Text(
            text = "Paired Devices: ${pairedClients.size} registered",
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f)
          )
        }
      }

      // 60 FPS Ultra Screen Mirroring Card
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .testTag("screen_mirror_card")
          .background(
            if (isProjectionActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            RoundedCornerShape(24.dp)
          )
          .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(
          horizontalArrangement = Arrangement.spacedBy(14.dp),
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.weight(1f)
        ) {
          Box(
            modifier = Modifier
              .size(44.dp)
              .background(
                if (isProjectionActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                CircleShape
              ),
            contentAlignment = Alignment.Center
          ) {
            Icon(
              imageVector = Icons.Outlined.Cast,
              contentDescription = "Screen Mirroring Cast Icon",
              tint = if (isProjectionActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(24.dp)
            )
          }
          Column {
            Text(
              text = "60 FPS Live Mirroring",
              fontSize = 15.sp,
              fontWeight = FontWeight.Bold,
              color = if (isProjectionActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
              text = if (isProjectionActive) "Hardware Zero-Latency Stream Active" else "Tap to enable 60 FPS ultra stream",
              fontSize = 11.sp,
              color = if (isProjectionActive) MaterialTheme.colorScheme.primary else Muted
            )
          }
        }
        FilledTonalButton(
          onClick = {
            if (isProjectionActive) {
              ScreenStreamManager.stopProjection()
            } else {
              mediaProjectionManager?.createScreenCaptureIntent()?.let {
                screenCaptureLauncher.launch(it)
              }
            }
          },
          shape = RoundedCornerShape(14.dp),
          colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = if (isProjectionActive) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primary,
            contentColor = if (isProjectionActive) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimary
          ),
          contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
        ) {
          Text(if (isProjectionActive) "Stop" else "Enable", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
      }

      // Stats Grid
      Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        // Battery
        Column(
          modifier = Modifier
            .weight(1f)
            .testTag("battery_card")
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(24.dp))
            .padding(16.dp)
        ) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
            Icon(
              imageVector = Icons.Outlined.BatteryFull,
              contentDescription = "Battery Status Icon",
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(16.dp)
            )
            Text("Battery", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
          Text(
            text = "${deviceInfo.batteryLevel}%",
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground
          )
          Text(
            text = if (deviceInfo.isCharging) "Charging • AC Power" else "Discharging",
            fontSize = 10.sp,
            color = Muted
          )
        }

        // Storage
        Column(
          modifier = Modifier
            .weight(1f)
            .testTag("storage_card")
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(24.dp))
            .padding(16.dp)
        ) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
            Icon(
              imageVector = Icons.Outlined.Storage,
              contentDescription = "Storage Status Icon",
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(16.dp)
            )
            Text("Storage", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
          val totalGb = deviceInfo.totalStorage / (1024 * 1024 * 1024)
          val availGb = deviceInfo.availableStorage / (1024 * 1024 * 1024)
          Text(
            text = "$totalGb GB",
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground
          )
          Text(
            text = "$availGb GB Available",
            fontSize = 10.sp,
            color = Muted
          )
        }
      }
    }

    // Footer
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 24.dp, vertical = 20.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      Text(
        text = "RESOLUTION: ${deviceInfo.screenWidth}x${deviceInfo.screenHeight} • ${deviceInfo.androidVersion}",
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        color = Muted,
        letterSpacing = 0.5.sp
      )
      
      Button(
        onClick = {
            if (isServerStoppedOrError) {
                viewModel.startServer()
            } else {
                viewModel.stopServer()
            }
        },
        modifier = Modifier
          .fillMaxWidth()
          .height(56.dp)
          .testTag("toggle_server_button"),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
          containerColor = MaterialTheme.colorScheme.onBackground,
          contentColor = MaterialTheme.colorScheme.background
        )
      ) {
        Text(
          text = if (isServerStoppedOrError) "Start Remote Service" else "Stop Remote Service",
          fontSize = 14.sp,
          fontWeight = FontWeight.SemiBold
        )
      }
    }
  }

  // Settings & Boot Persistence Bottom Sheet
  if (showSettingsSheet) {
    ModalBottomSheet(
      onDismissRequest = { showSettingsSheet = false },
      containerColor = MaterialTheme.colorScheme.background,
      shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 24.dp)
          .padding(bottom = 36.dp)
          .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp)
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = "Settings & Persistence",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
          )
          IconButton(onClick = { showSettingsSheet = false }) {
            Text("Done", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
          }
        }

        // Custom Static Pairing PIN Setting
        var customPinInput by remember(pairingCode) { mutableStateOf(pairingCode) }
        var pinSavedFeedback by remember { mutableStateOf(false) }

        Card(
          shape = RoundedCornerShape(20.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
          modifier = Modifier.fillMaxWidth().testTag("custom_pin_settings_card")
        ) {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
          ) {
            Row(
              horizontalArrangement = Arrangement.spacedBy(12.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Icon(
                imageVector = Icons.Outlined.Lock,
                contentDescription = "Pairing PIN Lock Icon",
                tint = MaterialTheme.colorScheme.primary
              )
              Column {
                Text(
                  text = "Custom Pairing PIN",
                  fontSize = 16.sp,
                  fontWeight = FontWeight.SemiBold,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                  text = "Set a permanent static PIN to pair and connect remote clients.",
                  fontSize = 12.sp,
                  color = Muted
                )
              }
            }

            OutlinedTextField(
              value = customPinInput,
              onValueChange = { input ->
                if (input.length <= 12) {
                  customPinInput = input
                  pinSavedFeedback = false
                }
              },
              label = { Text("Pairing Security PIN") },
              singleLine = true,
              keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done
              ),
              modifier = Modifier.fillMaxWidth().testTag("custom_pin_input"),
              shape = RoundedCornerShape(12.dp),
              trailingIcon = {
                if (pinSavedFeedback) {
                  Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = "PIN Saved",
                    tint = MaterialTheme.colorScheme.tertiary
                  )
                }
              }
            )

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              OutlinedButton(
                onClick = {
                  viewModel.refreshPairingCode()
                  customPinInput = viewModel.pairingCode.value
                  pinSavedFeedback = true
                },
                modifier = Modifier.weight(1f).testTag("generate_random_pin_button"),
                shape = RoundedCornerShape(12.dp)
              ) {
                Text("Generate 6-Digit", fontSize = 12.sp)
              }

              Button(
                onClick = {
                  if (customPinInput.isNotBlank()) {
                    viewModel.setCustomPairingCode(customPinInput)
                    pinSavedFeedback = true
                  }
                },
                modifier = Modifier.weight(1f).testTag("save_custom_pin_button"),
                shape = RoundedCornerShape(12.dp)
              ) {
                Text(
                  if (pinSavedFeedback) "Saved ✓" else "Save PIN",
                  fontSize = 12.sp,
                  fontWeight = FontWeight.Bold
                )
              }
            }
          }
        }

        // Permissions Check
        com.example.ui.PermissionsCard()

        // Auto Boot Setting
        Card(
          shape = RoundedCornerShape(20.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
          modifier = Modifier.fillMaxWidth()
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Row(
              modifier = Modifier.weight(1f),
              horizontalArrangement = Arrangement.spacedBy(12.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Icon(
                imageVector = Icons.Outlined.Power,
                contentDescription = "Auto Boot Icon",
                tint = MaterialTheme.colorScheme.primary
              )
              Column {
                Text(
                  text = "Start on Device Boot",
                  fontSize = 16.sp,
                  fontWeight = FontWeight.SemiBold,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                  text = "Automatically activates Ghost when phone restarts so it's always reachable.",
                  fontSize = 12.sp,
                  color = Muted,
                  lineHeight = 16.sp
                )
              }
            }
            Switch(
              checked = bootStartEnabled,
              onCheckedChange = { viewModel.setBootStartEnabled(it) },
              modifier = Modifier.testTag("boot_start_switch")
            )
          }
        }

        // Battery Optimization Check
        Card(
          shape = RoundedCornerShape(20.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
          modifier = Modifier.fillMaxWidth()
        ) {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
          ) {
            Row(
              horizontalArrangement = Arrangement.spacedBy(12.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Icon(
                imageVector = if (isBatteryOptimized) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                contentDescription = "Battery Optimization Status",
                tint = if (isBatteryOptimized) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
              )
              Column {
                Text(
                  text = "Background Protection",
                  fontSize = 16.sp,
                  fontWeight = FontWeight.SemiBold,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                  text = if (isBatteryOptimized) "Battery optimization ignored (Active)" else "Battery optimized (System may pause service)",
                  fontSize = 12.sp,
                  color = if (isBatteryOptimized) MaterialTheme.colorScheme.tertiary else Muted
                )
              }
            }

            if (!isBatteryOptimized) {
              Button(
                onClick = {
                  try {
                    context.startActivity(viewModel.getBatteryOptimizationIntent())
                  } catch (e: Exception) {
                    e.printStackTrace()
                  }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
              ) {
                Text("Allow Unrestricted Background", fontSize = 13.sp)
              }
            }
          }
        }

        // Notification Permission Check (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth()
          ) {
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
              verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
              Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
              ) {
                Icon(
                  imageVector = Icons.Outlined.Notifications,
                  contentDescription = "Notification Icon",
                  tint = MaterialTheme.colorScheme.primary
                )
                Column {
                  Text(
                    text = "Persistent Notification",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                  )
                  Text(
                    text = if (hasNotificationPermission) "Granted (Keeps service alive in background)" else "Permission needed for Android 13+ foreground service",
                    fontSize = 12.sp,
                    color = Muted
                  )
                }
              }

              if (!hasNotificationPermission) {
                Button(
                  onClick = {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                  },
                  modifier = Modifier.fillMaxWidth(),
                  shape = RoundedCornerShape(12.dp),
                  colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                  Text("Grant Notification Permission", fontSize = 13.sp)
                }
              }
            }
          }
        }
      }
    }
  }
}
