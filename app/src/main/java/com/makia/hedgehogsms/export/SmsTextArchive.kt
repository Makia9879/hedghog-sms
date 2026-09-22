package com.makia.hedgehogsms.export

import com.makia.hedgehogsms.data.SlotResolver
import com.makia.hedgehogsms.data.SmsRecord
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 每个 TXT 最多放这么多条。一个卡槽超过这个数就拆成多个文件。 */
const val MESSAGES_PER_FILE = 500

data class SlotIdentity(
    val subscriptionId: Long,
    val slotIndex: Int,
    val phoneNumber: String?,
)

data class TextPart(
    val fileName: String,
    val body: String,
    val messageCount: Int,
)

fun planTextParts(
    messages: List<SmsRecord>,
    identities: List<SlotIdentity>?,
    logicalSlotCount: Int,
    batchSize: Int = MESSAGES_PER_FILE,
    zone: ZoneId = ZoneId.systemDefault(),
): List<TextPart> {
    if (messages.isEmpty()) return emptyList()
    val size = batchSize.coerceAtLeast(1)
    val resolver = SlotResolver()
    val activeSlots = identities?.associate { it.subscriptionId to it.slotIndex }
    val grouped = messages.groupBy { message ->
        resolver.resolve(message.subscriptionId, activeSlots, logicalSlotCount).slotIndex
    }
    val orderedKeys = grouped.keys.sortedWith(compareBy(nullsLast()) { it })
    val stems = fileStems(orderedKeys, identities)
    return orderedKeys.flatMap { slotIndex ->
        val slotMessages = grouped.getValue(slotIndex).sortedWith(compareBy<SmsRecord> { it.dateMillis }.thenBy { it.id })
        val batches = slotMessages.chunked(size)
        val names = exportFileNames(stems.getValue(slotIndex), batches.size)
        val number = phoneForSlot(slotIndex, identities)
        batches.mapIndexed { index, batch ->
            TextPart(
                fileName = names[index],
                body = renderBatch(
                    slotIndex = slotIndex,
                    phoneNumber = number,
                    batchIndex = index + 1,
                    batchCount = batches.size,
                    messages = batch,
                    zone = zone,
                ),
                messageCount = batch.size,
            )
        }
    }
}

fun archiveZipName(nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val stamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(zone).format(Instant.ofEpochMilli(nowMillis))
    return "短信导出_$stamp.zip"
}

fun zipTexts(parts: List<TextPart>, output: OutputStream) {
    ZipOutputStream(output).use { zip ->
        parts.forEach { part ->
            zip.putNextEntry(ZipEntry(part.fileName))
            zip.write(part.body.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }
}

internal fun sanitizePhone(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    val digits = trimmed.filter { it.isDigit() }
    if (digits.isEmpty()) return ""
    return if (trimmed.startsWith("+")) "+$digits" else digits
}

internal fun archiveStem(slotIndex: Int?, phoneNumber: String?): String {
    val number = phoneNumber?.let(::sanitizePhone)?.takeIf { it.isNotEmpty() }
    if (number != null) return number
    return if (slotIndex != null) "卡槽${slotIndex + 1}" else "未知卡槽"
}

internal fun exportFileNames(stem: String, partCount: Int): List<String> {
    if (partCount <= 1) return listOf("$stem.txt")
    return (1..partCount).map { index -> "${stem}_${index.toString().padStart(3, '0')}.txt" }
}

private fun fileStems(slotIndexes: List<Int?>, identities: List<SlotIdentity>?): Map<Int?, String> {
    val bases = slotIndexes.associateWith { archiveStem(it, phoneForSlot(it, identities)) }
    val duplicated = bases.values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    return bases.mapValues { (slotIndex, base) ->
        if (base !in duplicated) base else "${base}_${slotLabel(slotIndex)}"
    }
}

private fun phoneForSlot(slotIndex: Int?, identities: List<SlotIdentity>?): String? {
    if (slotIndex == null) return null
    return identities?.firstOrNull { it.slotIndex == slotIndex }?.phoneNumber
}

private fun slotLabel(slotIndex: Int?) = if (slotIndex != null) "卡槽${slotIndex + 1}" else "未知卡槽"

private fun renderBatch(
    slotIndex: Int?,
    phoneNumber: String?,
    batchIndex: Int,
    batchCount: Int,
    messages: List<SmsRecord>,
    zone: ZoneId,
): String = buildString {
    appendLine("卡槽: ${slotLabel(slotIndex)}")
    appendLine("号码: ${phoneNumber?.takeIf { it.isNotBlank() } ?: "无"}")
    appendLine("本批: $batchIndex/$batchCount")
    appendLine("条数: ${messages.size}")
    messages.forEach { message ->
        appendLine("----")
        appendLine("时间: ${formatExportTime(message.dateMillis, zone)}")
        appendLine("方向: ${smsDirectionLabel(message.type)}")
        appendLine("对方: ${message.sender?.takeIf { it.isNotBlank() } ?: "未知"}")
        appendLine("内容:")
        appendLine(message.body)
    }
}

internal fun formatExportTime(millis: Long, zone: ZoneId): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(zone).format(Instant.ofEpochMilli(millis))

internal fun smsDirectionLabel(type: Int) = when (type) {
    1 -> "接收"
    2 -> "发送"
    3 -> "草稿"
    4 -> "发件箱"
    5 -> "发送失败"
    6 -> "排队发送"
    else -> "其他($type)"
}
