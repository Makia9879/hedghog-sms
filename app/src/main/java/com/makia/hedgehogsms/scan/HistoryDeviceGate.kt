package com.makia.hedgehogsms.scan

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import com.makia.hedgehogsms.data.ScanStatus

data class HistoryDeviceState(
    val charging: Boolean,
    val batteryPercent: Int,
    val thermalStatus: Int,
    val batteryTemperatureC: Double,
)

fun interface HistoryDeviceStateSource { fun snapshot(): HistoryDeviceState }

object HistoryDeviceGate {
    fun status(current: ScanStatus, state: HistoryDeviceState): ScanStatus {
        val thermalBlocked = state.thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE ||
            if (current == ScanStatus.WAITING_THERMAL) state.batteryTemperatureC > 38.0 else state.batteryTemperatureC >= 40.0
        if (thermalBlocked) return ScanStatus.WAITING_THERMAL
        if (!state.charging) {
            val threshold = if (current == ScanStatus.WAITING_BATTERY) 25 else 20
            if (state.batteryPercent < threshold) return ScanStatus.WAITING_BATTERY
        }
        return ScanStatus.RUNNING
    }
}

class AndroidHistoryDeviceStateSource(private val context: Context) : HistoryDeviceStateSource {
    override fun snapshot(): HistoryDeviceState {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val temperature = (battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        val thermal = context.getSystemService(PowerManager::class.java).currentThermalStatus
        return HistoryDeviceState(charging, if (level < 0) 100 else level * 100 / scale.coerceAtLeast(1), thermal, temperature)
    }
}
