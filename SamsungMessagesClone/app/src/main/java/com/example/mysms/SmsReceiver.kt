package com.example.mysms

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (msgs.isNullOrEmpty()) return
        val address = msgs[0].originatingAddress ?: return
        val body = msgs.joinToString("") { it.messageBody ?: "" }
        val repo = SmsRepository(context.applicationContext)

        if (repo.isBlocked(address)) {
            // Bloklanan nomer: bazada saklanýar (okalan hökmünde), bildiriş ýok, sanawda görünmeýär
            repo.saveIncoming(address, body, System.currentTimeMillis(), true)
            return
        }

        repo.saveIncoming(address, body, System.currentTimeMillis(), false)
        try {
            notify(context, repo.displayName(address), address, body)
        } catch (e: Exception) {
        }
    }

    private fun notify(ctx: Context, title: String, address: String, body: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("sms", "Habarlar", NotificationManager.IMPORTANCE_HIGH)
        )
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, "sms")
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(ctx).notify(address.hashCode(), n)
    }
}
