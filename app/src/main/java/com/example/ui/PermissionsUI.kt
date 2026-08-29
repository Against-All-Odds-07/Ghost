package com.example.ui

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Message
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.device.GhostAccessibilityService
import com.example.device.ScreenStreamManager
import com.google.accompanist.permissions.*

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PermissionsCard() {
    val context = LocalContext.current

    val isProjectionActive by ScreenStreamManager.isProjectionActive.collectAsStateWithLifecycle()

    val mediaProjectionManager = remember {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
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

    val smsPermissionsState = rememberMultiplePermissionsState(
        permissions = listOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS)
    )

    val locationPermissionsState = rememberMultiplePermissionsState(
        permissions = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    )

    var hasStoragePermission by remember { mutableStateOf(checkStoragePermission(context)) }
    var hasAccessibilityPermission by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }

    // Storage Launcher for Android 11+
    val storageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        hasStoragePermission = checkStoragePermission(context)
    }

    val storagePermissionState = rememberMultiplePermissionsState(
        permissions = listOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    )

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Required Permissions",
                fontSize = 18.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 60 FPS Ultra Screen Mirroring (MediaProjection)
            PermissionRow(
                icon = Icons.Outlined.Cast,
                title = "60 FPS Live Mirroring",
                description = if (isProjectionActive) "Active (Hardware Accelerated)" else "Enable zero-latency 60 FPS stream",
                isGranted = isProjectionActive,
                onClick = {
                    if (isProjectionActive) {
                        ScreenStreamManager.stopProjection()
                    } else {
                        mediaProjectionManager?.createScreenCaptureIntent()?.let {
                            screenCaptureLauncher.launch(it)
                        }
                    }
                }
            )

            // Accessibility Permission
            PermissionRow(
                icon = Icons.Outlined.Accessibility,
                title = "Accessibility Gestures",
                description = "Remote taps, clicks & swipes",
                isGranted = hasAccessibilityPermission,
                onClick = {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
            )

            // Storage Permission
            PermissionRow(
                icon = Icons.Outlined.Folder,
                title = "Storage",
                description = "Manage files remotely",
                isGranted = hasStoragePermission,
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        try {
                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                            intent.data = Uri.parse("package:${context.packageName}")
                            storageLauncher.launch(intent)
                        } catch (e: Exception) {
                            val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                            storageLauncher.launch(intent)
                        }
                    } else {
                        storagePermissionState.launchMultiplePermissionRequest()
                        hasStoragePermission = checkStoragePermission(context)
                    }
                }
            )

            // SMS Permission
            PermissionRow(
                icon = Icons.Outlined.Message,
                title = "SMS",
                description = "Read and send texts",
                isGranted = smsPermissionsState.allPermissionsGranted,
                onClick = {
                    smsPermissionsState.launchMultiplePermissionRequest()
                }
            )

            // Location Permission
            val hasLocation = locationPermissionsState.allPermissionsGranted ||
                    checkLocationPermission(context)
            PermissionRow(
                icon = Icons.Outlined.LocationOn,
                title = "Location",
                description = "Locate device remotely",
                isGranted = hasLocation,
                onClick = {
                    locationPermissionsState.launchMultiplePermissionRequest()
                }
            )
            
            Button(
                onClick = {
                    hasStoragePermission = checkStoragePermission(context)
                    hasAccessibilityPermission = isAccessibilityServiceEnabled(context)
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                Text("Refresh Permission Status", fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun PermissionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    isGranted: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = if (isGranted) Icons.Filled.CheckCircle else icon,
                contentDescription = title,
                tint = if (isGranted) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
            )
            Column {
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (isGranted) "Granted / Active" else description,
                    fontSize = 12.sp,
                    color = if (isGranted) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
        if (!isGranted) {
            Button(
                onClick = onClick,
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.height(36.dp)
            ) {
                Text("Grant", fontSize = 12.sp)
            }
        }
    }
}

private fun checkStoragePermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }
}

private fun checkLocationPermission(context: Context): Boolean {
    val fine = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    return fine || coarse
}

private fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val expectedComponentName = ComponentName(context, GhostAccessibilityService::class.java)
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    )
    return enabledServices?.contains(expectedComponentName.flattenToString()) == true
}
