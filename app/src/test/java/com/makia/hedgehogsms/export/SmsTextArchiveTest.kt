package com.makia.hedgehogsms.export

import com.makia.hedgehogsms.data.SmsRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.ZoneOffset
import java.util.zip.ZipInputStream

class SmsTextArchiveTest {
    private val zone = ZoneOffset.UTC
    private val slots = listOf(
        SlotIdentity(1, 0, "+86 138-0013-8000"),
        SlotIdentity(2, 1, "13900139000"),
    )

    @Test fun `each slot is named by its phone number`() {
        val parts = planTextParts(
            listOf(sms(1, 2_000, 1, "卡一"), sms(2, 3_000, 2, "卡二")),
            slots,
            logicalSlotCount = 2,
            zone = zone,
        )
        assertEquals(listOf("+8613800138000.txt", "13900139000.txt"), parts.map { it.fileName })
        assertTrue(parts[0].body.contains("卡一"))
        assertTrue(parts[0].body.contains("卡槽: 卡槽1"))
        assertTrue(parts[0].body.contains("1970-01-01 00:00:02"))
        assertTrue(parts[1].body.contains("卡二"))
        assertTrue(parts[1].body.contains("1970-01-01 00:00:03"))
    }

    @Test fun `a slot with more than one batch splits into numbered txt files`() {
        val messages = (1L..5L).map { sms(it, it * 1_000, 1, "正文$it") }
        val parts = planTextParts(messages, slots, logicalSlotCount = 2, batchSize = 2, zone = zone)
        assertEquals(
            listOf("+8613800138000_001.txt", "+8613800138000_002.txt", "+8613800138000_003.txt"),
            parts.map { it.fileName },
        )
        assertEquals(listOf(2, 2, 1), parts.map { it.messageCount })
        assertTrue(parts[0].body.contains("正文1"))
        assertTrue(parts[0].body.contains("正文2"))
        assertTrue(parts[2].body.contains("正文5"))
        assertTrue(parts[0].body.contains("本批: 1/3"))
        val firstBodyIndex = parts[0].body.indexOf("正文1")
        val secondBodyIndex = parts[0].body.indexOf("正文2")
        assertTrue(firstBodyIndex in 0 until secondBodyIndex)
    }

    @Test fun `missing number falls back to the slot name and unknown stays unknown`() {
        val parts = planTextParts(
            listOf(
                sms(1, 1_000, 1, "没有号码"),
                sms(2, 2_000, null, "没有卡槽"),
                sms(3, 3_000, 99, "旧订阅"),
            ),
            listOf(SlotIdentity(1, 0, " ")),
            logicalSlotCount = 2,
            zone = zone,
        )
        assertEquals(listOf("卡槽1.txt", "未知卡槽.txt"), parts.map { it.fileName })
        assertTrue(parts[1].body.contains("没有卡槽"))
        assertTrue(parts[1].body.contains("旧订阅"))
        assertEquals(2, parts[1].messageCount)
    }

    @Test fun `the same number on two slots is not written into one file`() {
        val same = listOf(
            SlotIdentity(1, 0, "13800138000"),
            SlotIdentity(2, 1, "13800138000"),
        )
        val parts = planTextParts(
            listOf(sms(1, 1_000, 1, "甲"), sms(2, 1_000, 2, "乙")),
            same,
            logicalSlotCount = 2,
            zone = zone,
        )
        assertEquals(listOf("13800138000_卡槽1.txt", "13800138000_卡槽2.txt"), parts.map { it.fileName })
        assertTrue(parts[0].body.contains("甲"))
        assertTrue(parts[1].body.contains("乙"))
    }

    @Test fun `no phone permission puts every message in the unknown file`() {
        val parts = planTextParts(
            listOf(sms(1, 1_000, 1, "看不出卡")),
            identities = null,
            logicalSlotCount = 2,
            zone = zone,
        )
        assertEquals("未知卡槽.txt", parts.single().fileName)
    }

    @Test fun `zip keeps every txt name and body`() {
        val parts = planTextParts(
            listOf(sms(1, 1_000, null, "第一行\n第二行")),
            identities = null,
            logicalSlotCount = 2,
            zone = zone,
        )
        val bytes = ByteArrayOutputStream()
        zipTexts(parts, bytes)
        val unzipped = unzip(bytes.toByteArray())
        assertEquals(listOf("未知卡槽.txt"), unzipped.map { it.first })
        assertTrue(unzipped.single().second.contains("第一行\n第二行"))
    }

    @Test fun `empty mailbox produces no files`() {
        assertTrue(planTextParts(emptyList(), slots, 2).isEmpty())
    }

    private fun sms(id: Long, dateMillis: Long, subscriptionId: Long?, body: String) =
        SmsRecord(id, dateMillis, 1, "10086", body, subscriptionId)

    private fun unzip(bytes: ByteArray): List<Pair<String, String>> =
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            buildList {
                while (true) {
                    val entry = zip.nextEntry ?: break
                    add(entry.name to zip.readBytes().toString(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
        }
}
