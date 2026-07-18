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
import com.makia.hedgehogsms.classification.PlatformSlotFilter
import com.makia.hedgehogsms.ui.platform.LabelChoiceUi
import com.makia.hedgehogsms.scan.HistoryScanCoordinator
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
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
    val labelPlatforms: List<PlatformSummary> = emptyList(),
    val platformSlotFilter: PlatformSlotFilter = PlatformSlotFilter.ALL,
    val pendingLabelCount: Int = 0,
    val selectedSlotFilter: PlatformSlotFilter? = null,
    val slotDetailPlatforms: List<PlatformSummary> = emptyList(),
    val slotDetailLoading: Boolean = false,
    val slotDetailErrorText: String? = null,
    val slotDetailPermissionUnavailable: Boolean = false,
    val selectedPlatform: PlatformSummary? = null,
    val selectedPlatformSlotFilter: PlatformSlotFilter = PlatformSlotFilter.ALL,
    val platformEvidence: List<SmsRecord> = emptyList(),
    val platformEvidenceLoading: Boolean = false,
    val platformEvidenceErrorText: String? = null,
    val platformEvidencePermissionUnavailable: Boolean = false,
    val pendingMessage: SmsRecord? = null,
    val pendingPermissionUnavailable: Boolean = false,
)

class InboxViewModel(
    private val container: AppContainer,
    private val coordinator: HistoryScanCoordinator,
) : ViewModel() {
    private val pageState = MutableStateFlow(InboxUiState())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val platformSummaries = pageState
        .map { it.platformSlotFilter }
        .distinctUntilChanged()
        .flatMapLatest { filter -> container.database.classificationDao().observePlatformSummaries(filter.name) }
    private val platformSummaryPair = combine(
        platformSummaries,
        container.database.classificationDao().observePlatformSummaries(PlatformSlotFilter.ALL.name),
    ) { filtered, labels -> filtered to labels }

    val uiState: StateFlow<InboxUiState> = combine(
        container.database.scanRunDao().observe(),
        container.database.messageIndexDao().observeSummary(),
        platformSummaryPair,
        container.database.classificationDao().observePendingCount(),
        pageState,
    ) { scan, summary, platformPair, pending, page ->
        page.copy(
            scanRun = scan,
            summary = summary,
            platforms = platformPair.first,
            labelPlatforms = platformPair.second,
            pendingLabelCount = pending,
        )
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

    fun loadPlatformEvidence(
        platform: PlatformSummary,
        slotFilter: PlatformSlotFilter = pageState.value.platformSlotFilter,
    ) {
        viewModelScope.launch {
            pageState.value = pageState.value.copy(
                selectedPlatform = platform,
                selectedPlatformSlotFilter = slotFilter,
                platformEvidence = emptyList(),
                platformEvidenceLoading = true,
                platformEvidenceErrorText = null,
                platformEvidencePermissionUnavailable = false,
            )
            try {
                val ids = container.database.classificationDao()
                    .messageIdsForPlatform(platform.platformKey, slotFilter.name, PAGE_SIZE, 0)
                val records = coroutineScope { ids.map { async { container.smsSource.byId(it) } }.awaitAll().filterNotNull() }
                pageState.value = pageState.value.copy(
                    selectedPlatform = platform,
                    selectedPlatformSlotFilter = slotFilter,
                    platformEvidence = records,
                    platformEvidenceLoading = false,
                    platformEvidenceErrorText = null,
                    platformEvidencePermissionUnavailable = false,
                )
            } catch (_: SmsPermissionUnavailableException) {
                pageState.value = pageState.value.copy(
                    selectedPlatform = platform,
                    selectedPlatformSlotFilter = slotFilter,
                    platformEvidence = emptyList(),
                    platformEvidenceLoading = false,
                    platformEvidenceErrorText = null,
                    platformEvidencePermissionUnavailable = true,
                )
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                pageState.value = pageState.value.copy(
                    selectedPlatform = platform,
                    selectedPlatformSlotFilter = slotFilter,
                    platformEvidence = emptyList(),
                    platformEvidenceLoading = false,
                    platformEvidenceErrorText = "证据短信读取失败，请重试",
                    platformEvidencePermissionUnavailable = false,
                )
            }
        }
    }

    fun loadSlotDetail(slotFilter: PlatformSlotFilter) {
        viewModelScope.launch {
            pageState.value = pageState.value.copy(
                selectedSlotFilter = slotFilter,
                slotDetailPlatforms = emptyList(),
                slotDetailLoading = true,
                slotDetailErrorText = null,
                slotDetailPermissionUnavailable = false,
            )
            try {
                val platforms = container.database.classificationDao().platformSummaries(slotFilter.name)
                pageState.value = pageState.value.copy(
                    selectedSlotFilter = slotFilter,
                    slotDetailPlatforms = platforms,
                    slotDetailLoading = false,
                    slotDetailErrorText = null,
                    slotDetailPermissionUnavailable = false,
                )
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                pageState.value = pageState.value.copy(
                    selectedSlotFilter = slotFilter,
                    slotDetailPlatforms = emptyList(),
                    slotDetailLoading = false,
                    slotDetailErrorText = "卡槽平台读取失败，请重试",
                    slotDetailPermissionUnavailable = false,
                )
            }
        }
    }

    fun closeSlotDetail() {
        pageState.value = pageState.value.copy(
            selectedSlotFilter = null,
            slotDetailPlatforms = emptyList(),
            slotDetailLoading = false,
            slotDetailErrorText = null,
            slotDetailPermissionUnavailable = false,
        )
    }

    fun closePlatformEvidence() {
        pageState.value = pageState.value.copy(
            selectedPlatform = null,
            platformEvidence = emptyList(),
            platformEvidenceLoading = false,
            platformEvidenceErrorText = null,
            platformEvidencePermissionUnavailable = false,
        )
    }

    fun selectPlatformSlotFilter(filter: PlatformSlotFilter) {
        pageState.value = pageState.value.copy(
            platformSlotFilter = filter,
            selectedPlatform = null,
            platformEvidence = emptyList(),
            platformEvidenceLoading = false,
            platformEvidenceErrorText = null,
            platformEvidencePermissionUnavailable = false,
        )
    }

    fun loadPendingCandidate() {
        viewModelScope.launch {
            try {
                val id = container.database.classificationDao().nextPendingMessageId()
                pageState.value = pageState.value.copy(
                    pendingMessage = id?.let { container.smsSource.byId(it) },
                    pendingPermissionUnavailable = false,
                    historyPermissionUnavailable = false,
                )
            } catch (_: SmsPermissionUnavailableException) {
                pageState.value = pageState.value.copy(
                    pendingMessage = null,
                    pendingPermissionUnavailable = true,
                )
            }
        }
    }

    fun confirmPendingLabel(
        expectedMessageId: Long,
        label: LabelChoiceUi,
        onComplete: (Result<Unit>) -> Unit = {},
    ): Boolean {
        val message = pageState.value.pendingMessage ?: return false
        if (message.id != expectedMessageId) {
            onComplete(Result.failure(IllegalStateException("候选短信已变化，请刷新后重试")))
            return true
        }
        val clean = runCatching { com.makia.hedgehogsms.classification.PlatformLabelNormalizer.displayName(label.displayName) }
            .getOrNull() ?: return false
        viewModelScope.launch {
            val result = runCatching {
                container.pendingLabelTrainingQueueRepository.enqueue(
                    messageId = message.id,
                    labelId = label.labelId,
                    platformKey = label.platformKey,
                    displayName = clean,
                    now = System.currentTimeMillis(),
                )
                viewModelScope.launch { container.pendingLabelTrainingQueueRepository.drain() }
                pageState.value = pageState.value.copy(pendingMessage = null)
                loadPendingCandidate()
            }
            onComplete(result)
        }
        return true
    }

    fun drainPendingLabelTrainingQueue() = viewModelScope.launch {
        container.pendingLabelTrainingQueueRepository.drain()
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
