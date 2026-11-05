package com.example.mysms

import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.PhoneNumberUtils
import android.telephony.SmsManager
import android.telephony.SubscriptionManager

data class Conversation(
    val threadId: Long,
    val address: String,
    val name: String,
    val snippet: String,
    val date: Long,
    val unread: Boolean
)

data class Msg(
    val id: Long,
    val body: String,
    val date: Long,
    val isMine: Boolean,
    val type: Int,
    val status: Int,
    val reaction: String?
)

data class SearchHit(
    val id: Long,
    val threadId: Long,
    val address: String,
    val name: String,
    val body: String,
    val date: Long
)

data class SimInfo(val subId: Int, val slot: Int, val name: String)

class SmsRepository(private val ctx: Context) {

    private val prefs by lazy { ctx.getSharedPreferences("mysms", Context.MODE_PRIVATE) }
    private val nameCache = HashMap<String, String>()

    // ---------------------------------------------------------------- Kontaktlar
    fun displayName(address: String): String {
        if (address.isBlank()) return address
        nameCache[address]?.let { return it }
        var result = address
        var ok = true
        try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address)
            )
            ctx.contentResolver.query(
                uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getString(0)
                    if (!n.isNullOrBlank()) result = n
                }
            }
        } catch (e: Exception) {
            ok = false
        }
        if (ok) nameCache[address] = result
        return result
    }

    // ---------------------------------------------------------------- Bloklamak
    private fun blockedSet(): Set<String> =
        HashSet(prefs.getStringSet("blocked", emptySet()) ?: emptySet())

    fun blockedList(): List<String> = blockedSet().sorted()

    private fun sameNumber(a: String, b: String): Boolean {
        val aDigits = a.any { it.isDigit() }
        val bDigits = b.any { it.isDigit() }
        return if (aDigits && bDigits) {
            try {
                PhoneNumberUtils.compare(a, b)
            } catch (e: Exception) {
                a == b
            }
        } else {
            a.equals(b, ignoreCase = true)
        }
    }

    fun isBlocked(address: String): Boolean = blockedSet().any { sameNumber(it, address) }

    fun block(address: String) {
        val s = blockedSet().toMutableSet()
        s.add(address)
        prefs.edit().putStringSet("blocked", s).apply()
    }

    fun unblock(address: String) {
        val s = blockedSet().filter { !sameNumber(it, address) }.toSet()
        prefs.edit().putStringSet("blocked", s).apply()
    }

    // ---------------------------------------------------------------- Reaksiýalar (diňe bu telefonda)
    fun setReaction(msgId: Long, emoji: String?) {
        val e = prefs.edit()
        if (emoji == null) e.remove("react_$msgId") else e.putString("react_$msgId", emoji)
        e.apply()
    }

    // ---------------------------------------------------------------- SIM saýlamak
    fun sims(): List<SimInfo> = try {
        val sm = ctx.getSystemService(SubscriptionManager::class.java)
        val list = sm.activeSubscriptionInfoList ?: emptyList()
        list.map {
            SimInfo(
                it.subscriptionId, it.simSlotIndex,
                (it.displayName ?: it.carrierName ?: "").toString()
            )
        }.sortedBy { it.slot }
    } catch (e: Exception) {
        emptyList()
    }

    fun selectedSubId(): Int {
        val list = sims()
        val saved = prefs.getInt("sim", -1)
        if (saved != -1 && list.any { it.subId == saved }) return saved
        val def = SubscriptionManager.getDefaultSmsSubscriptionId()
        if (def != SubscriptionManager.INVALID_SUBSCRIPTION_ID && list.any { it.subId == def }) return def
        return list.firstOrNull()?.subId ?: -1
    }

    fun setSelectedSubId(subId: Int) {
        prefs.edit().putInt("sim", subId).apply()
    }

    @Suppress("DEPRECATION")
    private fun smsManager(): SmsManager {
        val subId = selectedSubId()
        return if (Build.VERSION.SDK_INT >= 31) {
            val base = ctx.getSystemService(SmsManager::class.java)
            if (subId >= 0) base.createForSubscriptionId(subId) else base
        } else {
            if (subId >= 0) SmsManager.getSmsManagerForSubscriptionId(subId) else SmsManager.getDefault()
        }
    }

    // ---------------------------------------------------------------- Okamak
    fun conversations(): List<Conversation> {
        val map = LinkedHashMap<Long, Conversation>()
        val unread = HashSet<Long>()
        val blocked = blockedSet()
        try {
            ctx.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(
                    Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
                    Telephony.Sms.DATE, Telephony.Sms.READ, Telephony.Sms.TYPE
                ),
                null, null, "date DESC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val t = c.getLong(0)
                    if (c.getInt(4) == 0 && c.getInt(5) == Telephony.Sms.MESSAGE_TYPE_INBOX) unread.add(t)
                    if (!map.containsKey(t)) {
                        map[t] = Conversation(
                            t, c.getString(1) ?: "", "", c.getString(2) ?: "", c.getLong(3), false
                        )
                    }
                }
            }
        } catch (e: Exception) {
            return emptyList()
        }
        return map.values
            .filter { conv -> blocked.none { sameNumber(it, conv.address) } }
            .map { it.copy(name = displayName(it.address), unread = unread.contains(it.threadId)) }
    }

    fun messages(threadId: Long): List<Msg> {
        val list = ArrayList<Msg>()
        try {
            ctx.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(
                    Telephony.Sms._ID, Telephony.Sms.BODY, Telephony.Sms.DATE,
                    Telephony.Sms.TYPE, Telephony.Sms.STATUS
                ),
                "${Telephony.Sms.THREAD_ID} = ?", arrayOf(threadId.toString()), "date ASC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val type = c.getInt(3)
                    list.add(
                        Msg(
                            id, c.getString(1) ?: "", c.getLong(2),
                            type != Telephony.Sms.MESSAGE_TYPE_INBOX,
                            type, c.getInt(4), prefs.getString("react_$id", null)
                        )
                    )
                }
            }
        } catch (e: Exception) {
        }
        return list
    }

    /** Habarlaryň içinden gözleg (uly/kiçi harpa seresap däl, ähli diller üçin). */
    fun search(q: String): List<SearchHit> {
        val out = ArrayList<SearchHit>()
        val blocked = blockedSet()
        try {
            ctx.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(
                    Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY, Telephony.Sms.DATE
                ),
                null, null, "date DESC"
            )?.use { c ->
                while (c.moveToNext() && out.size < 200) {
                    val body = c.getString(3) ?: ""
                    val addr = c.getString(2) ?: ""
                    if (body.contains(q, ignoreCase = true) || addr.contains(q, ignoreCase = true)) {
                        if (blocked.any { sameNumber(it, addr) }) continue
                        out.add(SearchHit(c.getLong(0), c.getLong(1), addr, displayName(addr), body, c.getLong(4)))
                    }
                }
            }
        } catch (e: Exception) {
        }
        return out
    }

    fun markRead(threadId: Long) {
        try {
            val v = ContentValues().apply { put(Telephony.Sms.READ, 1) }
            ctx.contentResolver.update(
                Telephony.Sms.CONTENT_URI, v,
                "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.READ} = 0",
                arrayOf(threadId.toString())
            )
        } catch (e: Exception) {
        }
    }

    fun deleteThread(threadId: Long): Boolean = try {
        ctx.contentResolver.delete(
            Telephony.Sms.CONTENT_URI, "${Telephony.Sms.THREAD_ID} = ?", arrayOf(threadId.toString())
        ) > 0
    } catch (e: Exception) {
        false
    }

    fun deleteMessage(id: Long): Boolean = try {
        setReaction(id, null)
        ctx.contentResolver.delete(Uri.parse("content://sms/$id"), null, null) > 0
    } catch (e: Exception) {
        false
    }

    fun threadIdFor(address: String): Long = try {
        Telephony.Threads.getOrCreateThreadId(ctx, address)
    } catch (e: Exception) {
        0L
    }

    // ---------------------------------------------------------------- Ugratmak
    private fun insertOutgoing(address: String, text: String): Uri? = try {
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, text)
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_PENDING)
            put(Telephony.Sms.THREAD_ID, Telephony.Threads.getOrCreateThreadId(ctx, address))
        }
        ctx.contentResolver.insert(Telephony.Sms.Outbox.CONTENT_URI, values)
    } catch (e: Exception) {
        null // default SMS programma däl: bazaga ýazyp bolmaýar
    }

    private fun statusIntent(uri: Uri, act: String, mutable: Boolean): PendingIntent {
        val i = Intent(ctx, SmsStatusReceiver::class.java).apply {
            action = act
            data = uri
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        flags = if (mutable) {
            if (Build.VERSION.SDK_INT >= 31) flags or PendingIntent.FLAG_MUTABLE else flags
        } else {
            flags or PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getBroadcast(ctx, 0, i, flags)
    }

    /** Habar ugratmak. Ýalňyşlyk bolsa Result.failure gaýtarýar. */
    fun send(address: String, text: String): Result<Unit> = runCatching {
        val rowUri = insertOutgoing(address, text)
        try {
            val sms = smsManager()
            val parts = sms.divideMessage(text)
            val sentPi = rowUri?.let { statusIntent(it, SmsStatusReceiver.ACTION_SENT, false) }
            val deliveredPi = rowUri?.let { statusIntent(it, SmsStatusReceiver.ACTION_DELIVERED, true) }
            if (parts.size > 1) {
                val sentList = ArrayList<PendingIntent?>()
                val delList = ArrayList<PendingIntent?>()
                for (i in parts.indices) {
                    val last = i == parts.size - 1
                    sentList.add(if (last) sentPi else null)
                    delList.add(if (last) deliveredPi else null)
                }
                sms.sendMultipartTextMessage(address, null, parts, sentList, delList)
            } else {
                sms.sendTextMessage(address, null, text, sentPi, deliveredPi)
            }
        } catch (e: Exception) {
            if (rowUri != null) {
                try {
                    ctx.contentResolver.delete(rowUri, null, null)
                } catch (ex: Exception) {
                }
            }
            throw e
        }
        Unit
    }

    fun saveIncoming(address: String, body: String, date: Long, read: Boolean) {
        try {
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, address)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, date)
                put(Telephony.Sms.READ, if (read) 1 else 0)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
                put(Telephony.Sms.THREAD_ID, Telephony.Threads.getOrCreateThreadId(ctx, address))
            }
            ctx.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
        } catch (e: Exception) {
        }
    }
}
