package com.example.device

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

class ScreenCaptureHelper(private val context: Context) {

    private val captureMutex = Mutex()
    private var lastSuccessfulFrame: Map<String, Any>? = null
    private var lastCaptureTime: Long = 0L

    suspend fun generateScreenFrame(
        deviceInfo: DeviceInfo,
        quality: Int = 50,
        scale: Float = 0.5f
    ): Map<String, Any> = withContext(Dispatchers.IO) {
        // 1. FAST PATH: Check Hardware Accelerated MediaProjection (30-60 FPS)
        if (ScreenStreamManager.isProjectionActive.value) {
            val hwFrame = ScreenStreamManager.getLatestHardwareFrame(quality, scale)
            if (hwFrame != null) {
                lastSuccessfulFrame = hwFrame
                lastCaptureTime = System.currentTimeMillis()
                return@withContext hwFrame
            }
        }

        // 2. FALLBACK PATH: Accessibility Service Screenshot
        val now = System.currentTimeMillis()
        val service = GhostAccessibilityService.instance
        if (service == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return@withContext lastSuccessfulFrame ?: generatePermissionRequiredFrame(deviceInfo, quality, scale)
        }

        // If a request comes in very quickly (< 40ms) and we have a recent frame, return cached frame
        if (now - lastCaptureTime < 40L && lastSuccessfulFrame != null) {
            return@withContext lastSuccessfulFrame!!.toMutableMap().apply {
                put("timestamp", now)
                put("cached", true)
            }
        }

        val frame = captureMutex.withLock {
            if (System.currentTimeMillis() - lastCaptureTime < 40L && lastSuccessfulFrame != null) {
                return@withLock lastSuccessfulFrame!!.toMutableMap().apply {
                    put("timestamp", System.currentTimeMillis())
                    put("cached", true)
                }
            }

            val capturedResult = withTimeoutOrNull(800L) {
                captureFromAccessibility(service, quality, scale)
            }

            if (capturedResult != null) {
                lastSuccessfulFrame = capturedResult
                lastCaptureTime = System.currentTimeMillis()
                capturedResult
            } else {
                lastSuccessfulFrame?.toMutableMap()?.apply {
                    put("timestamp", System.currentTimeMillis())
                    put("cached", true)
                } ?: generatePermissionRequiredFrame(deviceInfo, quality, scale)
            }
        }

        frame
    }

    private suspend fun captureFromAccessibility(
        service: GhostAccessibilityService,
        quality: Int,
        scale: Float
    ): Map<String, Any>? = suspendCancellableCoroutine { continuation ->
        try {
            service.takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                context.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        try {
                            val hwBuffer = screenshot.hardwareBuffer
                            val bitmap = Bitmap.wrapHardwareBuffer(hwBuffer, screenshot.colorSpace)

                            if (bitmap != null) {
                                val targetScale = scale.coerceIn(0.1f, 1.0f)
                                val scaledBitmap = if (targetScale < 0.99f) {
                                    val m = Matrix()
                                    m.setScale(targetScale, targetScale)
                                    Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
                                } else {
                                    bitmap
                                }

                                val outputStream = ByteArrayOutputStream()
                                scaledBitmap.compress(
                                    Bitmap.CompressFormat.JPEG,
                                    quality.coerceIn(10, 95),
                                    outputStream
                                )
                                val bytes = outputStream.toByteArray()
                                val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)

                                if (scaledBitmap != bitmap) {
                                    scaledBitmap.recycle()
                                }
                                bitmap.recycle()
                                hwBuffer.close()

                                val result = mapOf(
                                    "image" to "data:image/jpeg;base64,$base64",
                                    "width" to (bitmap.width * targetScale).toInt(),
                                    "height" to (bitmap.height * targetScale).toInt(),
                                    "timestamp" to System.currentTimeMillis(),
                                    "success" to true,
                                    "fpsMode" to "accessibility_fallback"
                                )
                                if (continuation.isActive) {
                                    continuation.resume(result)
                                }
                            } else {
                                hwBuffer.close()
                                if (continuation.isActive) continuation.resume(null)
                            }
                        } catch (e: Exception) {
                            Log.e("ScreenCapture", "Screenshot processing exception", e)
                            if (continuation.isActive) continuation.resume(null)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        if (continuation.isActive) {
                            continuation.resume(null)
                        }
                    }
                }
            )
        } catch (e: Exception) {
            Log.e("ScreenCapture", "Failed to call takeScreenshot", e)
            if (continuation.isActive) {
                continuation.resume(null)
            }
        }
    }

    private fun generatePermissionRequiredFrame(
        deviceInfo: DeviceInfo,
        quality: Int = 50,
        scale: Float = 0.35f
    ): Map<String, Any> {
        val width = (deviceInfo.screenWidth * scale).toInt().coerceAtLeast(360)
        val height = (deviceInfo.screenHeight * scale).toInt().coerceAtLeast(640)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val bgPaint = Paint().apply {
            color = Color.parseColor("#090A0F")
            isAntiAlias = true
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        val cardPaint = Paint().apply {
            color = Color.parseColor("#151828")
            isAntiAlias = true
        }
        val cardRect = RectF(
            width * 0.08f,
            height * 0.25f,
            width * 0.92f,
            height * 0.65f
        )
        canvas.drawRoundRect(cardRect, 28f, 28f, cardPaint)

        val headerPaint = Paint().apply {
            color = Color.parseColor("#EF4444")
            textSize = height * 0.024f
            isFakeBoldText = true
            isAntiAlias = true
        }
        canvas.drawText("ACCESSIBILITY OR CAST PERMISSION REQUIRED", width * 0.12f, height * 0.35f, headerPaint)

        val subPaint = Paint().apply {
            color = Color.parseColor("#8E96B4")
            textSize = height * 0.016f
            isAntiAlias = true
        }
        canvas.drawText("Enable Ghost Accessibility Service or 60 FPS Cast", width * 0.12f, height * 0.42f, subPaint)
        canvas.drawText("in the Android app for ultra-smooth remote control.", width * 0.12f, height * 0.47f, subPaint)

        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        val bytes = outputStream.toByteArray()
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        bitmap.recycle()

        return mapOf(
            "image" to "data:image/jpeg;base64,$base64",
            "width" to width,
            "height" to height,
            "timestamp" to System.currentTimeMillis(),
            "error" to "Permission Required"
        )
    }
}
