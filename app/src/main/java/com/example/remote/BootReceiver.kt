package com.example.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d("BootReceiver", "Received broadcast action: $action")

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            val isBootEnabled = SettingsManager.isBootStartEnabled(context)
            Log.d("BootReceiver", "Boot persistence check: isBootStartEnabled=$isBootEnabled")

            if (isBootEnabled) {
                try {
                    Log.d("BootReceiver", "Triggering RemoteControlService startup post-boot...")
                    RemoteControlService.startService(context)
                } catch (e: Exception) {
                    Log.e("BootReceiver", "Failed to start RemoteControlService from BootReceiver", e)
                }
            } else {
                Log.d("BootReceiver", "Boot auto-start disabled by user preferences.")
            }
        }
    }
}
