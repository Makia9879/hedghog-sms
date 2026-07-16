package com.makia.hedgehogsms.ui.platform

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.makia.hedgehogsms.classification.PlatformSlotFilter

enum class PrimaryDestination(val label: String) {
    MESSAGES("短信"), PLATFORMS("平台"), PENDING("待标注"), LABELS("标签"),
}

data class PlatformNavigationState(
    val destination: PrimaryDestination = PrimaryDestination.MESSAGES,
    val selectedPlatformId: String? = null,
    val detail: MessageDetailNavigation? = null,
)

fun PlatformNavigationState.isSensitiveScreen(): Boolean =
    detail != null || selectedPlatformId != null || destination == PrimaryDestination.PENDING

fun PlatformNavigationState.systemBackEvent(): PlatformNavigationEvent? = when {
    detail != null -> PlatformNavigationEvent.CloseMessageDetail
    selectedPlatformId != null -> PlatformNavigationEvent.ClosePlatform
    else -> null
}

sealed interface MessageDetailSource {
    data object Messages : MessageDetailSource
    data class PlatformEvidence(val platformId: String) : MessageDetailSource
}

data class MessageDetailNavigation(
    val messageId: Long,
    val source: MessageDetailSource,
)

sealed interface PlatformNavigationEvent {
    data class SelectDestination(val destination: PrimaryDestination) : PlatformNavigationEvent
    data class OpenPlatform(val platformId: String) : PlatformNavigationEvent
    data object ClosePlatform : PlatformNavigationEvent
    data class OpenMessageDetail(val messageId: Long, val source: MessageDetailSource) : PlatformNavigationEvent
    data object CloseMessageDetail : PlatformNavigationEvent
}

fun PlatformNavigationState.reduce(event: PlatformNavigationEvent): PlatformNavigationState = when (event) {
    is PlatformNavigationEvent.SelectDestination -> copy(destination = event.destination, selectedPlatformId = null, detail = null)
    is PlatformNavigationEvent.OpenPlatform -> copy(destination = PrimaryDestination.PLATFORMS, selectedPlatformId = event.platformId, detail = null)
    PlatformNavigationEvent.ClosePlatform -> copy(selectedPlatformId = null)
    is PlatformNavigationEvent.OpenMessageDetail -> when (val source = event.source) {
        MessageDetailSource.Messages -> copy(
            destination = PrimaryDestination.MESSAGES,
            detail = MessageDetailNavigation(event.messageId, source),
        )
        is MessageDetailSource.PlatformEvidence -> copy(
            destination = PrimaryDestination.PLATFORMS,
            selectedPlatformId = source.platformId,
            detail = MessageDetailNavigation(event.messageId, source),
        )
    }
    PlatformNavigationEvent.CloseMessageDetail -> when (val source = detail?.source) {
        null, MessageDetailSource.Messages -> copy(destination = PrimaryDestination.MESSAGES, detail = null)
        is MessageDetailSource.PlatformEvidence -> copy(
            destination = PrimaryDestination.PLATFORMS,
            selectedPlatformId = source.platformId,
            detail = null,
        )
    }
}

data class PlatformSummaryUi(
    val id: String,
    val name: String,
    val verificationCodeCount: Int,
    val latestAtText: String,
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
            is MessageDetailSource.PlatformEvidence -> "来自平台证据"
            MessageDetailSource.Messages -> "来自短信列表"
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
    val onChooseExistingLabel: (Long, LabelChoiceUi) -> Unit,
    val onCreateLabel: (Long) -> Unit,
    val onOpenCreateLabel: (Long) -> Unit,
    val onDismissCreateLabel: () -> Unit,
    val onNewLabelDraftChange: (String) -> Unit,
    val onPlatformSlotFilterChange: (PlatformSlotFilter) -> Unit,
    val onRenameDraftChange: (String, String) -> Unit,
    val onRenameLabel: (String) -> Unit,
    val onMergeLabel: (String) -> Unit,
    val onSplitLabel: (String) -> Unit,
    val onRequestSmsPermission: () -> Unit,
)

