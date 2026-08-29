package com.example.remote

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

class SettingsManager(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("remote_link_settings", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_BOOT_START = "key_boot_start_enabled"
        private const val DEFAULT_BOOT_START = true

        fun isBootStartEnabled(context: Context): Boolean {
            val sp = context.getSharedPreferences("remote_link_settings", Context.MODE_PRIVATE)
            return sp.getBoolean(KEY_BOOT_START, DEFAULT_BOOT_START)
        }
    }

    var isBootStartEnabled: Boolean
        get() = prefs.getBoolean(KEY_BOOT_START, DEFAULT_BOOT_START)
        set(value) {
            prefs.edit().putBoolean(KEY_BOOT_START, value).apply()
        }

    fun isBatteryOptimizationIgnored(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        } else {
            true
        }
    }

    fun getBatteryOptimizationIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } catch (e: Exception) {
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
        } else {
            Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }
}
