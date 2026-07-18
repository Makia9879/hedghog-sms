package com.makia.hedgehogsms.ui.platform

import android.content.Context
import android.os.Build
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.WindowInsets as AndroidWindowInsets
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import com.makia.hedgehogsms.classification.PlatformSlotFilter

enum class PrimaryDestination(val label: String) {
    SCAN("扫描"),
    PLATFORMS("查看平台列表"),
    SLOTS("查看卡槽"),
    LABELS("查看标签"),
}

data class PlatformNavigationState(
    val destination: PrimaryDestination = PrimaryDestination.SCAN,
    val selectedPlatformId: String? = null,
    val selectedSlot: PlatformSlotFilter? = null,
    val pendingOpen: Boolean = false,
    val selectedLabelId: String? = null,
    val labelCreateOpen: Boolean = false,
    val detail: MessageDetailNavigation? = null,
) {
    val hidesBottomBar: Boolean
        get() = selectedPlatformId != null ||
            selectedSlot != null ||
            pendingOpen ||
            selectedLabelId != null ||
            labelCreateOpen ||
            detail != null
}

fun PlatformNavigationState.isSensitiveScreen(): Boolean =
    detail != null || selectedPlatformId != null || pendingOpen

fun PlatformNavigationState.systemBackEvent(): PlatformNavigationEvent? = when {
    detail != null -> PlatformNavigationEvent.CloseMessageDetail
    selectedPlatformId != null -> PlatformNavigationEvent.ClosePlatform
    selectedSlot != null -> PlatformNavigationEvent.CloseSlot
    pendingOpen -> PlatformNavigationEvent.ClosePendingLabels
    selectedLabelId != null -> PlatformNavigationEvent.CloseLabelDetail
    labelCreateOpen -> PlatformNavigationEvent.CloseLabelCreate
    else -> null
}

sealed interface MessageDetailSource {
    data object Scan : MessageDetailSource
    data class PlatformEvidence(val platformId: String) : MessageDetailSource
    data class SlotDetail(val slot: PlatformSlotFilter) : MessageDetailSource
}

data class MessageDetailNavigation(
    val messageId: Long,
    val source: MessageDetailSource,
)

sealed interface PlatformNavigationEvent {
    data class SelectDestination(val destination: PrimaryDestination) : PlatformNavigationEvent
    data class OpenPlatform(val platformId: String) : PlatformNavigationEvent
    data object ClosePlatform : PlatformNavigationEvent
    data class OpenSlot(val slot: PlatformSlotFilter) : PlatformNavigationEvent
    data object CloseSlot : PlatformNavigationEvent
    data object OpenPendingLabels : PlatformNavigationEvent
    data object ClosePendingLabels : PlatformNavigationEvent
    data class OpenLabelDetail(val labelId: String) : PlatformNavigationEvent
    data object CloseLabelDetail : PlatformNavigationEvent
    data object OpenLabelCreate : PlatformNavigationEvent
    data object CloseLabelCreate : PlatformNavigationEvent
    data class OpenMessageDetail(val messageId: Long, val source: MessageDetailSource) : PlatformNavigationEvent
    data object CloseMessageDetail : PlatformNavigationEvent
}

