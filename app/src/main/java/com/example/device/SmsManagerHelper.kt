package com.example.device

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat

data class SmsMessageItem(
    val id: String,
    val address: String,
    val body: String,
    val date: Long,
    val type: Int, // 1 = Inbox, 2 = Sent
    val read: Boolean
)

class SmsManagerHelper(private val context: Context) {

    fun getSmsList(limit: Int = 50): List<SmsMessageItem> {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_SMS
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            // Return helpful status message
            return listOf(
                SmsMessageItem(
                    id = "system_notice",
                    address = "Ghost System",
                    body = "SMS read permission is not granted on Android. Grant 'SMS' permission in phone settings to sync live messages.",
                    date = System.currentTimeMillis(),
                    type = 1,
                    read = true
                )
            )
        }

        val messages = mutableListOf<SmsMessageItem>()
        val uri = Uri.parse("content://sms")
        val projection = arrayOf("_id", "address", "body", "date", "type", "read")

        try {
            val cursor = context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                "date DESC LIMIT $limit"
            )

            cursor?.use {
                val idIdx = it.getColumnIndex("_id")
                val addrIdx = it.getColumnIndex("address")
                val bodyIdx = it.getColumnIndex("body")
                val dateIdx = it.getColumnIndex("date")
                val typeIdx = it.getColumnIndex("type")
                val readIdx = it.getColumnIndex("read")

                while (it.moveToNext()) {
                    val id = if (idIdx != -1) it.getString(idIdx) else ""
                    val address = if (addrIdx != -1) it.getString(addrIdx) ?: "Unknown" else "Unknown"
                    val body = if (bodyIdx != -1) it.getString(bodyIdx) ?: "" else ""
                    val date = if (dateIdx != -1) it.getLong(dateIdx) else System.currentTimeMillis()
                    val type = if (typeIdx != -1) it.getInt(typeIdx) else 1
                    val read = if (readIdx != -1) it.getInt(readIdx) == 1 else true

                    messages.add(
                        SmsMessageItem(
                            id = id,
                            address = address,
                            body = body,
                            date = date,
                            type = type,
                            read = read
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("SmsManagerHelper", "Error reading SMS", e)
        }

        return messages
    }

    fun sendSms(address: String, messageText: String): Boolean {
        return try {
            val hasSendPermission = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.SEND_SMS
            ) == PackageManager.PERMISSION_GRANTED

            if (hasSendPermission) {
                val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.getSystemService(SmsManager::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    SmsManager.getDefault()
                }

                val parts = smsManager.divideMessage(messageText)
                if (parts.size > 1) {
                    smsManager.sendMultipartTextMessage(address, null, parts, null, null)
                } else {
                    smsManager.sendTextMessage(address, null, messageText, null, null)
                }
                true
            } else {
                // Launch SMS intent as fallback
                val intent = Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("smsto:$address")
                    putExtra("sms_body", messageText)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            }
        } catch (e: Exception) {
            Log.e("SmsManagerHelper", "Error sending SMS", e)
            false
        }
    }
}
