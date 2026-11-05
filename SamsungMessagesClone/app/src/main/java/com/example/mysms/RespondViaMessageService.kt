package com.example.mysms

import android.app.Service
import android.content.Intent
import android.os.IBinder

class RespondViaMessageService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val to = intent?.data?.schemeSpecificPart?.substringBefore('?')
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)
        if (!to.isNullOrBlank() && !text.isNullOrBlank()) {
            SmsRepository(applicationContext).send(to, text)
        }
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