fun PlatformNavigationState.reduce(event: PlatformNavigationEvent): PlatformNavigationState = when (event) {
    is PlatformNavigationEvent.SelectDestination -> PlatformNavigationState(destination = event.destination)
    is PlatformNavigationEvent.OpenPlatform -> copy(
        destination = PrimaryDestination.PLATFORMS,
        selectedPlatformId = event.platformId,
        detail = null,
        pendingOpen = false,
        selectedLabelId = null,
        labelCreateOpen = false,
    )
    PlatformNavigationEvent.ClosePlatform -> copy(selectedPlatformId = null, detail = null)
    is PlatformNavigationEvent.OpenSlot -> copy(
        destination = PrimaryDestination.SLOTS,
        selectedSlot = event.slot,
        selectedPlatformId = null,
        detail = null,
    )
    PlatformNavigationEvent.CloseSlot -> copy(selectedSlot = null, selectedPlatformId = null, detail = null)
    PlatformNavigationEvent.OpenPendingLabels -> copy(
        destination = PrimaryDestination.PLATFORMS,
        pendingOpen = true,
        selectedPlatformId = null,
        selectedSlot = null,
        detail = null,
    )
    PlatformNavigationEvent.ClosePendingLabels -> copy(pendingOpen = false)
    is PlatformNavigationEvent.OpenLabelDetail -> copy(
        destination = PrimaryDestination.LABELS,
        selectedLabelId = event.labelId,
        labelCreateOpen = false,
    )
    PlatformNavigationEvent.CloseLabelDetail -> copy(selectedLabelId = null)
    PlatformNavigationEvent.OpenLabelCreate -> copy(
        destination = PrimaryDestination.LABELS,
        labelCreateOpen = true,
        selectedLabelId = null,
    )
    PlatformNavigationEvent.CloseLabelCreate -> copy(labelCreateOpen = false)
    is PlatformNavigationEvent.OpenMessageDetail -> when (val source = event.source) {
        MessageDetailSource.Scan -> copy(
            destination = PrimaryDestination.SCAN,
            detail = MessageDetailNavigation(event.messageId, source),
        )
        is MessageDetailSource.PlatformEvidence -> copy(
            destination = PrimaryDestination.PLATFORMS,
            selectedPlatformId = source.platformId,
            detail = MessageDetailNavigation(event.messageId, source),
        )
        is MessageDetailSource.SlotDetail -> copy(
            destination = PrimaryDestination.SLOTS,
            selectedSlot = source.slot,
            detail = MessageDetailNavigation(event.messageId, source),
        )
    }
    PlatformNavigationEvent.CloseMessageDetail -> when (val source = detail?.source) {
        null, MessageDetailSource.Scan -> copy(destination = PrimaryDestination.SCAN, detail = null)
        is MessageDetailSource.PlatformEvidence -> copy(
            destination = PrimaryDestination.PLATFORMS,
            selectedPlatformId = source.platformId,
            detail = null,
        )
        is MessageDetailSource.SlotDetail -> copy(
            destination = PrimaryDestination.SLOTS,
            selectedSlot = source.slot,
            detail = null,
        )
    }
}

data class PlatformSummaryUi(
    val id: String,
    val name: String,
    val verificationCodeCount: Int,
    val latestAtText: String,
    val slot1Count: Int = 0,
    val slot2Count: Int = 0,
    val unknownCount: Int = 0,
)

fun filterPlatformSummaries(platforms: List<PlatformSummaryUi>, query: String): List<PlatformSummaryUi> {
    val clean = query.trim()
    if (clean.isBlank()) return platforms
    return platforms.filter { it.name.contains(clean, ignoreCase = true) || it.id.contains(clean, ignoreCase = true) }
}

fun filterManagedLabels(labels: List<ManagedLabelUi>, query: String): List<ManagedLabelUi> {
    val clean = query.trim()
    if (clean.isBlank()) return labels
    return labels.filter { it.displayName.contains(clean, ignoreCase = true) || it.id.contains(clean, ignoreCase = true) }
}

fun labelListItemText(label: ManagedLabelUi): String = label.displayName

private const val PageContentLayer = 0f
private const val PageOverlayLayer = 1f

fun bottomNavigationHeightDp(screenHeightDp: Int): Float = screenHeightDp * 0.10f

fun shouldShowBottomNavigation(navigation: PlatformNavigationState, imeVisible: Boolean): Boolean =
    !navigation.hidesBottomBar && !imeVisible

fun shouldRequestKeyboardOnFocus(hasFocus: Boolean, enabled: Boolean): Boolean = hasFocus && enabled

data class SlotCardUi(
    val filter: PlatformSlotFilter,
    val name: String,
    val smsCount: Int,
)