@Composable
fun PlatformFeatureScaffold(
    state: PlatformScreensUiState,
    callbacks: PlatformScreensCallbacks,
    modifier: Modifier = Modifier,
    messagesContent: @Composable (Modifier) -> Unit,
) {
    BackHandler(enabled = state.navigation.systemBackEvent() != null) {
        state.navigation.systemBackEvent()?.let(callbacks.onNavigation)
    }
    Scaffold(
        modifier = modifier,
        bottomBar = {
            NavigationBar {
                PrimaryDestination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = state.navigation.destination == destination,
                        onClick = { callbacks.onNavigation(PlatformNavigationEvent.SelectDestination(destination)) },
                        icon = { Text(destination.label.take(1)) },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { padding ->
        val contentModifier = Modifier.padding(padding)
        if (state.navigation.detail != null) {
            MessageDetailScreen(state.messageDetail, callbacks.onNavigation, callbacks.onRequestSmsPermission, contentModifier)
        } else when (state.navigation.destination) {
            PrimaryDestination.MESSAGES -> messagesContent(contentModifier)
            PrimaryDestination.PLATFORMS -> if (state.navigation.selectedPlatformId == null) {
                PlatformOverview(
                    state.platforms,
                    state.platformSlotFilter,
                    callbacks.onPlatformSlotFilterChange,
                    callbacks.onNavigation,
                    contentModifier,
                )
            } else {
                PlatformEvidenceScreen(
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
            }
            PrimaryDestination.PENDING -> PendingLabelScreen(
                state.pendingCandidate,
                state.pendingPermissionUnavailable,
                callbacks,
                contentModifier,
            )
            PrimaryDestination.LABELS -> LabelManagementScreen(state.labels, state.actionNotice, callbacks, contentModifier)
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
                        detail.body?.let {
                            Text(it)
                            Text("正文从系统信箱实时读取")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PlatformOverview(
    platforms: List<PlatformSummaryUi>,
    selectedFilter: PlatformSlotFilter,
    onFilterChange: (PlatformSlotFilter) -> Unit,
    onNavigation: (PlatformNavigationEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("平台概览", style = MaterialTheme.typography.headlineMedium) }
        item { Text("平台来自验证码证据；发送者号码本身不等于平台。") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PlatformSlotFilter.entries.forEach { filter ->
                    Button(onClick = { onFilterChange(filter) }, enabled = selectedFilter != filter) {
                        Text(filter.label)
                    }
                }
            }
        }
        if (platforms.isEmpty()) item {
            if (selectedFilter == PlatformSlotFilter.ALL) {
                Text("扫描和确认后，平台会显示在这里。")
            } else {
                Text("此卡槽暂无已识别平台")
                TextButton(onClick = { onFilterChange(PlatformSlotFilter.ALL) }) { Text("查看全部") }
            }
        }
        items(platforms, key = { it.id }) { platform ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(platform.name, style = MaterialTheme.typography.titleMedium)
                    Text("验证码 ${platform.verificationCodeCount} 条 · 最近 ${platform.latestAtText}")
                    Button(onClick = { onNavigation(PlatformNavigationEvent.OpenPlatform(platform.id)) }) { Text("查看注销线索") }
                }
            }
        }
    }
}

private val PlatformSlotFilter.label: String
    get() = when (this) {
        PlatformSlotFilter.ALL -> "全部"
        PlatformSlotFilter.SLOT_1 -> "卡槽 1"
        PlatformSlotFilter.SLOT_2 -> "卡槽 2"
        PlatformSlotFilter.UNKNOWN -> "未知卡槽"
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
        item { TextButton(onClick = onBack) { Text("返回平台") } }
        item { Text(platformName, style = MaterialTheme.typography.headlineMedium) }
        item { Text("以下是本机短信证据。请自行前往平台处理账号，本应用不执行注销。") }
        if (loading) item {
            Text("正在读取证据短信...")
        } else if (permissionUnavailable) item {
            Text(MESSAGE_DETAIL_PERMISSION_UNAVAILABLE)
            Button(onClick = onRequestSmsPermission) { Text("重新授权") }
        } else if (errorText != null) item {
            Text(errorText)
        } else if (evidence.isEmpty()) item { Text("当前没有可展示的证据短信。") }
        items(evidence, key = { it.messageId }) { message ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(message.senderText, style = MaterialTheme.typography.titleMedium)
                    Text(message.receivedAtText)
                    Text(message.simAndSlotText)
                    Text(message.availabilityText)
                    Button(onClick = { onOpenEvidence(message.messageId) }) { Text("查看短信证据") }
                }
            }
        }
    }
}

@Composable
fun PendingLabelScreen(
    candidate: PendingCandidateUi?,
    permissionUnavailable: Boolean,
    callbacks: PlatformScreensCallbacks,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("待标注", style = MaterialTheme.typography.headlineMedium) }
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
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(candidate.suggestedPlatform ?: "请选择或新建平台", style = MaterialTheme.typography.titleMedium)
                    Text(candidate.explanation)
                    candidate.selectedLabel?.let { Text("已选择：${it.displayName}") }
                    OutlinedTextField(
                        value = candidate.labelSearchText,
                        onValueChange = callbacks.onNewLabelDraftChange,
                        label = { Text("搜索或新建平台标签") },
                        singleLine = true,
                        enabled = !candidate.submitInProgress,
                    )
                    TextButton(
                        onClick = { callbacks.onOpenCreateLabel(candidate.messageId) },
                        enabled = candidate.canCreateLabel,
                    ) { Text("快速创建标签") }
                    candidate.submitError?.let { Text(it) }
                    Button(
                        onClick = { callbacks.onAcceptSuggestedLabel(candidate.messageId) },
                        enabled = candidate.canSubmit,
                    ) { Text("绑定并训练") }
                }
            }
        }
        items(candidate.existingLabels, key = { it.platformKey }) { label ->
            TextButton(
                onClick = { callbacks.onChooseExistingLabel(candidate.messageId, label) },
                enabled = !candidate.submitInProgress,
            ) { Text("选择 ${label.displayName}") }
        }
    }
    val dialogCandidate = candidate
    if (dialogCandidate?.createDialogOpen == true) {
        AlertDialog(
            onDismissRequest = callbacks.onDismissCreateLabel,
            title = { Text("快速创建标签") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = dialogCandidate.labelSearchText,
                        onValueChange = callbacks.onNewLabelDraftChange,
                        label = { Text("标签名称") },
                        singleLine = true,
                        enabled = !dialogCandidate.submitInProgress,
                    )
                    dialogCandidate.createError?.let { Text(it) }
                }
            },
            confirmButton = {
                Button(
                    onClick = { callbacks.onCreateLabel(dialogCandidate.messageId) },
                    enabled = !dialogCandidate.submitInProgress,
                ) { Text("确认") }
            },
            dismissButton = {
                TextButton(
                    onClick = callbacks.onDismissCreateLabel,
                    enabled = !dialogCandidate.submitInProgress,
                ) { Text("取消") }
            },
        )
    }
}

@Composable
fun LabelManagementScreen(labels: List<ManagedLabelUi>, actionNotice: String?, callbacks: PlatformScreensCallbacks, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("标签管理", style = MaterialTheme.typography.headlineMedium) }
        item { Text("合并保留旧名为别名；拆分需要逐条人工重新标注。") }
        actionNotice?.let { notice -> item { Text(notice) } }
        if (labels.isEmpty()) item { Text("还没有平台标签。") }
        items(labels, key = { it.id }) { label ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(label.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(label.aliasesText)
                    OutlinedTextField(
                        value = label.renameDraft,
                        onValueChange = { callbacks.onRenameDraftChange(label.id, it) },
                        label = { Text("标签名称") },
                        singleLine = true,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { callbacks.onRenameLabel(label.id) }) { Text("重命名") }
                        TextButton(onClick = { callbacks.onMergeLabel(label.id) }) { Text("合并") }
                        TextButton(onClick = { callbacks.onSplitLabel(label.id) }) { Text("拆分") }
                    }
                }
            }
        }
    }
}
