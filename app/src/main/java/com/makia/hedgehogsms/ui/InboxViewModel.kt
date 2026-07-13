package com.makia.hedgehogsms.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.makia.hedgehogsms.AppContainer
import com.makia.hedgehogsms.data.IndexSummary
import com.makia.hedgehogsms.data.ScanRun
import com.makia.hedgehogsms.data.SmsPermissionUnavailableException
import com.makia.hedgehogsms.data.SmsRecord
import com.makia.hedgehogsms.classification.PlatformSummary
import com.makia.hedgehogsms.classification.PlatformRuleClassifier
import com.makia.hedgehogsms.scan.HistoryScanCoordinator
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class InboxUiState(
    val scanRun: ScanRun? = null,
    val summary: IndexSummary = IndexSummary(0, 0, 0, 0),
    val historyPage: Int = 0,
    val historyMessages: List<SmsRecord> = emptyList(),
    val historyLoading: Boolean = false,
    val historyPermissionUnavailable: Boolean = false,
    val platforms: List<PlatformSummary> = emptyList(),
    val pendingLabelCount: Int = 0,
    val selectedPlatform: PlatformSummary? = null,
    val platformEvidence: List<SmsRecord> = emptyList(),
    val pendingMessage: SmsRecord? = null,
)

class InboxViewModel(
    private val container: AppContainer,
    private val coordinator: HistoryScanCoordinator,
) : ViewModel() {
    private val pageState = MutableStateFlow(InboxUiState())

    val uiState: StateFlow<InboxUiState> = combine(
        container.database.scanRunDao().observe(),
        container.database.messageIndexDao().observeSummary(),
        container.database.classificationDao().observePlatformSummaries(),
        container.database.classificationDao().observePendingCount(),
        pageState,
    ) { scan, summary, platforms, pending, page ->
        page.copy(scanRun = scan, summary = summary, platforms = platforms, pendingLabelCount = pending)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InboxUiState())

    fun startOrResume() = viewModelScope.launch { coordinator.startOrResume() }
    fun pause() = viewModelScope.launch { coordinator.pause() }
    fun restart() = viewModelScope.launch { coordinator.restart() }

    fun loadHistoryPage(page: Int) {
        if (page < 0) return
        viewModelScope.launch {
            pageState.value = pageState.value.copy(historyLoading = true, historyPermissionUnavailable = false)
            try {
                val indexes = container.database.messageIndexDao().page(PAGE_SIZE, page * PAGE_SIZE)
                val messages = coroutineScope {
                    indexes.map { index -> async { container.smsSource.byId(index.sourceMessageId) } }
                        .awaitAll().filterNotNull()
                }
                pageState.value = pageState.value.copy(
                    historyPage = page,
                    historyMessages = messages,
                    historyLoading = false,
                )
            } catch (_: SmsPermissionUnavailableException) {
                pageState.value = pageState.value.copy(
                    historyMessages = emptyList(),
                    historyLoading = false,
                    historyPermissionUnavailable = true,
                )
            }
        }
    }

    fun loadPlatformEvidence(platform: PlatformSummary) {
        viewModelScope.launch {
            val ids = container.database.classificationDao().messageIdsForPlatform(platform.platformKey, PAGE_SIZE, 0)
            val records = coroutineScope { ids.map { async { container.smsSource.byId(it) } }.awaitAll().filterNotNull() }
            pageState.value = pageState.value.copy(selectedPlatform = platform, platformEvidence = records)
        }
    }

    fun closePlatformEvidence() {
        pageState.value = pageState.value.copy(selectedPlatform = null, platformEvidence = emptyList())
    }

    fun loadPendingCandidate() {
        viewModelScope.launch {
            try {
                val id = container.database.classificationDao().nextPendingMessageId()
                pageState.value = pageState.value.copy(
                    pendingMessage = id?.let { container.smsSource.byId(it) },
                    historyPermissionUnavailable = false,
                )
            } catch (_: SmsPermissionUnavailableException) {
                pageState.value = pageState.value.copy(
                    pendingMessage = null,
                    historyPermissionUnavailable = true,
                )
            }
        }
    }

    fun confirmPendingLabel(displayName: String) {
        val message = pageState.value.pendingMessage ?: return
        val clean = displayName.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            val features = container.featureExtractor.extract(message.body, message.sender)
            container.trainingRepository.confirmHumanClassification(
                sampleId = message.id,
                sourceMessageId = message.id,
                labelId = PlatformRuleClassifier.stablePlatformLabelId(clean),
                platformKey = PlatformRuleClassifier.stablePlatformKey(clean),
                displayName = clean,
                features = features,
                now = System.currentTimeMillis(),
            )
            pageState.value = pageState.value.copy(pendingMessage = null)
            loadPendingCandidate()
        }
    }

    companion object {
        const val PAGE_SIZE = 25
        fun factory(container: AppContainer, coordinator: HistoryScanCoordinator) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                InboxViewModel(container, coordinator) as T
        }
    }
}