data class EvidenceMessageUi(
    val messageId: Long,
    val senderText: String,
    val receivedAtText: String,
    val simAndSlotText: String,
    val availabilityText: String = "可查看系统短信",
)

data class PendingCandidateUi(
    val messageId: Long,
    val suggestedPlatform: String?,
    val explanation: String,
    val existingLabels: List<LabelChoiceUi>,
    val labelSearchText: String = "",
    val selectedLabel: LabelChoiceUi? = null,
    val createDialogOpen: Boolean = false,
    val createError: String? = null,
    val submitInProgress: Boolean = false,
    val submitError: String? = null,
) {
    val canCreateLabel: Boolean
        get() = labelSearchText.isNotBlank() && !submitInProgress

    val canSubmit: Boolean
        get() = selectedLabel != null && !submitInProgress
}

data class LabelChoiceUi(
    val labelId: Long,
    val platformKey: String,
    val displayName: String,
)

data class ManagedLabelUi(
    val id: String,
    val displayName: String,
    val renameDraft: String,
    val aliasesText: String,
)

data class PlatformScreensUiState(
    val navigation: PlatformNavigationState = PlatformNavigationState(),
    val platformSlotFilter: PlatformSlotFilter = PlatformSlotFilter.ALL,
    val messageDetail: MessageDetailUi? = null,
    val platforms: List<PlatformSummaryUi> = emptyList(),
    val selectedPlatformName: String = "",
    val selectedPlatformEvidence: List<EvidenceMessageUi> = emptyList(),
    val platformEvidenceLoading: Boolean = false,
    val platformEvidenceErrorText: String? = null,
    val platformEvidencePermissionUnavailable: Boolean = false,
    val pendingCandidate: PendingCandidateUi? = null,
    val pendingPermissionUnavailable: Boolean = false,
    val pendingLabelCount: Int = 0,
    val slots: List<SlotCardUi> = emptyList(),
    val selectedSlotPlatforms: List<PlatformSummaryUi> = emptyList(),
    val slotDetailLoading: Boolean = false,
    val slotDetailErrorText: String? = null,
    val slotDetailPermissionUnavailable: Boolean = false,
    val labels: List<ManagedLabelUi> = emptyList(),
    val actionNotice: String? = null,
)

data class MessageDetailUi(
    val senderText: String,
    val body: String?,
    val receivedAtText: String,
    val sourceText: String,
    val statusText: String? = null,
    val canRequestPermission: Boolean = false,
)

const val MESSAGE_DETAIL_PERMISSION_UNAVAILABLE = "短信读取权限不可用"

@Composable
private fun SearchTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    autoFocus: Boolean = false,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val currentValue by rememberUpdatedState(value)
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    var editText by remember { mutableStateOf<EditText?>(null) }
    LaunchedEffect(autoFocus, enabled, editText) {
        val target = editText
        if (autoFocus && enabled && target != null) {
            withFrameNanos { }
            target.requestFocus()
            showSoftInputFromAndroidView(context, target)
        }
    }
    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            EditText(viewContext).apply {
                editText = this
                hint = label
                isSingleLine = true
                inputType = InputType.TYPE_CLASS_TEXT
                imeOptions = EditorInfo.IME_ACTION_SEARCH
                setTextColor(Color.rgb(32, 29, 36))
                setHintTextColor(Color.rgb(96, 91, 101))
                textSize = 18f
                minHeight = (56 * resources.displayMetrics.density).toInt()
                setPadding(
                    (16 * resources.displayMetrics.density).toInt(),
                    0,
                    (16 * resources.displayMetrics.density).toInt(),
                    0,
                )
                background = searchFieldBackground()
                setOnFocusChangeListener { focusedView, hasFocus ->
                    if (shouldRequestKeyboardOnFocus(hasFocus, isEnabled)) {
                        showSoftInputFromAndroidView(viewContext, focusedView)
                    }
                }
                setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                        focusManager.clearFocus()
                        hideSoftInputFromAndroidView(viewContext, this)
                        true
                    } else {
                        false
                    }
                }
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        val next = s?.toString().orEmpty()
                        if (next != currentValue) currentOnValueChange(next)
                    }
                    override fun afterTextChanged(s: Editable?) = Unit
                })
            }
        },
        update = { field ->
            editText = field
            field.hint = label
            field.isEnabled = enabled
            if (field.text.toString() != value) {
                field.setText(value)
                field.setSelection(field.text.length)
            }
        },
    )
}

