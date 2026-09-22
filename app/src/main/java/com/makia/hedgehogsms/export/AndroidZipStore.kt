package com.makia.hedgehogsms.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.makia.hedgehogsms.data.SmsKeyset
import com.makia.hedgehogsms.data.SmsRecord
import com.makia.hedgehogsms.data.SmsSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SlotDirectory(val identities: List<SlotIdentity>?, val logicalSlotCount: Int)

sealed interface ExportLocation {
    data object Downloads : ExportLocation
    data class Folder(val uri: Uri) : ExportLocation
}

class AndroidSlotDirectory(private val context: Context) {
    suspend fun load(): SlotDirectory = withContext(Dispatchers.IO) {
        val telephony = context.getSystemService(TelephonyManager::class.java)
        val count = if (Build.VERSION.SDK_INT >= 30) {
            telephony.activeModemCount
        } else {
            @Suppress("DEPRECATION")
            telephony.phoneCount
        }.coerceAtLeast(1)
        val phoneGranted = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_PHONE_STATE,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!phoneGranted) return@withContext SlotDirectory(null, count)
        val subscriptions = context.getSystemService(SubscriptionManager::class.java)
        val numbersGranted = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_PHONE_NUMBERS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val identities = try {
            subscriptions.activeSubscriptionInfoList.orEmpty().map { info ->
                SlotIdentity(
                    subscriptionId = info.subscriptionId.toLong(),
                    slotIndex = info.simSlotIndex,
                    phoneNumber = if (numbersGranted) readNumber(subscriptions, info) else null,
                )
            }
        } catch (_: SecurityException) {
            null
        }
        SlotDirectory(identities, count)
    }

    private fun readNumber(subscriptions: SubscriptionManager, info: SubscriptionInfo): String? {
        val raw = try {
            if (Build.VERSION.SDK_INT >= 33) {
                subscriptions.getPhoneNumber(info.subscriptionId)
            } else {
                @Suppress("DEPRECATION")
                info.number
            }
        } catch (_: SecurityException) {
            null
        }
        return raw?.trim()?.takeIf { it.isNotEmpty() }
    }
}

suspend fun SmsSource.readAll(onPage: (count: Int) -> Unit = {}): List<SmsRecord> {
    val all = mutableListOf<SmsRecord>()
    var keyset: SmsKeyset? = null
    while (true) {
        val page = page(keyset, limit = 25, fence = null)
        if (page.isEmpty()) break
        all += page
        val last = page.last()
        val next = SmsKeyset(last.dateMillis, last.id)
        if (next == keyset) break
        keyset = next
        onPage(all.size)
    }
    return all
}

class AndroidZipStore(private val context: Context) {
    fun save(location: ExportLocation, fileName: String, parts: List<TextPart>): Uri = when (location) {
        ExportLocation.Downloads -> saveDownload(fileName, parts)
        is ExportLocation.Folder -> saveTree(location.uri, fileName, parts)
    }

    private fun saveDownload(fileName: String, parts: List<TextPart>): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, ZIP_TYPE)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("无法在下载文件夹创建压缩包")
        try {
            write(uri, parts)
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private fun saveTree(treeUri: Uri, fileName: String, parts: List<TextPart>): Uri {
        val resolver = context.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        val displayName = fileName.removeSuffix(".zip")
        val uri = DocumentsContract.createDocument(resolver, parent, ZIP_TYPE, displayName)
            ?: error("无法在所选文件夹创建压缩包")
        try {
            write(uri, parts)
            return uri
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private fun write(uri: Uri, parts: List<TextPart>) {
        context.contentResolver.openOutputStream(uri)?.use { zipTexts(parts, it) }
            ?: error("无法写入压缩包")
    }

    companion object {
        private const val ZIP_TYPE = "application/zip"
        const val PREFS = "sms_export"
        const val TREE_URI = "tree_uri"

        fun rememberFolder(context: Context, uri: Uri) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(TREE_URI, uri.toString())
                .apply()
        }

        fun clearFolder(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(TREE_URI)
                .apply()
        }

        fun location(context: Context): ExportLocation {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(TREE_URI, null)
                ?: return ExportLocation.Downloads
            val uri = Uri.parse(raw)
            val writable = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
            return if (writable) ExportLocation.Folder(uri) else ExportLocation.Downloads
        }
    }
}

fun locationLabel(location: ExportLocation): String = when (location) {
    ExportLocation.Downloads -> "下载文件夹"
    is ExportLocation.Folder -> folderDisplayName(location.uri)
}

fun folderDisplayName(uri: Uri): String {
    val raw = uri.lastPathSegment.orEmpty().substringAfter(':').substringAfterLast('/')
    return Uri.decode(raw).trim().ifBlank { "已选择的文件夹" }
}
