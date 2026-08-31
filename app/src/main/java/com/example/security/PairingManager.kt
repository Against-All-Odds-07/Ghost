package com.example.security

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom
import java.util.UUID
import kotlin.random.Random

class PairingManager private constructor(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("remote_control_prefs", Context.MODE_PRIVATE)
    private val secureRandom = SecureRandom()

    var deviceId: String = ""
        private set

    var currentPairingCode: String = ""
        private set

    companion object {
        private const val KEY_CUSTOM_PAIRING_CODE = "custom_pairing_code"
        private const val DEFAULT_PAIRING_CODE = "123456"

        @Volatile
        private var instance: PairingManager? = null

        fun getInstance(context: Context): PairingManager {
            return instance ?: synchronized(this) {
                instance ?: PairingManager(context.applicationContext).also { instance = it }
            }
        }
    }

    init {
        deviceId = prefs.getString("device_id", null) ?: run {
            val newId = "DEVICE-${UUID.randomUUID().toString().substring(0, 8).uppercase()}"
            prefs.edit().putString("device_id", newId).apply()
            newId
        }
        currentPairingCode = prefs.getString(KEY_CUSTOM_PAIRING_CODE, null) ?: run {
            prefs.edit().putString(KEY_CUSTOM_PAIRING_CODE, DEFAULT_PAIRING_CODE).apply()
            DEFAULT_PAIRING_CODE
        }
    }

    fun setCustomPairingCode(newCode: String): String {
        val clean = newCode.trim().ifEmpty { DEFAULT_PAIRING_CODE }
        currentPairingCode = clean
        prefs.edit().putString(KEY_CUSTOM_PAIRING_CODE, clean).apply()
        return currentPairingCode
    }

    fun generatePairingCode(): String {
        val newCode = String.format("%06d", Random.nextInt(1000000))
        setCustomPairingCode(newCode)
        return currentPairingCode
    }

    fun verifyPairingCode(code: String): Boolean {
        return code.trim().equals(currentPairingCode.trim(), ignoreCase = true)
    }

    /**
     * Registers an authenticated client with a new cryptographically secure token.
     */
    fun registerClientWithNewToken(clientId: String): String {
        val randomBytes = ByteArray(32)
        secureRandom.nextBytes(randomBytes)
        val token = android.util.Base64.encodeToString(randomBytes, android.util.Base64.NO_WRAP)
        
        prefs.edit()
            .putString("token_$clientId", token)
            .apply()
        
        val clients = getAuthenticatedClients().toMutableSet()
        clients.add(clientId)
        prefs.edit().putStringSet("authenticated_clients", clients).apply()

        return token
    }

    /**
     * Validates that the provided token matches the stored token for this client.
     */
    fun validateClientToken(clientId: String, token: String): Boolean {
        if (!isAuthenticated(clientId)) return false
        val storedToken = prefs.getString("token_$clientId", null) ?: return false
        return storedToken == token.trim()
    }

    fun revokeClient(clientId: String) {
        val clients = getAuthenticatedClients().toMutableSet()
        clients.remove(clientId)
        prefs.edit()
            .putStringSet("authenticated_clients", clients)
            .remove("token_$clientId")
            .apply()
    }

    fun isAuthenticated(clientId: String): Boolean {
        return getAuthenticatedClients().contains(clientId)
    }

    fun getAuthenticatedClients(): Set<String> {
        return prefs.getStringSet("authenticated_clients", emptySet()) ?: emptySet()
    }
}