private fun searchFieldBackground(): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadius = 4f
    setColor(Color.TRANSPARENT)
    setStroke(3, Color.rgb(121, 116, 126))
}

private fun showSoftInputFromAndroidView(context: Context, view: View) {
    val inputMethodManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        ?: return
    view.post {
        if (!view.hasFocus()) {
            view.requestFocus()
        }
        inputMethodManager.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.windowInsetsController?.show(AndroidWindowInsets.Type.ime())
        }
    }
}

private fun hideSoftInputFromAndroidView(context: Context, view: View) {
    val inputMethodManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        ?: return
    inputMethodManager.hideSoftInputFromWindow(view.windowToken, 0)
}

fun messageDetailUi(
    navigation: MessageDetailNavigation?,
    senderText: String?,
    body: String?,
    receivedAtText: String?,
    statusText: String?,
): MessageDetailUi? = navigation?.let { detail ->
    MessageDetailUi(
        senderText = if (statusText == null) senderText.orEmpty() else "",
        receivedAtText = if (statusText == null) receivedAtText.orEmpty() else "",
        sourceText = when (detail.source) {
            is MessageDetailSource.PlatformEvidence -> "来自平台详情"
            is MessageDetailSource.SlotDetail -> "来自卡槽详情"
            MessageDetailSource.Scan -> "来自扫描页"
        },
        body = if (statusText == null) body else null,
        statusText = statusText,
        canRequestPermission = statusText == MESSAGE_DETAIL_PERMISSION_UNAVAILABLE,
    )
}

data class PlatformScreensCallbacks(
    val onNavigation: (PlatformNavigationEvent) -> Unit,
    val onOpenEvidence: (Long) -> Unit,
    val onAcceptSuggestedLabel: (Long) -> Unit,
    val onChooseExistingLabel: (Long, LabelChoiceUi?) -> Unit,
    val onConfirmPendingSelection: (LabelChoiceUi) -> Unit = {},
    val onCreateLabel: (Long) -> Unit,
    val onNewLabelDraftChange: (String) -> Unit,
    val onPlatformSlotFilterChange: (PlatformSlotFilter) -> Unit,
    val onOpenSlotPlatform: (PlatformSummaryUi, PlatformSlotFilter) -> Unit = { _, _ -> },
    val onCreateStandaloneLabel: (String, List<LabelChoiceUi>) -> Unit = { _, _ -> },
    val onRequestSmsPermission: () -> Unit,
    val onOpenCreateLabel: (Long) -> Unit = {},
    val onDismissCreateLabel: () -> Unit = {},
    val onRenameDraftChange: (String, String) -> Unit = { _, _ -> },
    val onRenameLabel: (String) -> Unit = {},
    val onMergeLabel: (String) -> Unit = {},
    val onSplitLabel: (String) -> Unit = {},
)

