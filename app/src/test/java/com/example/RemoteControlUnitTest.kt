package com.example

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.device.DeviceInfoManager
import com.example.network.ProtocolHandler
import com.example.network.TailscaleManager
import com.example.remote.BootReceiver
import com.example.remote.RemoteControlService
import com.example.remote.SettingsManager
import com.example.security.PairingManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemoteControlUnitTest {

    @Test
    fun testPairingManager_codeGenerationAndValidation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pairingManager = PairingManager.getInstance(context)

        val code = pairingManager.currentPairingCode
        assertTrue(code.isNotEmpty())
        assertTrue(pairingManager.verifyPairingCode(code))
        assertFalse(pairingManager.verifyPairingCode("000000_wrong"))

        val customCode = "998877"
        pairingManager.setCustomPairingCode(customCode)
        assertEquals(customCode, pairingManager.currentPairingCode)
        assertTrue(pairingManager.verifyPairingCode("998877"))
        assertFalse(pairingManager.verifyPairingCode("112233"))

        val newCode = pairingManager.generatePairingCode()
        assertEquals(6, newCode.length)
        assertTrue(pairingManager.verifyPairingCode(newCode))
    }

    @Test
    fun testPairingManager_tokenLifecycle() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pairingManager = PairingManager.getInstance(context)

        val clientId = "TEST-CLIENT-1"
        val token = pairingManager.registerClientWithNewToken(clientId)

        assertNotNull(token)
        assertTrue(token.isNotEmpty())
        assertTrue(pairingManager.isAuthenticated(clientId))
        assertTrue(pairingManager.validateClientToken(clientId, token))
        assertFalse(pairingManager.validateClientToken(clientId, "wrong_token"))
        assertFalse(pairingManager.validateClientToken("UNKNOWN-CLIENT", token))

        pairingManager.revokeClient(clientId)
        assertFalse(pairingManager.isAuthenticated(clientId))
        assertFalse(pairingManager.validateClientToken(clientId, token))
    }

    @Test
    fun testDeviceInfoManager_readMetrics() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val deviceInfoManager = DeviceInfoManager(context)

        val info = deviceInfoManager.getDeviceInfo()
        assertNotNull(info.deviceName)
        assertNotNull(info.manufacturer)
        assertNotNull(info.model)
        assertNotNull(info.androidVersion)
        assertTrue(info.batteryLevel >= 0)
        assertTrue(info.screenWidth > 0)
        assertTrue(info.screenHeight > 0)
        assertNotNull(info.orientation)
    }

    @Test
    fun testSettingsManager_bootStartPreferences() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settingsManager = SettingsManager(context)

        settingsManager.isBootStartEnabled = true
        assertTrue(settingsManager.isBootStartEnabled)
        assertTrue(SettingsManager.isBootStartEnabled(context))

        settingsManager.isBootStartEnabled = false
        assertFalse(settingsManager.isBootStartEnabled)
        assertFalse(SettingsManager.isBootStartEnabled(context))
    }

    @Test
    fun testBootReceiver_handleBootCompletedIntent() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settingsManager = SettingsManager(context)
        settingsManager.isBootStartEnabled = true

        val bootReceiver = BootReceiver()
        val intent = Intent(Intent.ACTION_BOOT_COMPLETED)

        // Trigger onReceive to verify no exceptions are thrown and it handles the broadcast
        bootReceiver.onReceive(context, intent)
    }
}
