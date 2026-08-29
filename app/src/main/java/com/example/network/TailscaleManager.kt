package com.example.network

import java.net.Inet4Address
import java.net.NetworkInterface

object TailscaleManager {

    /**
     * Attempts to find the device's Tailscale IPv4 address (100.64.0.0/10).
     */
    fun getTailscaleIp(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val name = networkInterface.name.lowercase()

                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        val hostAddress = address.hostAddress ?: continue

                        // Check Tailscale CGNAT range 100.64.0.0/10
                        if (hostAddress.startsWith("100.")) {
                            val parts = hostAddress.split(".")
                            if (parts.size == 4) {
                                val secondOctet = parts[1].toIntOrNull() ?: 0
                                if (secondOctet in 64..127) {
                                    return hostAddress
                                }
                            }
                        }

                        // Also check if interface is explicitly named tailscale/tun
                        if (name.contains("tailscale") || name.startsWith("tun") || name.startsWith("utun")) {
                            return hostAddress
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    /**
     * Fallback to retrieve the active local Wi-Fi / LAN IP if Tailscale is not running.
     */
    fun getLocalIp(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback) continue

                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        val hostAddress = address.hostAddress ?: continue
                        if (!hostAddress.startsWith("127.")) {
                            return hostAddress
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
}