@Composable
fun PlatformFeatureScaffold(
    state: PlatformScreensUiState,
    callbacks: PlatformScreensCallbacks,
    modifier: Modifier = Modifier,
    scanContent: @Composable (Modifier) -> Unit,
) {
    BackHandler(enabled = state.navigation.systemBackEvent() != null) {
        state.navigation.systemBackEvent()?.let(callbacks.onNavigation)
    }
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val showBottomBar = shouldShowBottomNavigation(state.navigation, imeVisible)
    val bottomBarHeight = bottomNavigationHeightDp(LocalConfiguration.current.screenHeightDp).dp
    Box(modifier.fillMaxSize()) {
        val contentModifier = if (showBottomBar) {
            Modifier
                .fillMaxSize()
                .padding(bottom = bottomBarHeight)
                .imePadding()
                .zIndex(PageContentLayer)
        } else {
            Modifier
                .fillMaxSize()
                .imePadding()
                .zIndex(PageContentLayer)
        }
        if (state.navigation.detail != null) {
            MessageDetailScreen(state.messageDetail, callbacks.onNavigation, callbacks.onRequestSmsPermission, contentModifier)
        } else when {
            state.navigation.pendingOpen -> PendingLabelScreen(
                state.pendingCandidate,
                state.pendingPermissionUnavailable,
                state.pendingLabelCount,
                callbacks,
                contentModifier,
            )
            state.navigation.selectedPlatformId != null -> PlatformEvidenceScreen(
                state.selectedPlatformName,
                state.selectedPlatformEvidence,
                state.platformEvidenceLoading,
                state.platformEvidenceErrorText,
                state.platformEvidencePermissionUnavailable,
                callbacks.onOpenEvidence,
                { callbacks.onNavigation(PlatformNavigationEvent.ClosePlatform) },
                callbacks.onRequestSmsPermission,
                contentModifier,
            )
            state.navigation.selectedSlot != null -> SlotDetailScreen(
                state.navigation.selectedSlot,
                state.slots.firstOrNull { it.filter == state.navigation.selectedSlot },
                state.selectedSlotPlatforms,
                state.slotDetailLoading,
                state.slotDetailErrorText,
                state.slotDetailPermissionUnavailable,
                callbacks,
                contentModifier,
            )
            state.navigation.selectedLabelId != null -> LabelDetailScreen(
                state.labels.firstOrNull { it.id == state.navigation.selectedLabelId },
                callbacks,
                contentModifier,
            )
            state.navigation.labelCreateOpen -> LabelCreateScreen(state.labels, callbacks, contentModifier)
            else -> when (state.navigation.destination) {
                PrimaryDestination.SCAN -> scanContent(contentModifier)
                PrimaryDestination.PLATFORMS -> PlatformListScreen(state.platforms, callbacks, contentModifier)
                PrimaryDestination.SLOTS -> SlotListScreen(state.slots, callbacks, contentModifier)
                PrimaryDestination.LABELS -> LabelListScreen(state.labels, state.actionNotice, callbacks, contentModifier)
            }
        }
        if (showBottomBar) {
            NavigationBar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(bottomBarHeight)
                    .zIndex(PageOverlayLayer),
                windowInsets = WindowInsets(0.dp),
            ) {
                PrimaryDestination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = state.navigation.destination == destination,
                        onClick = { callbacks.onNavigation(PlatformNavigationEvent.SelectDestination(destination)) },
                        icon = { Text(destination.label.take(1)) },
                        label = { Text(destination.label) },
                    )
                }
            }
        }
    }
}

@Composable
fun MessageDetailScreen(
    detail: MessageDetailUi?,
    onNavigation: (PlatformNavigationEvent) -> Unit,
    onRequestSmsPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { TextButton(onClick = { onNavigation(PlatformNavigationEvent.CloseMessageDetail) }) { Text("返回") } }
        item { Text("短信详情", style = MaterialTheme.typography.headlineMedium) }
        if (detail == null) {
            item { Text("正在读取短信详情...") }
        } else {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        detail.statusText?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                        if (detail.senderText.isNotBlank()) Text(detail.senderText, style = MaterialTheme.typography.titleMedium)
                        if (detail.receivedAtText.isNotBlank()) Text(detail.receivedAtText)
                        Text(detail.sourceText)
                        if (detail.canRequestPermission) {
                            Button(onClick = onRequestSmsPermission) { Text("重新授权") }
                        }
                        detail.body?.let { Text(it) }
                    }
                }
            }
        }
    }
}

