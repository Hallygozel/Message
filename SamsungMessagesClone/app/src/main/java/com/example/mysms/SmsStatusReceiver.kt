package com.example.mysms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage

/** Habar ugradylandygy (SENT) we alyja eltilendigi (DELIVERED) barada netijäni bazada täzeleýär. */
class SmsStatusReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_SENT = "com.example.mysms.SMS_SENT"
        const val ACTION_DELIVERED = "com.example.mysms.SMS_DELIVERED"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val uri = intent.data ?: return
        val values = ContentValues()
        when (intent.action) {
            ACTION_SENT -> {
                values.put(
                    Telephony.Sms.TYPE,
                    if (resultCode == Activity.RESULT_OK) Telephony.Sms.MESSAGE_TYPE_SENT
                    else Telephony.Sms.MESSAGE_TYPE_FAILED
                )
            }
            ACTION_DELIVERED -> {
                var status = 0
                try {
                    val pdu = intent.getByteArrayExtra("pdu")
                    val format = intent.getStringExtra("format")
                    if (pdu != null) status = SmsMessage.createFromPdu(pdu, format).status
                } catch (e: Exception) {
                }
                when {
                    status == 0 -> values.put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_COMPLETE)
                    status >= 64 -> values.put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_FAILED)
                    else -> return // entek gutarmady
                }
            }
            else -> return
        }
        try {
            context.contentResolver.update(uri, values, null, null)
        } catch (e: Exception) {
        }
    }
}
