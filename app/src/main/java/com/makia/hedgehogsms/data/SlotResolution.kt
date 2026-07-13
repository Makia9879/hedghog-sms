package com.makia.hedgehogsms.data

enum class SlotMappingStatus { RESOLVED, UNKNOWN_NO_SUB_ID, UNKNOWN_INACTIVE_SUBSCRIPTION, UNKNOWN_INVALID_SLOT, UNKNOWN_PERMISSION }

data class SlotResolution(val slotIndex: Int?, val status: SlotMappingStatus)

class SlotResolver {
    fun resolve(subscriptionId: Long?, activeSlots: Map<Long, Int>?, logicalSlotCount: Int): SlotResolution {
        if (subscriptionId == null || subscriptionId < 0) return SlotResolution(null, SlotMappingStatus.UNKNOWN_NO_SUB_ID)
        if (activeSlots == null) return SlotResolution(null, SlotMappingStatus.UNKNOWN_PERMISSION)
        val slot = activeSlots[subscriptionId] ?: return SlotResolution(null, SlotMappingStatus.UNKNOWN_INACTIVE_SUBSCRIPTION)
        if (slot !in 0 until logicalSlotCount) return SlotResolution(null, SlotMappingStatus.UNKNOWN_INVALID_SLOT)
        return SlotResolution(slot, SlotMappingStatus.RESOLVED)
    }
}
