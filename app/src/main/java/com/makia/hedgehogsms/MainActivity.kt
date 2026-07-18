package com.makia.hedgehogsms

import android.Manifest
import android.app.Activity
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.makia.hedgehogsms.data.SlotResolver
import com.makia.hedgehogsms.data.SmsRecord
import com.makia.hedgehogsms.data.SmsPermissionUnavailableException
import com.makia.hedgehogsms.data.ScanStatus
import com.makia.hedgehogsms.scan.HistoryScanCoordinator
import com.makia.hedgehogsms.classification.PlatformRuleClassifier
import com.makia.hedgehogsms.permission.PermissionEvent
import com.makia.hedgehogsms.permission.PermissionSnapshot
import com.makia.hedgehogsms.permission.PermissionStep
import com.makia.hedgehogsms.permission.reduce
import com.makia.hedgehogsms.ui.InboxViewModel
import com.makia.hedgehogsms.ui.platform.EvidenceMessageUi
import com.makia.hedgehogsms.ui.platform.LabelChoiceUi
import com.makia.hedgehogsms.ui.platform.MESSAGE_DETAIL_PERMISSION_UNAVAILABLE
import com.makia.hedgehogsms.ui.platform.ManagedLabelUi
import com.makia.hedgehogsms.ui.platform.MessageDetailSource
import com.makia.hedgehogsms.ui.platform.PendingCandidateUi
import com.makia.hedgehogsms.ui.platform.PlatformFeatureScaffold
import com.makia.hedgehogsms.ui.platform.PlatformNavigationEvent
import com.makia.hedgehogsms.ui.platform.PlatformNavigationState
import com.makia.hedgehogsms.ui.platform.PlatformScreensCallbacks
import com.makia.hedgehogsms.ui.platform.PlatformScreensUiState
import com.makia.hedgehogsms.ui.platform.PlatformSummaryUi
import com.makia.hedgehogsms.ui.platform.PrimaryDestination
import com.makia.hedgehogsms.ui.platform.SlotCardUi
import com.makia.hedgehogsms.ui.platform.isSensitiveScreen
import com.makia.hedgehogsms.ui.platform.messageDetailUi
import com.makia.hedgehogsms.ui.platform.reduce as reducePlatformNavigation
import com.makia.hedgehogsms.ui.security.ClearResult
import com.makia.hedgehogsms.ui.security.AndroidLocalDataClearer
import com.makia.hedgehogsms.ui.security.LocalDataClearer
import com.makia.hedgehogsms.ui.security.PrivacyEvent
import com.makia.hedgehogsms.ui.security.PrivacyUiState
import com.makia.hedgehogsms.ui.security.reduce
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as HedgehogApplication).container
        setContent { MaterialTheme { HedgehogApp(container) } }
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (debuggable) {
            val action = intent.getStringExtra(DEBUG_SCAN_ACTION)
                ?: if (intent.getBooleanExtra(DEBUG_RESTART_HISTORY_SCAN, false)) "restart" else null
            lifecycleScope.launch {
                val coordinator = HistoryScanCoordinator(this@MainActivity)
                when (action) {
                    "restart" -> coordinator.restart()
                    "pause" -> coordinator.pause()
                    "resume" -> coordinator.startOrResume()
                }
            }
        }
    }

    private companion object {
        const val DEBUG_RESTART_HISTORY_SCAN = "debug_restart_history_scan"
        const val DEBUG_SCAN_ACTION = "debug_scan_action"
    }
}

