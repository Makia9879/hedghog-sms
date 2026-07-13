package com.makia.hedgehogsms.data

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidSmsSourceDeviceTest {
    @Test fun firstPageContainsTwentyFiveRowsWhenDeviceInboxHasEnoughRows() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assumeTrue(context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED)

        // Deliberately assert only the count: never print or retain message
        // contents, senders, ids, or dates from the real device provider.
        val page = AndroidSmsSource(context.contentResolver).page(null, 25, null)
        assertEquals(25, page.size)
    }
}
