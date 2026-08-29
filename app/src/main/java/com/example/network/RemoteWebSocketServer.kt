package com.example.network

import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress

class RemoteWebSocketServer(
    port: Int,
    private val protocolHandler: ProtocolHandler
) : WebSocketServer(InetSocketAddress(port)) {

    init {
        // Allows binding even if previous socket is in TIME_WAIT
        isReuseAddr = true
        // Set connection lost timeout to 30s to clean up zombie connections
        connectionLostTimeout = 30
    }

    override fun onOpen(conn: WebSocket?, handshake: ClientHandshake?) {
        Log.d("RemoteWS", "New connection from ${conn?.remoteSocketAddress}")
    }

    override fun onClose(conn: WebSocket?, code: Int, reason: String?, remote: Boolean) {
        Log.d("RemoteWS", "Closed connection to ${conn?.remoteSocketAddress}, code: $code, reason: $reason")
        conn?.let { protocolHandler.onConnectionClosed(it) }
    }

    override fun onMessage(conn: WebSocket?, message: String?) {
        conn?.let { connection ->
            message?.let { msg ->
                protocolHandler.handleMessage(connection, msg)
            }
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception?) {
        Log.e("RemoteWS", "Error on connection ${conn?.remoteSocketAddress}", ex)
    }

    override fun onStart() {
        Log.d("RemoteWS", "Server started successfully on port $port")
    }

    fun stopGracefully(timeoutMs: Int = 1000) {
        try {
            stop(timeoutMs)
        } catch (e: Exception) {
            Log.e("RemoteWS", "Error stopping WebSocket server", e)
        }
    }
}

