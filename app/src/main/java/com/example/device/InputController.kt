package com.example.device

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log

class InputController(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    fun getClipboardText(): String {
        return try {
            val clip = clipboardManager?.primaryClip
            if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0).text?.toString() ?: ""
            } else {
                ""
            }
        } catch (e: Exception) {
            Log.e("InputController", "Error reading clipboard", e)
            ""
        }
    }

    fun setClipboardText(text: String): Boolean {
        return try {
            mainHandler.post {
                val clip = ClipData.newPlainText("Ghost Remote", text)
                clipboardManager?.setPrimaryClip(clip)
            }
            vibrateBrief()
            true
        } catch (e: Exception) {
            Log.e("InputController", "Error setting clipboard", e)
            false
        }
    }

    fun handleKeyEvent(keyCode: String): Boolean {
        Log.d("InputController", "Handling hardware key event: $keyCode")
        vibrateBrief()

        return when (keyCode.uppercase()) {
            "HOME" -> {
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            }
            "VOLUME_UP" -> {
                audioManager?.adjustVolume(AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                true
            }
            "VOLUME_DOWN" -> {
                audioManager?.adjustVolume(AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                true
            }
            "VOLUME_MUTE" -> {
                audioManager?.adjustVolume(AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
                true
            }
            "BACK" -> {
                GhostAccessibilityService.instance?.injectGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK) ?: false
            }
            "APP_SWITCH" -> {
                GhostAccessibilityService.instance?.injectGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS) ?: false
            }
            "POWER", "LOCK" -> {
                GhostAccessibilityService.instance?.injectGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) ?: false
            }
            else -> false
        }
    }

    fun openUrl(url: String): Boolean {
        return try {
            var formattedUrl = url.trim()
            if (!formattedUrl.startsWith("http://") && !formattedUrl.startsWith("https://")) {
                formattedUrl = "https://$formattedUrl"
            }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(formattedUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e("InputController", "Error opening URL: $url", e)
            false
        }
    }

    fun launchApp(packageName: String): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e("InputController", "Error launching app: $packageName", e)
            false
        }
    }

    fun adjustVolume(direction: String): Map<String, Any> {
        val stream = AudioManager.STREAM_MUSIC
        when (direction.uppercase()) {
            "UP" -> audioManager?.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "DOWN" -> audioManager?.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "MUTE" -> audioManager?.adjustStreamVolume(stream, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
        }
        val currentVol = audioManager?.getStreamVolume(stream) ?: 0
        val maxVol = audioManager?.getStreamMaxVolume(stream) ?: 100
        return mapOf(
            "volume" to currentVol,
            "maxVolume" to maxVol,
            "percentage" to if (maxVol > 0) (currentVol * 100 / maxVol) else 0
        )
    }

    private fun vibrateBrief() {
        try {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(35)
            }
        } catch (e: Exception) {
            // Ignore vibration error
        }
    }
}
