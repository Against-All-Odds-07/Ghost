package com.example.network

import android.content.Context
import android.util.Log
import com.example.device.DeviceInfoManager
import com.example.device.FileManager
import com.example.device.InputController
import com.example.device.LocationHelper
import com.example.device.ScreenCaptureHelper
import com.example.device.SmsManagerHelper
import com.example.security.PairingManager
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.java_websocket.WebSocket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class ProtocolHandler(
    private val context: Context,
    private val deviceInfoManager: DeviceInfoManager,
    private val pairingManager: PairingManager
) {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val requestAdapter = moshi.adapter(RpcRequest::class.java)
    private val responseAdapter = moshi.adapter(RpcResponse::class.java)

    private val fileManager = FileManager(context)
    private val smsHelper = SmsManagerHelper(context)
    private val inputController = InputController(context)
    private val screenCaptureHelper = ScreenCaptureHelper(context)
    private val locationHelper = LocationHelper(context)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Thread-safe set of authenticated sessions
    private val authenticatedSessions: MutableSet<WebSocket> =
        Collections.newSetFromMap(ConcurrentHashMap<WebSocket, Boolean>())

    fun handleMessage(conn: WebSocket, message: String) {
        scope.launch {
            try {
                val request = requestAdapter.fromJson(message) ?: run {
                    sendError(conn, "unknown", "Failed to parse JSON RPC request")
                    return@launch
                }

                // If not authenticated, only allow auth commands
                if (!authenticatedSessions.contains(conn)) {
                    handleUnauthenticatedRequest(conn, request)
                    return@launch
                }

                // Authenticated requests
                handleAuthenticatedRequest(conn, request)

            } catch (e: Exception) {
                Log.e("ProtocolHandler", "Failed to parse or handle message", e)
                sendError(conn, "unknown", "Server error processing request: ${e.localizedMessage}")
            }
        }
    }

    fun onConnectionClosed(conn: WebSocket) {
        authenticatedSessions.remove(conn)
    }

    fun shutdown() {
        authenticatedSessions.clear()
        scope.cancel()
    }

    private fun handleUnauthenticatedRequest(conn: WebSocket, request: RpcRequest) {
        when (request.type) {
            "session.authenticate" -> {
                val payload = request.payload
                    ?: return sendError(conn, request.id, "Missing payload in authentication request")
                val clientId = payload["clientId"] as? String ?: "WEB-CLIENT"
                val pairingCode = payload["pairingCode"] as? String
                val token = payload["token"] as? String

                if (!token.isNullOrBlank()) {
                    // Authenticating with saved token
                    if (pairingManager.validateClientToken(clientId, token)) {
                        authenticatedSessions.add(conn)
                        sendSuccess(
                            conn, request.id, mapOf(
                                "status" to "authenticated",
                                "clientId" to clientId,
                                "deviceId" to pairingManager.deviceId
                            )
                        )
                        Log.d("ProtocolHandler", "Web Client $clientId authenticated successfully via token")
                    } else {
                        sendError(conn, request.id, "Invalid authentication token. Please re-pair with code.")
                    }
                } else if (!pairingCode.isNullOrBlank()) {
                    // Authenticating with pairing code
                    if (pairingManager.verifyPairingCode(pairingCode)) {
                        val newToken = pairingManager.registerClientWithNewToken(clientId)
                        authenticatedSessions.add(conn)
                        sendSuccess(
                            conn, request.id, mapOf(
                                "status" to "paired",
                                "token" to newToken,
                                "clientId" to clientId,
                                "deviceId" to pairingManager.deviceId
                            )
                        )
                        Log.d("ProtocolHandler", "Web Client $clientId paired successfully with new token")
                    } else {
                        sendError(conn, request.id, "Invalid 6-digit pairing code")
                    }
                } else {
                    sendError(conn, request.id, "Provide either 'token' or 'pairingCode' in payload")
                }
            }
            else -> {
                sendError(conn, request.id, "Unauthorized. Must call 'session.authenticate' first.")
            }
        }
    }

    private suspend fun handleAuthenticatedRequest(conn: WebSocket, request: RpcRequest) {
        when (request.type) {
            "session.ping" -> {
                sendSuccess(
                    conn, request.id, mapOf(
                        "pong" to true,
                        "timestamp" to System.currentTimeMillis()
                    )
                )
            }
            "session.disconnect" -> {
                sendSuccess(conn, request.id, mapOf("status" to "disconnected"))
                authenticatedSessions.remove(conn)
                try {
                    conn.close()
                } catch (e: Exception) {
                    // Ignore close exception
                }
            }
            "device.info" -> {
                val info = deviceInfoManager.getDeviceInfo()
                val payload = mapOf(
                    "deviceName" to info.deviceName,
                    "manufacturer" to info.manufacturer,
                    "model" to info.model,
                    "androidVersion" to info.androidVersion,
                    "batteryLevel" to info.batteryLevel,
                    "isCharging" to info.isCharging,
                    "totalStorage" to info.totalStorage.toDouble(),
                    "availableStorage" to info.availableStorage.toDouble(),
                    "networkType" to info.networkType,
                    "screenWidth" to info.screenWidth,
                    "screenHeight" to info.screenHeight,
                    "orientation" to info.orientation,
                    "tailscaleIp" to (info.tailscaleIp ?: "")
                )
                sendSuccess(conn, request.id, payload)
            }
            "device.location" -> {
                val fresh = (request.payload?.get("fresh") as? Boolean) ?: true
                val geocode = (request.payload?.get("geocode") as? Boolean) ?: true
                val locationData = locationHelper.getLocationData(fresh = fresh, geocode = geocode)
                if (locationData["success"] == true) {
                    sendSuccess(conn, request.id, locationData)
                } else {
                    val errorMsg = locationData["error"] as? String ?: "Failed to retrieve location"
                    sendError(conn, request.id, errorMsg)
                }
            }
            "screen.capture" -> {
                val info = deviceInfoManager.getDeviceInfo()
                val quality = (request.payload?.get("quality") as? Number)?.toInt() ?: 50
                val scale = (request.payload?.get("scale") as? Number)?.toFloat() ?: 0.3f
                val frameData = screenCaptureHelper.generateScreenFrame(info, quality, scale)
                sendSuccess(conn, request.id, frameData)
            }
            "input.tap" -> {
                val x = (request.payload?.get("x") as? Number)?.toFloat() ?: 0f
                val y = (request.payload?.get("y") as? Number)?.toFloat() ?: 0f
                val info = deviceInfoManager.getDeviceInfo()
                val success = com.example.device.GhostAccessibilityService.instance?.dispatchTap(
                    x * info.screenWidth, y * info.screenHeight
                ) ?: false
                sendSuccess(conn, request.id, mapOf("action" to "tap", "success" to success))
            }
            "input.swipe" -> {
                val startX = (request.payload?.get("startX") as? Number)?.toFloat() ?: 0f
                val startY = (request.payload?.get("startY") as? Number)?.toFloat() ?: 0f
                val endX = (request.payload?.get("endX") as? Number)?.toFloat() ?: 0f
                val endY = (request.payload?.get("endY") as? Number)?.toFloat() ?: 0f
                val duration = (request.payload?.get("duration") as? Number)?.toLong() ?: 300L
                val info = deviceInfoManager.getDeviceInfo()
                val success = com.example.device.GhostAccessibilityService.instance?.dispatchSwipe(
                    startX * info.screenWidth, startY * info.screenHeight,
                    endX * info.screenWidth, endY * info.screenHeight, duration
                ) ?: false
                sendSuccess(conn, request.id, mapOf("action" to "swipe", "success" to success))
            }
            "input.key" -> {
                val keyCode = request.payload?.get("keyCode") as? String ?: ""
                val handled = inputController.handleKeyEvent(keyCode)
                sendSuccess(conn, request.id, mapOf("keyCode" to keyCode, "success" to handled))
            }
            "input.text" -> {
                val text = request.payload?.get("text") as? String ?: ""
                inputController.setClipboardText(text)
                sendSuccess(conn, request.id, mapOf("text" to text, "success" to true))
            }
            "clipboard.get" -> {
                val clipText = inputController.getClipboardText()
                sendSuccess(conn, request.id, mapOf("text" to clipText))
            }
            "clipboard.set" -> {
                val text = request.payload?.get("text") as? String ?: ""
                val success = inputController.setClipboardText(text)
                sendSuccess(conn, request.id, mapOf("text" to text, "success" to success))
            }
            "file.locations" -> {
                val locations = fileManager.getQuickLocations()
                sendSuccess(conn, request.id, mapOf("locations" to locations, "root" to fileManager.getDefaultRoot()))
            }
            "file.list" -> {
                val path = request.payload?.get("path") as? String
                try {
                    val files = fileManager.listDirectory(path)
                    val currentPath = path ?: fileManager.getDefaultRoot()
                    sendSuccess(conn, request.id, mapOf(
                        "path" to currentPath,
                        "files" to files.map {
                            mapOf(
                                "name" to it.name,
                                "path" to it.path,
                                "isDirectory" to it.isDirectory,
                                "size" to it.size,
                                "lastModified" to it.lastModified,
                                "mimeType" to it.mimeType,
                                "readable" to it.readable,
                                "writable" to it.writable
                            )
                        }
                    ))
                } catch (e: Exception) {
                    sendError(conn, request.id, "Failed to list directory: ${e.message}")
                }
            }
            "file.read" -> {
                val path = request.payload?.get("path") as? String
                if (path.isNullOrBlank()) {
                    return sendError(conn, request.id, "Missing 'path'")
                }
                try {
                    val (base64, mime) = fileManager.readFileAsBase64(path)
                    sendSuccess(conn, request.id, mapOf(
                        "path" to path,
                        "mimeType" to mime,
                        "data" to base64
                    ))
                } catch (e: Exception) {
                    sendError(conn, request.id, "Failed to read file: ${e.message}")
                }
            }
            "file.write" -> {
                val path = request.payload?.get("path") as? String
                val base64 = request.payload?.get("data") as? String
                if (path.isNullOrBlank() || base64.isNullOrBlank()) {
                    return sendError(conn, request.id, "Missing 'path' or 'data'")
                }
                try {
                    val bytesWritten = fileManager.writeFileFromBase64(path, base64)
                    sendSuccess(conn, request.id, mapOf("path" to path, "bytesWritten" to bytesWritten, "success" to true))
                } catch (e: Exception) {
                    sendError(conn, request.id, "Failed to write file: ${e.message}")
                }
            }
            "file.delete" -> {
                val path = request.payload?.get("path") as? String
                if (path.isNullOrBlank()) {
                    return sendError(conn, request.id, "Missing 'path'")
                }
                val deleted = fileManager.deletePath(path)
                sendSuccess(conn, request.id, mapOf("path" to path, "success" to deleted))
            }
            "file.mkdir" -> {
                val path = request.payload?.get("path") as? String
                if (path.isNullOrBlank()) {
                    return sendError(conn, request.id, "Missing 'path'")
                }
                val created = fileManager.createDirectory(path)
                sendSuccess(conn, request.id, mapOf("path" to path, "success" to created))
            }
            "sms.list" -> {
                val limit = (request.payload?.get("limit") as? Number)?.toInt() ?: 50
                val messages = smsHelper.getSmsList(limit)
                sendSuccess(conn, request.id, mapOf(
                    "messages" to messages.map {
                        mapOf(
                            "id" to it.id,
                            "address" to it.address,
                            "body" to it.body,
                            "date" to it.date,
                            "type" to it.type,
                            "read" to it.read
                        )
                    }
                ))
            }
            "sms.send" -> {
                val address = request.payload?.get("address") as? String ?: ""
                val message = request.payload?.get("message") as? String ?: ""
                if (address.isBlank() || message.isBlank()) {
                    return sendError(conn, request.id, "Address and message are required.")
                }
                val sent = smsHelper.sendSms(address, message)
                sendSuccess(conn, request.id, mapOf("address" to address, "sent" to sent))
            }
            "url.open" -> {
                val url = request.payload?.get("url") as? String ?: ""
                val success = inputController.openUrl(url)
                sendSuccess(conn, request.id, mapOf("url" to url, "success" to success))
            }
            "app.launch" -> {
                val pkg = request.payload?.get("packageName") as? String ?: ""
                val success = inputController.launchApp(pkg)
                sendSuccess(conn, request.id, mapOf("packageName" to pkg, "success" to success))
            }
            "volume.adjust" -> {
                val dir = request.payload?.get("direction") as? String ?: "UP"
                val result = inputController.adjustVolume(dir)
                sendSuccess(conn, request.id, result)
            }
            else -> {
                sendError(conn, request.id, "Unknown command: '${request.type}'.")
            }
        }
    }

    private fun sendSuccess(conn: WebSocket, id: String, payload: Map<String, Any>? = null) {
        try {
            if (conn.isOpen) {
                val response = RpcResponse(id = id, ok = true, payload = payload)
                conn.send(responseAdapter.toJson(response))
            }
        } catch (e: Exception) {
            Log.e("ProtocolHandler", "Error sending success response $id", e)
        }
    }

    private fun sendError(conn: WebSocket, id: String, error: String) {
        try {
            if (conn.isOpen) {
                val response = RpcResponse(id = id, ok = false, error = error)
                conn.send(responseAdapter.toJson(response))
            }
        } catch (e: Exception) {
            Log.e("ProtocolHandler", "Error sending error response $id", e)
        }
    }
}