@Composable
fun PlatformListScreen(
    platforms: List<PlatformSummaryUi>,
    callbacks: PlatformScreensCallbacks,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    val filtered = filterPlatformSummaries(platforms, query)
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SearchTextField(
                value = query,
                onValueChange = { query = it },
                label = "搜索平台",
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Card(Modifier.fillMaxWidth().clickable { callbacks.onNavigation(PlatformNavigationEvent.OpenPendingLabels) }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("待标注平台", style = MaterialTheme.typography.titleMedium)
                    Text("进入待标注短信页")
                }
            }
        }
        if (filtered.isEmpty()) item { Text("没有匹配的平台。") }
        items(filtered, key = { it.id }) { platform ->
            Card(Modifier.fillMaxWidth().clickable { callbacks.onNavigation(PlatformNavigationEvent.OpenPlatform(platform.id)) }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(platform.name, style = MaterialTheme.typography.titleMedium)
                    Text("验证码 ${platform.verificationCodeCount} 条 · 最近 ${platform.latestAtText}")
                }
            }
        }
    }
}

@Composable
fun PlatformEvidenceScreen(
    platformName: String,
    evidence: List<EvidenceMessageUi>,
    loading: Boolean,
    errorText: String?,
    permissionUnavailable: Boolean,
    onOpenEvidence: (Long) -> Unit,
    onBack: () -> Unit,
    onRequestSmsPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { TextButton(onClick = onBack) { Text("返回") } }
        item { Text(platformName.ifBlank { "平台详情" }, style = MaterialTheme.typography.headlineMedium) }
        item { Text("平台详情信息") }
        if (loading) item {
            Text("正在读取平台短信...")
        } else if (permissionUnavailable) item {
            Text(MESSAGE_DETAIL_PERMISSION_UNAVAILABLE)
            Button(onClick = onRequestSmsPermission) { Text("重新授权") }
        } else if (errorText != null) item {
            Text(errorText)
        } else if (evidence.isEmpty()) item { Text("当前没有可展示的短信。") }
        items(evidence, key = { it.messageId }) { message ->
            Card(Modifier.fillMaxWidth().clickable { onOpenEvidence(message.messageId) }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(message.senderText, style = MaterialTheme.typography.titleMedium)
                    Text(message.receivedAtText)
                    Text(message.simAndSlotText)
                    Text(message.availabilityText)
                }
            }
        }
    }
}

@Composable
fun PendingLabelScreen(
    candidate: PendingCandidateUi?,
    permissionUnavailable: Boolean,
    remainingCount: Int,
    callbacks: PlatformScreensCallbacks,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    var choosing by remember { mutableStateOf(false) }
    var selectionConfirmed by remember { mutableStateOf(false) }
    LaunchedEffect(candidate?.messageId) {
        choosing = false
        selectionConfirmed = false
    }
    Box(modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().zIndex(PageContentLayer),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                    TextButton(onClick = { callbacks.onNavigation(PlatformNavigationEvent.ClosePendingLabels) }) { Text("返回") }
                }
            }
            item { Text("还剩 $remainingCount 条短信待标注", style = MaterialTheme.typography.titleMedium) }
            if (permissionUnavailable) {
                item { Text(MESSAGE_DETAIL_PERMISSION_UNAVAILABLE) }
                item { Button(onClick = callbacks.onRequestSmsPermission) { Text("重新授权") } }
                return@LazyColumn
            }
            if (candidate == null) {
                item { Text("目前没有需要确认的候选。") }
                return@LazyColumn
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(candidate.explanation)
                    }
                }
            }
            item {
                Button(onClick = { choosing = true }, enabled = !candidate.submitInProgress) { Text("选择标注") }
            }
            if (choosing) {
                item {
                    SearchTextField(
                        value = candidate.labelSearchText,
                        onValueChange = {
                            callbacks.onNewLabelDraftChange(it)
                            selectionConfirmed = false
                        },
                        label = "搜索标注",
                        enabled = !candidate.submitInProgress,
                        autoFocus = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                items(candidate.existingLabels, key = { it.platformKey }) { label ->
                    val selected = candidate.selectedLabel == label
                    TextButton(
                        onClick = {
                            callbacks.onChooseExistingLabel(candidate.messageId, if (selected) null else label)
                            selectionConfirmed = false
                        },
                        enabled = !candidate.submitInProgress,
                    ) { Text("${if (selected) "✓ " else ""}${label.displayName}") }
                }
            }
            if (selectionConfirmed && candidate.selectedLabel != null) {
                item {
                    Button(
                        onClick = { callbacks.onAcceptSuggestedLabel(candidate.messageId) },
                        enabled = candidate.canSubmit,
                    ) { Text("确认标注") }
                }
            }
            candidate.submitError?.let { error -> item { Text(error) } }
        }
        val selected = candidate?.selectedLabel
        if (choosing && selected != null && !selectionConfirmed) {
            Button(
                onClick = {
                    callbacks.onConfirmPendingSelection(selected)
                    focusManager.clearFocus()
                    selectionConfirmed = true
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).zIndex(PageOverlayLayer),
                enabled = !candidate.submitInProgress,
            ) { Text("确认") }
        }
    }
}

