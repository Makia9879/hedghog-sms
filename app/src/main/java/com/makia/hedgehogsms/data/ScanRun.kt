package com.makia.hedgehogsms.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class ScanStatus { IDLE, RUNNING, PAUSED, WAITING_BATTERY, WAITING_THERMAL, WAITING_PERMISSION, FAILED, COMPLETED }

@Entity(tableName = "scan_run")
data class ScanRun(
    @PrimaryKey val id: String = HISTORY_ID,
    val generation: Long = 0,
    val status: ScanStatus = ScanStatus.IDLE,
    val upperDate: Long? = null,
    val upperId: Long? = null,
    val cursorDate: Long? = null,
    val cursorId: Long? = null,
    val processed: Int = 0,
    val estimated: Int? = null,
    val lastError: String? = null,
    val startedAt: Long? = null,
    val updatedAt: Long = 0,
    val completedAt: Long? = null,
) {
    val approximatePercent: Int?
        get() = estimated?.let {
            if (it == 0) if (status == ScanStatus.COMPLETED) 100 else 0
            else ((processed.coerceAtMost(it) * 100L) / it).toInt()
        }
    companion object { const val HISTORY_ID = "history" }
}
