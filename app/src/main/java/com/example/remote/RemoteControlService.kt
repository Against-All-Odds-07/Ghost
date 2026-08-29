package com.example.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.device.DeviceInfoManager
import com.example.network.ProtocolHandler
import com.example.network.RemoteWebSocketServer
import com.example.network.TailscaleManager
import com.example.security.PairingManager
import com.example.ui.ServerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RemoteControlService : Service() {

    private var webSocketServer: RemoteWebSocketServer? = null
    private var protocolHandler: ProtocolHandler? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        const val CHANNEL_ID = "remote_control_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.example.remote.START"
        const val ACTION_STOP = "com.example.remote.STOP"
        const val SERVER_PORT = 8765

        private val _serverState = MutableStateFlow<ServerState>(ServerState.Stopped)
        val serverState: StateFlow<ServerState> = _serverState.asStateFlow()

        var isServiceRunning: Boolean = false
            private set

        fun startService(context: Context) {
            val intent = Intent(context, RemoteControlService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, RemoteControlService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        createNotificationChannel()
        registerNetworkMonitoring()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.d("RemoteControlService", "Stop command received")
                stopServer()
                stopForeground(true)
                stopSelf()
                isServiceRunning = false
                _serverState.value = ServerState.Stopped
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                Log.d("RemoteControlService", "Start command received, initiating foreground server...")
                startForegroundNotification()
                startServer()
            }
        }
        return START_STICKY
    }

    private fun startForegroundNotification() {
        val notification = buildForegroundNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val tailscaleIp = TailscaleManager.getTailscaleIp()
        val localIp = TailscaleManager.getLocalIp()
        val activeIp = tailscaleIp ?: localIp ?: "Connecting..."
        val statusText = if (tailscaleIp != null) {
            "Tailscale: ws://$tailscaleIp:$SERVER_PORT"
        } else if (localIp != null) {
            "LAN: ws://$localIp:$SERVER_PORT"
        } else {
            "Waiting for network..."
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Ghost Active & Ready")
            .setContentText(statusText)
            .setSubText("Port $SERVER_PORT")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification() {
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.notify(NOTIFICATION_ID, buildForegroundNotification())
        } catch (e: Exception) {
            Log.e("RemoteControlService", "Failed to update notification", e)
        }
    }

    @Synchronized
    private fun startServer() {
        if (webSocketServer != null) {
            Log.d("RemoteControlService", "WebSocket server already running on port $SERVER_PORT")
            _serverState.value = ServerState.Running(SERVER_PORT)
            return
        }

        serviceScope.launch {
            _serverState.value = ServerState.Starting
            try {
                val deviceInfoManager = DeviceInfoManager(applicationContext)
                val pairingManager = PairingManager.getInstance(applicationContext)
                protocolHandler = ProtocolHandler(applicationContext, deviceInfoManager, pairingManager)
                webSocketServer = RemoteWebSocketServer(SERVER_PORT, protocolHandler!!)
                webSocketServer?.start()

                _serverState.value = ServerState.Running(SERVER_PORT)
                Log.d("RemoteControlService", "WebSocket server successfully bound to port $SERVER_PORT")
                updateNotification()
            } catch (e: Exception) {
                Log.e("RemoteControlService", "Failed to start WebSocket server", e)
                _serverState.value = ServerState.Error(e.message ?: "Failed to start server")
            }
        }
    }

    @Synchronized
    private fun stopServer() {
        try {
            webSocketServer?.stopGracefully(1000)
            webSocketServer = null
            protocolHandler?.shutdown()
            protocolHandler = null
        } catch (e: Exception) {
            Log.e("RemoteControlService", "Error stopping WebSocket server", e)
        }
    }

    private fun registerNetworkMonitoring() {
        try {
            connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.d("RemoteControlService", "Network became available, updating endpoints...")
                    serviceScope.launch {
                        delay(1000) // Brief delay for interface IP assignment
                        updateNotification()
                    }
                }

                override fun onLost(network: Network) {
                    Log.d("RemoteControlService", "Network lost")
                    updateNotification()
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities
                ) {
                    updateNotification()
                }
            }

            networkCallback?.let {
                connectivityManager?.registerNetworkCallback(request, it)
            }
        } catch (e: Exception) {
            Log.e("RemoteControlService", "Failed to register network callback", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Ghost Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Ghost WebSocket server active for secure remote desktop connections"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        isServiceRunning = false
        _serverState.value = ServerState.Stopped
        stopServer()
        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (e: Exception) {
            // Ignore unregister error
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
