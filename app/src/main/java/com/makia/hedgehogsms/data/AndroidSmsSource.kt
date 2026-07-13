package com.makia.hedgehogsms.data

import android.content.ContentResolver
import android.content.ContentUris
import android.os.Bundle
import android.provider.Telephony
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SmsPermissionUnavailableException(cause: SecurityException) : RuntimeException(cause)

class AndroidSmsSource(
    private val resolver: ContentResolver,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SmsSource {
    override suspend fun page(keyset: SmsKeyset?, limit: Int, fence: SmsFence?): List<SmsRecord> = withContext(ioDispatcher) {
        val selectionParts = mutableListOf<String>()
        val args = mutableListOf<String>()
        keyset?.let {
            selectionParts += "(${Telephony.Sms.DATE} < ? OR (${Telephony.Sms.DATE} = ? AND ${Telephony.Sms._ID} < ?))"
            args += listOf(it.dateMillis.toString(), it.dateMillis.toString(), it.id.toString())
        }
        fence?.let {
            selectionParts += "(${Telephony.Sms.DATE} < ? OR (${Telephony.Sms.DATE} = ? AND ${Telephony.Sms._ID} <= ?))"
            args += listOf(it.dateMillis.toString(), it.dateMillis.toString(), it.id.toString())
        }
        val queryArgs = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selectionParts.takeIf { it.isNotEmpty() }?.joinToString(" AND "))
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args.toTypedArray())
            // Some OEM SMS providers accept structured selection/limit but do
            // not correctly compose a multi-column sort from SORT_COLUMNS plus
            // one SORT_DIRECTION. The standard SQL sort-order argument keeps
            // the keyset order explicit without adding a private LIMIT clause.
            putString(
                ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                "${Telephony.Sms.DATE} DESC, ${Telephony.Sms._ID} DESC",
            )
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit.coerceIn(1, 25))
        }
        try {
            resolver.query(Telephony.Sms.CONTENT_URI, PROJECTION, queryArgs, null)?.use { cursor ->
                buildList {
                    while (cursor.moveToNext() && size < limit.coerceIn(1, 25)) add(cursor.toRecord())
                }
            }.orEmpty()
        } catch (error: SecurityException) {
            throw SmsPermissionUnavailableException(error)
        }
    }

    override suspend fun byId(id: Long): SmsRecord? = withContext(ioDispatcher) {
        try {
            resolver.query(ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id), PROJECTION, null, null, null)
                ?.use { if (it.moveToFirst()) it.toRecord() else null }
        } catch (error: SecurityException) {
            throw SmsPermissionUnavailableException(error)
        }
    }

    override suspend fun estimateCount(fence: SmsFence?): Int = withContext(ioDispatcher) {
        val selection = fence?.let {
            "(${Telephony.Sms.DATE} < ? OR (${Telephony.Sms.DATE} = ? AND ${Telephony.Sms._ID} <= ?))"
        }
        val args = fence?.let { arrayOf(it.dateMillis.toString(), it.dateMillis.toString(), it.id.toString()) }
        try {
            // Projection is metadata-only: no BODY or ADDRESS is requested or materialized.
            resolver.query(Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms._ID), selection, args, null)
                ?.use { it.count } ?: 0
        } catch (error: SecurityException) {
            throw SmsPermissionUnavailableException(error)
        }
    }

    private fun android.database.Cursor.toRecord() = SmsRecord(
        id = getLong(getColumnIndexOrThrow(Telephony.Sms._ID)),
        dateMillis = getLong(getColumnIndexOrThrow(Telephony.Sms.DATE)),
        type = getInt(getColumnIndexOrThrow(Telephony.Sms.TYPE)),
        sender = getString(getColumnIndexOrThrow(Telephony.Sms.ADDRESS)),
        body = getString(getColumnIndexOrThrow(Telephony.Sms.BODY)).orEmpty(),
        subscriptionId = getColumnIndex(Telephony.Sms.SUBSCRIPTION_ID).takeIf { it >= 0 }?.let(::getLong),
    )

    private companion object {
        val PROJECTION = arrayOf(
            Telephony.Sms._ID, Telephony.Sms.DATE, Telephony.Sms.TYPE,
            Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.SUBSCRIPTION_ID,
        )
    }
}