@Composable
fun SlotListScreen(slots: List<SlotCardUi>, callbacks: PlatformScreensCallbacks, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        items(slots, key = { it.filter.name }) { slot ->
            Card(Modifier.fillMaxWidth().clickable { callbacks.onNavigation(PlatformNavigationEvent.OpenSlot(slot.filter)) }) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(slot.name, style = MaterialTheme.typography.headlineSmall)
                    Text("${slot.smsCount} 条短信")
                }
            }
        }
    }
}

@Composable
fun SlotDetailScreen(
    slot: PlatformSlotFilter?,
    slotCard: SlotCardUi?,
    platforms: List<PlatformSummaryUi>,
    loading: Boolean,
    errorText: String?,
    permissionUnavailable: Boolean,
    callbacks: PlatformScreensCallbacks,
    modifier: Modifier = Modifier,
) {
    var query by remember(slot) { mutableStateOf("") }
    val filtered = filterPlatformSummaries(platforms, query)
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { TextButton(onClick = { callbacks.onNavigation(PlatformNavigationEvent.CloseSlot) }) { Text("返回") } }
        item { Text(slotCard?.name ?: "卡槽详情", style = MaterialTheme.typography.headlineMedium) }
        item { Text("短信数量 ${slotCard?.smsCount ?: 0}") }
        item {
            SearchTextField(
                value = query,
                onValueChange = { query = it },
                label = "搜索平台",
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (loading) item {
            Text("正在读取卡槽平台...")
        } else if (permissionUnavailable) item {
            Text(MESSAGE_DETAIL_PERMISSION_UNAVAILABLE)
            Button(onClick = callbacks.onRequestSmsPermission) { Text("重新授权") }
        } else if (errorText != null) item {
            Text(errorText)
        } else if (platforms.isEmpty()) item {
            Text("此卡槽暂无已识别平台。")
        } else if (filtered.isEmpty()) item {
            Text("没有匹配的平台。")
        }
        items(filtered, key = { it.id }) { platform ->
            Card(Modifier.fillMaxWidth().clickable {
                slot?.let { callbacks.onOpenSlotPlatform(platform, it) }
            }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(platform.name, style = MaterialTheme.typography.titleMedium)
                    Text("验证码 ${platform.verificationCodeCount} 条 · 最近 ${platform.latestAtText}")
                }
            }
        }
    }
}