@Composable
private fun HedgehogApp(container: AppContainer) {
    val context = LocalContext.current
    fun refreshedPermissions() = PermissionEvent.Refreshed(
        readSms = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED,
        readPhoneState = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED,
        receiveSms = ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED,
    )
    var permissions by remember {
        val readSms = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val readPhone = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val receiveSms = ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        mutableStateOf(
            if (readSms || readPhone || receiveSms) PermissionSnapshot(
                explained = true,
                readSms = readSms,
                readPhoneState = readPhone,
                receiveSms = receiveSms,
                requested = setOf(PermissionStep.READ_SMS, PermissionStep.READ_PHONE_STATE, PermissionStep.RECEIVE_SMS),
            ) else PermissionSnapshot(),
        )
    }
    var pendingStep by remember { mutableStateOf<PermissionStep?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pendingStep?.let { permissions = permissions.reduce(PermissionEvent.Result(it, granted)) }
        pendingStep = null
    }
    val permissionName = when (permissions.nextStep) {
        PermissionStep.READ_SMS -> Manifest.permission.READ_SMS
        PermissionStep.READ_PHONE_STATE -> Manifest.permission.READ_PHONE_STATE
        PermissionStep.RECEIVE_SMS -> Manifest.permission.RECEIVE_SMS
        else -> null
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissions = permissions.reduce(refreshedPermissions())
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold { padding ->
        if (!permissions.canReadInbox || permissions.nextStep != PermissionStep.COMPLETE) {
            Onboarding(permissions, Modifier.padding(padding)) {
                if (permissions.nextStep == PermissionStep.EXPLANATION) {
                    permissions = permissions.reduce(PermissionEvent.ExplanationAccepted)
                } else {
                    val step = if (permissions.nextStep == PermissionStep.COMPLETE) PermissionStep.READ_SMS else permissions.nextStep
                    val name = if (step == PermissionStep.READ_SMS) Manifest.permission.READ_SMS else permissionName
                    pendingStep = step
                    name?.let(permissionLauncher::launch)
                }
            }
        } else Inbox(container, permissions, Modifier.padding(padding), remember(container) { AndroidLocalDataClearer(context, container) }) {
            permissions = permissions.reduce(PermissionEvent.Refreshed(false, permissions.readPhoneState, permissions.receiveSms))
        }
    }
}

@Composable
private fun Onboarding(state: PermissionSnapshot, modifier: Modifier, onContinue: () -> Unit) {
    Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("刺猬选短信", style = MaterialTheme.typography.headlineMedium)
        Text("短信不搬家，只帮你归类")
        Text("正文始终留在系统信箱。应用只读短信，并且默认不联网。")
        Text(when (state.nextStep) {
            PermissionStep.EXPLANATION -> "先了解权限用途，再由你逐项授权。"
            PermissionStep.READ_SMS -> "读取短信：用于显示真实短信。拒绝后不会读取或扫描。"
            PermissionStep.READ_PHONE_STATE -> "电话状态：仅用于把订阅映射到卡槽。拒绝后显示未知卡槽。"
            PermissionStep.RECEIVE_SMS -> "接收通知：用于及时发现新短信。拒绝后下次打开时补齐。"
            PermissionStep.COMPLETE -> if (!state.readSms) "短信读取权限不可用，请在系统设置中重新授权。" else "引导完成"
        })
        if (state.nextStep != PermissionStep.COMPLETE || !state.readSms) {
            Button(onClick = onContinue) { Text(if (state.nextStep == PermissionStep.COMPLETE) "重新授权" else "继续") }
        }
    }
}

