package com.makia.hedgehogsms.scan

import android.os.PowerManager
import com.makia.hedgehogsms.data.ScanStatus
import com.makia.hedgehogsms.data.ScanRun
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryDeviceGateTest {
    private fun state(charging: Boolean = false, battery: Int = 100, thermal: Int = 0, temp: Double = 25.0) =
        HistoryDeviceState(charging, battery, thermal, temp)

    @Test fun `running pauses below twenty but charging bypasses battery threshold`() {
        assertEquals(ScanStatus.WAITING_BATTERY, HistoryDeviceGate.status(ScanStatus.RUNNING, state(battery = 19)))
        assertEquals(ScanStatus.RUNNING, HistoryDeviceGate.status(ScanStatus.RUNNING, state(battery = 20)))
        assertEquals(ScanStatus.RUNNING, HistoryDeviceGate.status(ScanStatus.RUNNING, state(charging = true, battery = 1)))
    }

    @Test fun `battery wait resumes only at twenty five`() {
        assertEquals(ScanStatus.WAITING_BATTERY, HistoryDeviceGate.status(ScanStatus.WAITING_BATTERY, state(battery = 24)))
        assertEquals(ScanStatus.RUNNING, HistoryDeviceGate.status(ScanStatus.WAITING_BATTERY, state(battery = 25)))
    }

    @Test fun `moderate thermal status blocks regardless of charging`() {
        assertEquals(ScanStatus.WAITING_THERMAL, HistoryDeviceGate.status(
            ScanStatus.RUNNING, state(charging = true, thermal = PowerManager.THERMAL_STATUS_MODERATE),
        ))
    }

    @Test fun `temperature pauses at forty and resumes at thirty eight`() {
        assertEquals(ScanStatus.WAITING_THERMAL, HistoryDeviceGate.status(ScanStatus.RUNNING, state(temp = 40.0)))
        assertEquals(ScanStatus.WAITING_THERMAL, HistoryDeviceGate.status(ScanStatus.WAITING_THERMAL, state(temp = 38.1)))
        assertEquals(ScanStatus.RUNNING, HistoryDeviceGate.status(ScanStatus.WAITING_THERMAL, state(temp = 38.0)))
    }

    @Test fun `approximate percentage is bounded and absent without estimate`() {
        assertEquals(null, ScanRun(processed = 10).approximatePercent)
        assertEquals(100, ScanRun(status = ScanStatus.COMPLETED, estimated = 0).approximatePercent)
        assertEquals(24, ScanRun(processed = 24, estimated = 100).approximatePercent)
        assertEquals(100, ScanRun(processed = 101, estimated = 100).approximatePercent)
    }
}
