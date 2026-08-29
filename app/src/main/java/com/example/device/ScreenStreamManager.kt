package com.example.device

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream

object ScreenStreamManager {
    private const val TAG = "ScreenStreamManager"

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private val _isProjectionActive = MutableStateFlow(false)
    val isProjectionActive: StateFlow<Boolean> = _isProjectionActive.asStateFlow()

    @Volatile
    private var lastBitmap: Bitmap? = null
    private val bitmapLock = Any()

    fun startProjection(
        context: Context,
        resultCode: Int,
        data: Intent,
        screenWidth: Int = 1080,
        screenHeight: Int = 2400,
        densityDpi: Int = 400
    ) {
        stopProjection()
        try {
            val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
                ?: return

            val projection = projectionManager.getMediaProjection(resultCode, data) ?: return
            mediaProjection = projection

            val thread = HandlerThread("GhostScreenCaptureThread").apply { start() }
            handlerThread = thread
            val handler = Handler(thread.looper)
            backgroundHandler = handler

            // Stream resolution: optimize to ~540p or 720p for smooth 30-60 FPS low-bandwidth streaming
            val targetWidth = if (screenWidth > 720) 720 else (if (screenWidth > 0) screenWidth else 540)
            val aspectRatio = if (screenWidth > 0) screenHeight.toFloat() / screenWidth.toFloat() else 16f / 9f
            val targetHeight = (targetWidth * aspectRatio).toInt()

            val reader = ImageReader.newInstance(targetWidth, targetHeight, PixelFormat.RGBA_8888, 2)
            imageReader = reader

            reader.setOnImageAvailableListener({ r ->
                try {
                    val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                    processHardwareImage(image)
                    image.close()
                } catch (e: Exception) {
                    // ignore occasional dropped frame during resize/rotation
                }
            }, handler)

            virtualDisplay = projection.createVirtualDisplay(
                "GhostScreenMirror",
                targetWidth,
                targetHeight,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler
            )

            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(TAG, "MediaProjection stopped by system")
                    stopProjection()
                }
            }, handler)

            _isProjectionActive.value = true
            Log.d(TAG, "MediaProjection ultra-speed capture started: ${targetWidth}x${targetHeight}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MediaProjection", e)
            stopProjection()
        }
    }

    private fun processHardwareImage(image: Image) {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val bitmapWidth = image.width + rowPadding / pixelStride
        val bitmapHeight = image.height

        synchronized(bitmapLock) {
            val bmp = lastBitmap
            val targetBmp = if (bmp != null && !bmp.isRecycled && bmp.width == bitmapWidth && bmp.height == bitmapHeight) {
                bmp
            } else {
                bmp?.recycle()
                Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
            }
            buffer.position(0)
            targetBmp.copyPixelsFromBuffer(buffer)
            lastBitmap = targetBmp
        }
    }

    fun getLatestHardwareFrame(
        quality: Int = 50,
        scale: Float = 1.0f
    ): Map<String, Any>? {
        if (!_isProjectionActive.value) return null

        var bitmapToEncode: Bitmap? = null
        var outW = 0
        var outH = 0

        synchronized(bitmapLock) {
            val current = lastBitmap
            if (current == null || current.isRecycled) return null
            try {
                bitmapToEncode = Bitmap.createBitmap(current)
                outW = current.width
                outH = current.height
            } catch (e: Exception) {
                return null
            }
        }

        val bmp = bitmapToEncode ?: return null

        return try {
            val scaledBmp = if (scale < 0.95f && scale > 0.1f) {
                val matrix = Matrix()
                matrix.setScale(scale, scale)
                val s = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                bmp.recycle()
                s
            } else {
                bmp
            }

            val stream = ByteArrayOutputStream()
            scaledBmp.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(15, 90), stream)
            val bytes = stream.toByteArray()
            val w = scaledBmp.width
            val h = scaledBmp.height
            scaledBmp.recycle()

            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)

            mapOf(
                "image" to "data:image/jpeg;base64,$base64",
                "width" to w,
                "height" to h,
                "timestamp" to System.currentTimeMillis(),
                "success" to true,
                "fpsMode" to "60fps_hardware"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error encoding hardware frame", e)
            null
        }
    }

    fun stopProjection() {
        _isProjectionActive.value = false
        try {
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            mediaProjection?.stop()
            mediaProjection = null
            handlerThread?.quitSafely()
            handlerThread = null
            backgroundHandler = null
            synchronized(bitmapLock) {
                lastBitmap?.recycle()
                lastBitmap = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping projection", e)
        }
    }
}
