package com.makia.hedgehogsms.data

import android.content.Context
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SubscriptionSnapshot(val activeSlots: Map<Long, Int>?, val logicalSlotCount: Int)

interface SubscriptionSource {
    suspend fun snapshot(): SubscriptionSnapshot
}

class AndroidSubscriptionSource(context: Context) : SubscriptionSource {
    private val subscriptions = context.getSystemService(SubscriptionManager::class.java)
    private val telephony = context.getSystemService(TelephonyManager::class.java)

    override suspend fun snapshot(): SubscriptionSnapshot = withContext(Dispatchers.IO) {
        val slots = try {
            subscriptions.activeSubscriptionInfoList.orEmpty().associate { it.subscriptionId.toLong() to it.simSlotIndex }
        } catch (_: SecurityException) {
            null
        }
        val count = if (Build.VERSION.SDK_INT >= 30) telephony.activeModemCount else @Suppress("DEPRECATION") telephony.phoneCount
        SubscriptionSnapshot(slots, count.coerceAtLeast(1))
    }
}
