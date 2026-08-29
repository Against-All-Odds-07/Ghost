package com.example.device

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import com.example.network.TailscaleManager

data class DeviceInfo(
    val deviceName: String,
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val batteryLevel: Int,
    val isCharging: Boolean,
    val totalStorage: Long,
    val availableStorage: Long,
    val networkType: String,
    val screenWidth: Int,
    val screenHeight: Int,
    val orientation: String,
    val tailscaleIp: String?
)

class DeviceInfoManager(private val context: Context) {

    fun getDeviceInfo(): DeviceInfo {
        val displayMetrics = context.resources.displayMetrics
        val orientation = if (context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            "Landscape"
        } else {
            "Portrait"
        }

        return DeviceInfo(
            deviceName = getDeviceName(),
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            batteryLevel = getBatteryLevel(),
            isCharging = isCharging(),
            totalStorage = getTotalStorage(),
            availableStorage = getAvailableStorage(),
            networkType = getNetworkType(),
            screenWidth = displayMetrics.widthPixels,
            screenHeight = displayMetrics.heightPixels,
            orientation = orientation,
            tailscaleIp = TailscaleManager.getTailscaleIp()
        )
    }

    private fun getDeviceName(): String {
        return try {
            val name = android.provider.Settings.Global.getString(
                context.contentResolver,
                android.provider.Settings.Global.DEVICE_NAME
            )
            if (!name.isNullOrBlank()) name else "${Build.MANUFACTURER} ${Build.MODEL}"
        } catch (e: Exception) {
            "${Build.MANUFACTURER} ${Build.MODEL}"
        }
    }

    private fun getBatteryLevel(): Int {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val capacity = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            if (capacity in 0..100) {
                capacity
            } else {
                val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
                    context.registerReceiver(null, ifilter)
                }
                val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                if (level != -1 && scale > 0) {
                    (level * 100 / scale.toFloat()).toInt()
                } else {
                    100
                }
            }
        } catch (e: Exception) {
            100
        }
    }

    private fun isCharging(): Boolean {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && bm != null) {
                bm.isCharging
            } else {
                val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
                    context.registerReceiver(null, ifilter)
                }
                val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun getTotalStorage(): Long {
        return try {
            val path = Environment.getDataDirectory()
            val stat = StatFs(path.path)
            stat.blockCountLong * stat.blockSizeLong
        } catch (e: Exception) {
            0L
        }
    }

    private fun getAvailableStorage(): Long {
        return try {
            val path = Environment.getDataDirectory()
            val stat = StatFs(path.path)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (e: Exception) {
            0L
        }
    }

    private fun getNetworkType(): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return "Unknown"
            val network = cm.activeNetwork ?: return "Disconnected"
            val capabilities = cm.getNetworkCapabilities(network) ?: return "Unknown"
            when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "Tailscale/VPN"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> "Connected"
            }
        } catch (e: Exception) {
            "Unknown"
        }
    }
}

