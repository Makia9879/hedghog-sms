package com.makia.hedgehogsms.ui.security

import android.content.Context
import androidx.work.WorkManager
import com.makia.hedgehogsms.AppContainer
import com.makia.hedgehogsms.scan.HistoryScanCoordinator
import com.makia.hedgehogsms.sync.SmsSyncScheduler
import com.makia.hedgehogsms.llm.AndroidKeystoreApiKeyVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PrivacyUiState(
    val protectSensitiveScreens: Boolean = true,
    val screenshotRiskPending: Boolean = false,
    val clearConfirmationStep: Int = 0,
    val clearResult: ClearResult? = null,
)

enum class ClearResult { CLEARED, UNAVAILABLE, FAILED }

sealed interface PrivacyEvent {
    data object RequestDisableScreenshots : PrivacyEvent
    data object ConfirmDisableScreenshots : PrivacyEvent
    data object CancelDisableScreenshots : PrivacyEvent
    data object EnableScreenshotProtection : PrivacyEvent
    data object RequestClear : PrivacyEvent
    data object ConfirmClear : PrivacyEvent
    data object CancelClear : PrivacyEvent
    data class ClearFinished(val result: ClearResult) : PrivacyEvent
}

fun PrivacyUiState.reduce(event: PrivacyEvent): PrivacyUiState = when (event) {
    PrivacyEvent.RequestDisableScreenshots -> copy(screenshotRiskPending = true)
    PrivacyEvent.ConfirmDisableScreenshots -> copy(protectSensitiveScreens = false, screenshotRiskPending = false)
    PrivacyEvent.CancelDisableScreenshots -> copy(screenshotRiskPending = false)
    PrivacyEvent.EnableScreenshotProtection -> copy(protectSensitiveScreens = true, screenshotRiskPending = false)
    PrivacyEvent.RequestClear -> copy(clearConfirmationStep = 1, clearResult = null)
    PrivacyEvent.ConfirmClear -> when (clearConfirmationStep) {
        1 -> copy(clearConfirmationStep = 2)
        else -> this
    }
    PrivacyEvent.CancelClear -> copy(clearConfirmationStep = 0)
    is PrivacyEvent.ClearFinished -> copy(clearConfirmationStep = 0, clearResult = event.result)
}

fun interface LocalDataClearer {
    suspend fun clearAll(): ClearResult
}

object UnavailableLocalDataClearer : LocalDataClearer {
    override suspend fun clearAll(): ClearResult = ClearResult.UNAVAILABLE
}

class AndroidLocalDataClearer(
    context: Context,
    private val container: AppContainer,
) : LocalDataClearer {
    private val appContext = context.applicationContext

    override suspend fun clearAll(): ClearResult = withContext(Dispatchers.IO) {
        runCatching {
            val work = WorkManager.getInstance(appContext)
            listOf(
                HistoryScanCoordinator.WORK_NAME,
                SmsSyncScheduler.INCREMENTAL_WORK,
                SmsSyncScheduler.RECONCILE_WORK,
            ).forEach { work.cancelUniqueWork(it).result.get() }
            container.database.clearAllTables()
            AndroidKeystoreApiKeyVault(appContext).clearAll()
            container.featureHmacKey.clearAll()
            appContext.getSharedPreferences("privacy", Context.MODE_PRIVATE).edit().clear().commit()
            ClearResult.CLEARED
        }.getOrDefault(ClearResult.FAILED)
    }
}