@Composable
fun LabelListScreen(labels: List<ManagedLabelUi>, actionNotice: String?, callbacks: PlatformScreensCallbacks, modifier: Modifier = Modifier) {
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val filtered = if (searchOpen) filterManagedLabels(labels, query) else labels
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { searchOpen = true }) { Text("搜索") }
                Button(onClick = { callbacks.onNavigation(PlatformNavigationEvent.OpenLabelCreate) }) { Text("创建") }
            }
        }
        if (searchOpen) {
            item {
                SearchTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = "搜索标签",
                    autoFocus = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotBlank()) {
                item {
                    TextButton(onClick = { callbacks.onCreateStandaloneLabel(query, emptyList()) }) {
                        Text(
                            "创建标签：$query",
                            textDecoration = TextDecoration.Underline,
                        )
                    }
                }
            }
        }
        actionNotice?.let { item { Text(it) } }
        if (filtered.isEmpty()) item { Text("还没有平台标签。") }
        items(filtered, key = { it.id }) { label ->
            Card(Modifier.fillMaxWidth().clickable { callbacks.onNavigation(PlatformNavigationEvent.OpenLabelDetail(label.id)) }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(labelListItemText(label), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
fun LabelDetailScreen(label: ManagedLabelUi?, callbacks: PlatformScreensCallbacks, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { TextButton(onClick = { callbacks.onNavigation(PlatformNavigationEvent.CloseLabelDetail) }) { Text("返回") } }
        item { Text(label?.displayName ?: "标签详情", style = MaterialTheme.typography.headlineMedium) }
        item { Text("平台标签：${label?.displayName.orEmpty()}") }
        item { Text("合并标签信息：${label?.aliasesText.orEmpty()}") }
    }
}

@Composable
fun LabelCreateScreen(labels: List<ManagedLabelUi>, callbacks: PlatformScreensCallbacks, modifier: Modifier = Modifier) {
    var labelName by remember { mutableStateOf("") }
    var mergeOpen by remember { mutableStateOf(false) }
    var mergeQuery by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<LabelChoiceUi>() }
    val choices = labels.map {
        LabelChoiceUi(
            labelId = it.id.hashCode().toLong(),
            platformKey = it.id,
            displayName = it.displayName,
        )
    }
    val selectedKeys = selected.map { it.platformKey }.toSet()
    val matched = if (mergeQuery.isBlank()) emptyList() else choices.filter {
        it.displayName.contains(mergeQuery, ignoreCase = true) || it.platformKey.contains(mergeQuery, ignoreCase = true)
    }
    val ordered = selected + matched.filterNot { it.platformKey in selectedKeys }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { TextButton(onClick = { callbacks.onNavigation(PlatformNavigationEvent.CloseLabelCreate) }) { Text("返回") } }
        item { Text("创建标签", style = MaterialTheme.typography.headlineMedium) }
        item {
            OutlinedTextField(
                value = labelName,
                onValueChange = { labelName = it },
                label = { Text("标签名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Button(onClick = { callbacks.onCreateStandaloneLabel(labelName, selected.toList()) }) { Text("创建") }
        }
        item {
            TextButton(onClick = { mergeOpen = true }) { Text("合并已有标签") }
        }
        if (selected.isNotEmpty() && !mergeOpen) {
            item {
                OutlinedTextField(
                    value = selected.joinToString(",") { it.displayName },
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("已选择合并标签") },
                    modifier = Modifier.fillMaxWidth().clickable { mergeOpen = true },
                )
            }
        }
        if (mergeOpen) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SearchTextField(
                        value = mergeQuery,
                        onValueChange = { mergeQuery = it },
                        label = "搜索已有标签",
                        autoFocus = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = { mergeOpen = false },
                        enabled = selected.isNotEmpty(),
                    ) { Text("确认") }
                }
            }
            if (ordered.isEmpty()) item { Text("输入搜索词后显示匹配标签。") }
            items(ordered, key = { it.platformKey }) { label ->
                val isSelected = selected.any { it.platformKey == label.platformKey }
                TextButton(onClick = {
                    if (isSelected) selected.removeAll { it.platformKey == label.platformKey } else selected.add(label)
                }) { Text("${if (isSelected) "✓ " else ""}${label.displayName}") }
            }
        }
    }
}