@Composable
private fun Inbox(
    container: AppContainer,
    state: PermissionSnapshot,
    modifier: Modifier,
    dataClearer: LocalDataClearer,
    onPermissionUnavailable: () -> Unit,
) {
    var messages by remember { mutableStateOf<List<SmsRecord>>(emptyList()) }
    var slots by remember { mutableStateOf<Map<Long, Int>?>(null) }
    var slotCount by remember { mutableStateOf(2) }
    var selected by remember { mutableStateOf<SmsRecord?>(null) }
    var detailStatusText by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf(InboxFilter.ALL) }
    var newPlatformName by remember { mutableStateOf("") }
    var selectedPendingLabel by remember { mutableStateOf<LabelChoiceUi?>(null) }
    var createLabelDialogOpen by remember { mutableStateOf(false) }
    var createLabelError by remember { mutableStateOf<String?>(null) }
    var pendingSubmitInProgress by remember { mutableStateOf(false) }
    var pendingSubmitError by remember { mutableStateOf<String?>(null) }
    var platformNavigation by remember { mutableStateOf(PlatformNavigationState()) }
    var labelDrafts by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var governanceNotice by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val activity = context as? Activity
    val preferences = remember { context.getSharedPreferences("privacy", android.content.Context.MODE_PRIVATE) }
    var privacy by remember {
        mutableStateOf(PrivacyUiState(protectSensitiveScreens = preferences.getBoolean("protect_sensitive_screens", true)))
    }
    val scope = rememberCoroutineScope()
    val scanCoordinator = remember { HistoryScanCoordinator(context) }
    val inboxViewModel: InboxViewModel = viewModel(factory = InboxViewModel.factory(container, scanCoordinator))
    val uiState by inboxViewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(inboxViewModel) {
        inboxViewModel.drainPendingLabelTrainingQueue()
    }
    val scanRun = uiState.scanRun
    val indexedTotal = uiState.summary.total
    val sensitiveContentVisible = platformNavigation.isSensitiveScreen()
    DisposableEffect(sensitiveContentVisible, privacy.protectSensitiveScreens, activity) {
        if (sensitiveContentVisible && privacy.protectSensitiveScreens) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    LaunchedEffect(Unit) {
        try {
            messages = container.smsSource.page(null, 25, null)
            val snapshot = container.subscriptionSource.snapshot()
            slots = snapshot.activeSlots
            slotCount = snapshot.logicalSlotCount
        } catch (_: SmsPermissionUnavailableException) {
            messages = emptyList()
            onPermissionUnavailable()
        }
    }
    LaunchedEffect(platformNavigation.detail?.messageId) {
        val detail = platformNavigation.detail
        if (detail == null) {
            selected = null
            detailStatusText = null
            return@LaunchedEffect
        }
        selected = null
        detailStatusText = null
        try {
            val sms = container.smsSource.byId(detail.messageId)
            if (sms == null) {
                detailStatusText = "系统短信已删除"
            } else {
                selected = sms
            }
        } catch (_: SmsPermissionUnavailableException) {
            detailStatusText = MESSAGE_DETAIL_PERMISSION_UNAVAILABLE
        }
    }
    val resolver = remember { SlotResolver() }
    val dateTimeFormat = remember { DateFormat.getDateTimeInstance() }
    val resolved = messages.associateWith { resolver.resolve(it.subscriptionId, slots, slotCount) }
    val filtered = messages.filter { sms ->
        val slot = resolved.getValue(sms).slotIndex
        when (filter) {
            InboxFilter.ALL -> true
            InboxFilter.SLOT_1 -> slot == 0
            InboxFilter.SLOT_2 -> slot == 1
            InboxFilter.UNKNOWN -> slot == null
        }
    }
    val messageDetailReceivedAtText = remember(selected?.dateMillis) {
        selected?.let { dateTimeFormat.format(Date(it.dateMillis)) }
    }
    val showingPlatformList = platformNavigation.destination == PrimaryDestination.PLATFORMS &&
        platformNavigation.selectedPlatformId == null &&
        !platformNavigation.pendingOpen &&
        platformNavigation.detail == null
    val showingPendingLabels = platformNavigation.pendingOpen
    val showingSlotDetail = platformNavigation.selectedSlot != null && platformNavigation.selectedPlatformId == null
    val showingLabelScreens = platformNavigation.destination == PrimaryDestination.LABELS ||
        platformNavigation.selectedLabelId != null ||
        platformNavigation.labelCreateOpen
    val showingPlatformEvidence = platformNavigation.selectedPlatformId != null
    val platformSummaries = remember(uiState.platforms, showingPlatformList) {
        if (!showingPlatformList) {
            emptyList()
        } else {
            uiState.platforms.map { platform ->
                PlatformSummaryUi(
                    id = platform.platformKey,
                    name = platform.displayName,
                    verificationCodeCount = platform.otpCount,
                    latestAtText = dateTimeFormat.format(Date(platform.latestMessageDate)),
                    slot1Count = platform.slot1Count,
                    slot2Count = platform.slot2Count,
                    unknownCount = platform.unknownCount,
                )
            }
        }
    }
    val selectedPlatformEvidence = remember(uiState.platformEvidence, showingPlatformEvidence) {
        if (!showingPlatformEvidence) {
            emptyList()
        } else {
            uiState.platformEvidence.map { sms ->
                EvidenceMessageUi(
                    messageId = sms.id,
                    senderText = sms.sender.orEmpty(),
                    receivedAtText = dateTimeFormat.format(Date(sms.dateMillis)),
                    simAndSlotText = "卡槽信息以扫描索引为准",
                )
            }
        }
    }
    val labelChoices = remember(uiState.labelPlatforms, showingPendingLabels) {
        if (!showingPendingLabels) {
            emptyList()
        } else {
            uiState.labelPlatforms.map {
                LabelChoiceUi(
                    labelId = PlatformRuleClassifier.stablePlatformLabelId(it.displayName),
                    platformKey = it.platformKey,
                    displayName = it.displayName,
                )
            }
        }
    }
    val normalizedLabelSearch = remember(newPlatformName) {
        runCatching {
            com.makia.hedgehogsms.classification.PlatformLabelNormalizer.comparisonKey(newPlatformName)
        }.getOrNull()
    }
    val filteredLabelChoices = remember(labelChoices, normalizedLabelSearch) {
        labelChoices.filter { label ->
            normalizedLabelSearch == null ||
                com.makia.hedgehogsms.classification.PlatformLabelNormalizer.comparisonKey(label.displayName)
                    .contains(normalizedLabelSearch)
        }
    }
    val selectedSlotPlatformSummaries = remember(uiState.slotDetailPlatforms, showingSlotDetail) {
        if (!showingSlotDetail) {
            emptyList()
        } else {
            uiState.slotDetailPlatforms.map { platform ->
                PlatformSummaryUi(
                    id = platform.platformKey,
                    name = platform.displayName,
                    verificationCodeCount = platform.otpCount,
                    latestAtText = dateTimeFormat.format(Date(platform.latestMessageDate)),
                    slot1Count = platform.slot1Count,
                    slot2Count = platform.slot2Count,
                    unknownCount = platform.unknownCount,
                )
            }
        }
    }
    val managedLabels = remember(uiState.labelPlatforms, labelDrafts, showingLabelScreens) {
        if (!showingLabelScreens) {
            emptyList()
        } else {
            uiState.labelPlatforms.map { platform ->
                ManagedLabelUi(
                    id = platform.platformKey,
                    displayName = platform.displayName,
                    renameDraft = labelDrafts[platform.platformKey] ?: platform.displayName,
                    aliasesText = "别名治理需要先选择目标并确认",
                )
            }
        }
    }
    val platformScreenState = PlatformScreensUiState(
        navigation = platformNavigation,
        platformSlotFilter = uiState.platformSlotFilter,
        messageDetail = messageDetailUi(
            navigation = platformNavigation.detail,
            senderText = selected?.sender,
            body = selected?.body,
            receivedAtText = messageDetailReceivedAtText,
            statusText = detailStatusText,
        ),
        platforms = platformSummaries,
        selectedPlatformName = uiState.selectedPlatform?.displayName.orEmpty(),
        selectedPlatformEvidence = selectedPlatformEvidence,
        platformEvidenceLoading = uiState.platformEvidenceLoading,
        platformEvidenceErrorText = uiState.platformEvidenceErrorText,
        platformEvidencePermissionUnavailable = uiState.platformEvidencePermissionUnavailable,
        pendingCandidate = uiState.pendingMessage?.let { sms ->
            PendingCandidateUi(
                messageId = sms.id,
                suggestedPlatform = null,
                explanation = sms.body,
                existingLabels = filteredLabelChoices,
                labelSearchText = newPlatformName,
                selectedLabel = selectedPendingLabel,
                createDialogOpen = createLabelDialogOpen,
                createError = createLabelError,
                submitInProgress = pendingSubmitInProgress,
                submitError = pendingSubmitError,
            )
        },
        pendingPermissionUnavailable = uiState.pendingPermissionUnavailable,
        pendingLabelCount = uiState.pendingLabelCount,
        slots = listOf(
            SlotCardUi(com.makia.hedgehogsms.classification.PlatformSlotFilter.SLOT_1, "卡槽 1", uiState.summary.slot1),
            SlotCardUi(com.makia.hedgehogsms.classification.PlatformSlotFilter.SLOT_2, "卡槽 2", uiState.summary.slot2),
            SlotCardUi(com.makia.hedgehogsms.classification.PlatformSlotFilter.UNKNOWN, "未知卡槽", uiState.summary.unknown),
        ),
        selectedSlotPlatforms = selectedSlotPlatformSummaries,
        slotDetailLoading = uiState.slotDetailLoading,
        slotDetailErrorText = uiState.slotDetailErrorText,
        slotDetailPermissionUnavailable = uiState.slotDetailPermissionUnavailable,
        labels = managedLabels,
        actionNotice = governanceNotice,
    )
    val platformCallbacks = PlatformScreensCallbacks(
        onNavigation = { event ->
            when (event) {
                is PlatformNavigationEvent.OpenPlatform -> {
                    val slotFilter = platformNavigation.selectedSlot ?: uiState.platformSlotFilter
                    val platform = if (platformNavigation.selectedSlot == null) {
                        uiState.platforms.firstOrNull { it.platformKey == event.platformId }
                    } else {
                        uiState.slotDetailPlatforms.firstOrNull { it.platformKey == event.platformId }
                    }
                    platform?.let { inboxViewModel.loadPlatformEvidence(it, slotFilter) }
                }
                PlatformNavigationEvent.ClosePlatform -> inboxViewModel.closePlatformEvidence()
                PlatformNavigationEvent.CloseMessageDetail -> selected = null
                is PlatformNavigationEvent.OpenMessageDetail -> Unit
                is PlatformNavigationEvent.SelectDestination -> {
                    inboxViewModel.closePlatformEvidence()
                    inboxViewModel.closeSlotDetail()
                }
                PlatformNavigationEvent.OpenPendingLabels -> if (uiState.pendingMessage == null) inboxViewModel.loadPendingCandidate()
                is PlatformNavigationEvent.OpenSlot -> inboxViewModel.loadSlotDetail(event.slot)
                PlatformNavigationEvent.CloseSlot -> inboxViewModel.closeSlotDetail()
                PlatformNavigationEvent.ClosePendingLabels,
                PlatformNavigationEvent.CloseLabelDetail,
                PlatformNavigationEvent.CloseLabelCreate -> Unit
                is PlatformNavigationEvent.OpenLabelDetail,
                PlatformNavigationEvent.OpenLabelCreate -> Unit
            }
            platformNavigation = platformNavigation.reducePlatformNavigation(event)
        },
        onOpenEvidence = { messageId ->
            platformNavigation = platformNavigation.reducePlatformNavigation(
                PlatformNavigationEvent.OpenMessageDetail(
                    messageId,
                    MessageDetailSource.PlatformEvidence(uiState.selectedPlatform?.platformKey.orEmpty()),
                ),
            )
        },
        onOpenSlotPlatform = { platform, slot ->
            uiState.slotDetailPlatforms.firstOrNull { it.platformKey == platform.id }
                ?.let { inboxViewModel.loadPlatformEvidence(it, slot) }
            platformNavigation = platformNavigation.reducePlatformNavigation(PlatformNavigationEvent.OpenPlatform(platform.id))
        },
        onAcceptSuggestedLabel = { messageId ->
            selectedPendingLabel?.let { label ->
                pendingSubmitInProgress = true
                pendingSubmitError = null
                val started = inboxViewModel.confirmPendingLabel(messageId, label) { result ->
                    pendingSubmitInProgress = false
                    result
                        .onSuccess {
                            selectedPendingLabel = null
                            newPlatformName = ""
                            pendingSubmitError = null
                        }
                        .onFailure { error ->
                            pendingSubmitError = error.message ?: "绑定训练失败，请重试"
                        }
                }
                if (!started) {
                    pendingSubmitInProgress = false
                    pendingSubmitError = "候选短信已变化，请刷新后重试"
                }
            } ?: run { governanceNotice = "请先选择或快速创建一个标签。" }
        },
        onChooseExistingLabel = { _, label ->
            selectedPendingLabel = label
            pendingSubmitError = null
        },
        onConfirmPendingSelection = { label ->
            selectedPendingLabel = label
            newPlatformName = label.displayName
            pendingSubmitError = null
        },
        onCreateLabel = {
            val clean = runCatching {
                com.makia.hedgehogsms.classification.PlatformLabelNormalizer.displayName(newPlatformName)
            }.getOrNull()
            if (clean == null) {
                createLabelError = "标签名称不能为空"
            } else {
                val key = com.makia.hedgehogsms.classification.PlatformLabelNormalizer.comparisonKey(clean)
                val duplicate = uiState.labelPlatforms.any {
                    com.makia.hedgehogsms.classification.PlatformLabelNormalizer.comparisonKey(it.displayName) == key
                }
                if (duplicate) {
                    createLabelError = "已存在同名标签，请选择已有标签"
                } else {
                    selectedPendingLabel = LabelChoiceUi(
                        labelId = PlatformRuleClassifier.stablePlatformLabelId(clean),
                        platformKey = PlatformRuleClassifier.stablePlatformKey(clean),
                        displayName = clean,
                    )
                    newPlatformName = clean
                    createLabelDialogOpen = false
                    createLabelError = null
                    pendingSubmitError = null
                }
            }
        },
        onOpenCreateLabel = {
            createLabelDialogOpen = true
            createLabelError = null
        },
        onDismissCreateLabel = {
            createLabelDialogOpen = false
            createLabelError = null
        },
        onNewLabelDraftChange = {
            newPlatformName = it
            createLabelError = null
            pendingSubmitError = null
        },
        onPlatformSlotFilterChange = { filter ->
            inboxViewModel.selectPlatformSlotFilter(filter)
            platformNavigation = platformNavigation.reducePlatformNavigation(PlatformNavigationEvent.ClosePlatform)
        },
        onCreateStandaloneLabel = { name, mergedLabels ->
            val clean = runCatching {
                com.makia.hedgehogsms.classification.PlatformLabelNormalizer.displayName(name)
            }.getOrNull()
            if (clean == null) {
                governanceNotice = "标签名称不能为空"
            } else {
                val key = com.makia.hedgehogsms.classification.PlatformLabelNormalizer.comparisonKey(clean)
                val duplicate = uiState.labelPlatforms.any {
                    com.makia.hedgehogsms.classification.PlatformLabelNormalizer.comparisonKey(it.displayName) == key
                }
                governanceNotice = if (duplicate) {
                    "已存在同名标签，请选择已有标签。"
                } else if (mergedLabels.isEmpty()) {
                    "已准备创建标签：$clean。完整标签实体持久化待接入。"
                } else {
                    "已准备创建标签：$clean，并合并 ${mergedLabels.joinToString(",") { it.displayName }} 的短信集合。"
                }
            }
        },
        onRenameDraftChange = { id, value -> labelDrafts = labelDrafts + (id to value) },
        onRenameLabel = { governanceNotice = "重命名前需要接通标签治理事务；当前未做更改。" },
        onMergeLabel = { governanceNotice = "请先选择要合并到的主标签；当前未做更改。" },
        onSplitLabel = { governanceNotice = "拆分需要逐条选择短信并重新标注；当前未做更改。" },
        onRequestSmsPermission = onPermissionUnavailable,
    )
    PlatformFeatureScaffold(platformScreenState, platformCallbacks, modifier) { messagesModifier ->
      LazyColumn(messagesModifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("刺猬选短信", style = MaterialTheme.typography.headlineMedium)
                Text("找出每个 SIM 集合曾接收验证码的平台，供你自行处理账号")
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("本地只读", style = MaterialTheme.typography.titleMedium)
                    Text("正文位于系统信箱；当前页面实时读取，不写入本地索引，也不会上传。")
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(when (scanRun?.status ?: ScanStatus.IDLE) {
                        ScanStatus.IDLE -> "历史扫描尚未开始"
                        ScanStatus.RUNNING -> "正在温和扫描"
                        ScanStatus.PAUSED -> "历史扫描已暂停"
                        ScanStatus.WAITING_BATTERY -> "等待电量恢复（充电或电量达到安全门槛后自动继续）"
                        ScanStatus.WAITING_THERMAL -> "等待设备降温（恢复安全温度后自动继续）"
                        ScanStatus.WAITING_PERMISSION -> "等待短信权限"
                        ScanStatus.FAILED -> "扫描遇到错误"
                        ScanStatus.COMPLETED -> "历史扫描已完成"
                    }, style = MaterialTheme.typography.titleMedium)
                    Text("已建立 $indexedTotal 条无正文索引；最近列表仍实时读取系统信箱。")
                    when (scanRun?.status ?: ScanStatus.IDLE) {
                        ScanStatus.RUNNING -> Button(onClick = inboxViewModel::pause) { Text("暂停") }
                        ScanStatus.COMPLETED -> Button(onClick = inboxViewModel::restart) { Text("重新完整扫描") }
                        ScanStatus.WAITING_BATTERY, ScanStatus.WAITING_THERMAL ->
                            Button(onClick = inboxViewModel::startOrResume) { Text("重新检查条件") }
                        ScanStatus.WAITING_PERMISSION -> Button(onClick = onPermissionUnavailable) { Text("重新授权") }
                        ScanStatus.FAILED -> Button(onClick = inboxViewModel::startOrResume) { Text("从断点重试") }
                        ScanStatus.PAUSED -> Button(onClick = inboxViewModel::startOrResume) { Text("继续") }
                        ScanStatus.IDLE -> Button(onClick = inboxViewModel::startOrResume) { Text("开始扫描") }
                    }
                }
            }
        }
        item { Text("历史索引的卡槽分布", style = MaterialTheme.typography.titleLarge) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Card(Modifier.weight(1f)) { Column(Modifier.padding(12.dp)) { Text("卡槽 1"); Text("${uiState.summary.slot1} 条") } }
                Card(Modifier.weight(1f)) { Column(Modifier.padding(12.dp)) { Text("卡槽 2"); Text("${uiState.summary.slot2} 条") } }
                Card(Modifier.weight(1f)) { Column(Modifier.padding(12.dp)) { Text("未知"); Text("${uiState.summary.unknown} 条") } }
            }
        }
        if (state.phoneStateDegraded) item { Text("未授权电话状态，所有卡槽信息将显示为未知卡槽。") }
        if (state.receiveSmsDegraded) item { Text("新短信将在下次打开时补齐。") }
        return@LazyColumn
        item { Text("最近短信", style = MaterialTheme.typography.headlineSmall) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                InboxFilter.entries.forEach { option ->
                    Button(onClick = { filter = option }, enabled = filter != option) { Text(option.label) }
                }
            }
        }
        if (filtered.isEmpty()) item { Text("这个筛选下没有短信。") }
        items(filtered, key = { it.id }) { sms ->
            val slot = resolved.getValue(sms)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(sms.sender.orEmpty(), style = MaterialTheme.typography.titleMedium)
                    Text(sms.body)
                    Text(slot.slotIndex?.let { "卡槽 ${it + 1}" } ?: "未知卡槽")
                    Button(onClick = {
                        platformNavigation = platformNavigation.reducePlatformNavigation(
                            PlatformNavigationEvent.OpenMessageDetail(sms.id, MessageDetailSource.Scan),
                        )
                    }) { Text("查看详情") }
                }
            }
        }
        item { Text("历史短信", style = MaterialTheme.typography.headlineSmall) }
        item {
            Text("从无正文索引分页定位；当前页正文每次从系统信箱实时读取。")
            Button(
                onClick = { inboxViewModel.loadHistoryPage(0) },
                enabled = indexedTotal > 0 && !uiState.historyLoading,
            ) { Text(if (uiState.historyMessages.isEmpty()) "浏览全部历史" else "回到第一页") }
        }
        if (uiState.historyPermissionUnavailable) item {
            Text("短信读取权限不可用，历史正文已隐藏。")
            Button(onClick = onPermissionUnavailable) { Text("重新授权") }
        }
        if (uiState.historyLoading) item { Text("正在读取当前页…") }
        items(uiState.historyMessages, key = { "history-${it.id}" }) { sms ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(sms.sender.orEmpty(), style = MaterialTheme.typography.titleMedium)
                    Text(sms.body)
                    Text("正文从系统信箱实时读取")
                    Button(onClick = {
                        platformNavigation = platformNavigation.reducePlatformNavigation(
                            PlatformNavigationEvent.OpenMessageDetail(sms.id, MessageDetailSource.Scan),
                        )
                    }) { Text("查看详情") }
                }
            }
        }
        if (uiState.historyMessages.isNotEmpty()) item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { inboxViewModel.loadHistoryPage(uiState.historyPage - 1) },
                    enabled = uiState.historyPage > 0 && !uiState.historyLoading,
                ) { Text("上一页") }
                Text("第 ${uiState.historyPage + 1} 页", Modifier.padding(12.dp))
                Button(
                    onClick = { inboxViewModel.loadHistoryPage(uiState.historyPage + 1) },
                    enabled = (uiState.historyPage + 1) * InboxViewModel.PAGE_SIZE < indexedTotal && !uiState.historyLoading,
                ) { Text("下一页") }
            }
        }
        item { Text("设置与隐私", style = MaterialTheme.typography.headlineSmall) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("敏感页防截屏", style = MaterialTheme.typography.titleMedium)
                    Text(if (privacy.protectSensitiveScreens) "已开启：短信详情默认禁止截屏和最近任务预览。" else "已关闭：系统或其他应用可能捕获短信详情。")
                    if (privacy.protectSensitiveScreens) {
                        Button(onClick = { privacy = privacy.reduce(PrivacyEvent.RequestDisableScreenshots) }) { Text("关闭防截屏") }
                    } else {
                        Button(onClick = {
                            privacy = privacy.reduce(PrivacyEvent.EnableScreenshotProtection)
                            preferences.edit().putBoolean("protect_sensitive_screens", true).apply()
                        }) { Text("开启防截屏") }
                    }
                    if (privacy.screenshotRiskPending) {
                        Text("关闭后，短信发送者和正文可能出现在截图、录屏或最近任务预览中。")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                privacy = privacy.reduce(PrivacyEvent.ConfirmDisableScreenshots)
                                preferences.edit().putBoolean("protect_sensitive_screens", false).apply()
                            }) { Text("理解风险并关闭") }
                            Button(onClick = { privacy = privacy.reduce(PrivacyEvent.CancelDisableScreenshots) }) { Text("取消") }
                        }
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("清除全部本地数据", style = MaterialTheme.typography.titleMedium)
                    Text("不会删除系统短信；完整清除能力接通前不会执行部分清除。")
                    when (privacy.clearConfirmationStep) {
                        0 -> Button(onClick = { privacy = privacy.reduce(PrivacyEvent.RequestClear) }) { Text("清除全部本地数据") }
                        1 -> {
                            Text("第一次确认：索引、扫描状态、模型和本地设置将不可恢复。")
                            Button(onClick = { privacy = privacy.reduce(PrivacyEvent.ConfirmClear) }) { Text("继续确认") }
                            Button(onClick = { privacy = privacy.reduce(PrivacyEvent.CancelClear) }) { Text("取消") }
                        }
                        2 -> {
                            Text("第二次确认：系统短信不会被删除。确定调用完整清除接口？")
                            Button(onClick = {
                                scope.launch {
                                    val result = runCatching { dataClearer.clearAll() }.getOrDefault(ClearResult.FAILED)
                                    privacy = privacy.reduce(PrivacyEvent.ClearFinished(result))
                                }
                            }) { Text("确认清除") }
                            Button(onClick = { privacy = privacy.reduce(PrivacyEvent.CancelClear) }) { Text("取消") }
                        }
                    }
                    when (privacy.clearResult) {
                        ClearResult.CLEARED -> Text("本地数据已清除。")
                        ClearResult.UNAVAILABLE -> Text("完整清除接口尚未接通，未删除任何数据。")
                        ClearResult.FAILED -> Text("清除失败，未确认完成。")
                        null -> Unit
                    }
                }
            }
        }
      }
    }
}

private enum class InboxFilter(val label: String) {
    ALL("全部"), SLOT_1("卡槽 1"), SLOT_2("卡槽 2"), UNKNOWN("未知"),
}
