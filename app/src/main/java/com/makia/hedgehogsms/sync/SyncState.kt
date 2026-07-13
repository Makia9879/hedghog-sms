package com.makia.hedgehogsms.sync

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sync_state")
data class SyncState(
    @PrimaryKey val id: String = ID,
    val requestedGeneration: Long = 0,
    val completedGeneration: Long = 0,
    val highWaterId: Long = 0,
    val cursorDate: Long? = null,
    val cursorId: Long? = null,
    val baselineHighWaterId: Long = 0,
    val updatedAt: Long = 0,
) {
    companion object { const val ID = "incremental" }
}
