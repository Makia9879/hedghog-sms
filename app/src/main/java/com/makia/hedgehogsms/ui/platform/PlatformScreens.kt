package com.makia.hedgehogsms.ui.platform

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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

enum class PrimaryDestination(val label: String) {
    MESSAGES("短信"), PLATFORMS("平台"), PENDING("待标注"), LABELS("标签"),
}

data class PlatformNavigationState(
    val destination: PrimaryDestination = PrimaryDestination.MESSAGES,
    val selectedPlatformId: String? = null,
)

sealed interface PlatformNavigationEvent {
    data class SelectDestination(val destination: PrimaryDestination) : PlatformNavigationEvent
    data class OpenPlatform(val platformId: String) : PlatformNavigationEvent
    data object ClosePlatform : PlatformNavigationEvent
}

fun PlatformNavigationState.reduce(event: PlatformNavigationEvent): PlatformNavigationState = when (event) {
    is PlatformNavigationEvent.SelectDestination -> copy(destination = event.destination, selectedPlatformId = null)
    is PlatformNavigationEvent.OpenPlatform -> copy(destination = PrimaryDestination.PLATFORMS, selectedPlatformId = event.platformId)
    PlatformNavigationEvent.ClosePlatform -> copy(selectedPlatformId = null)
}

data class PlatformSummaryUi(
    val id: String,
    val name: String,
    val verificationCodeCount: Int,
    val latestAtText: String,
    val simAndSlotsText: String,
    val confidenceText: String,
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
    val newLabelDraft: String = "",
)

data class LabelChoiceUi(val id: String, val name: String)

data class ManagedLabelUi(
    val id: String,
    val displayName: String,
    val renameDraft: String,
    val aliasesText: String,
)

data class PlatformScreensUiState(
    val navigation: PlatformNavigationState = PlatformNavigationState(),
    val platforms: List<PlatformSummaryUi> = emptyList(),
    val selectedPlatformName: String = "",
    val selectedPlatformEvidence: List<EvidenceMessageUi> = emptyList(),
    val pendingCandidate: PendingCandidateUi? = null,
    val labels: List<ManagedLabelUi> = emptyList(),
    val actionNotice: String? = null,
)

data class PlatformScreensCallbacks(
    val onNavigation: (PlatformNavigationEvent) -> Unit,
    val onOpenEvidence: (Long) -> Unit,
    val onAcceptSuggestedLabel: (Long) -> Unit,
    val onChooseExistingLabel: (Long, String) -> Unit,
    val onCreateLabel: (Long) -> Unit,
    val onNewLabelDraftChange: (String) -> Unit,
    val onRenameDraftChange: (String, String) -> Unit,
    val onRenameLabel: (String) -> Unit,
    val onMergeLabel: (String) -> Unit,
    val onSplitLabel: (String) -> Unit,
)

@Composable
fun PlatformFeatureScaffold(
    state: PlatformScreensUiState,
    callbacks: PlatformScreensCallbacks,
    modifier: Modifier = Modifier,
    messagesContent: @Composable (Modifier) -> Unit,
) {
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
        when (state.navigation.destination) {
            PrimaryDestination.MESSAGES -> messagesContent(contentModifier)
            PrimaryDestination.PLATFORMS -> if (state.navigation.selectedPlatformId == null) {
                PlatformOverview(state.platforms, callbacks.onNavigation, contentModifier)
            } else {
                PlatformEvidenceScreen(
                    state.selectedPlatformName,
                    state.selectedPlatformEvidence,
                    callbacks.onOpenEvidence,
                    { callbacks.onNavigation(PlatformNavigationEvent.ClosePlatform) },
                    contentModifier,
                )
            }
            PrimaryDestination.PENDING -> PendingLabelScreen(state.pendingCandidate, callbacks, contentModifier)
            PrimaryDestination.LABELS -> LabelManagementScreen(state.labels, state.actionNotice, callbacks, contentModifier)
        }
    }
}

@Composable
fun PlatformOverview(
    platforms: List<PlatformSummaryUi>,
    onNavigation: (PlatformNavigationEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("平台概览", style = MaterialTheme.typography.headlineMedium) }
        item { Text("平台来自验证码证据；发送者号码本身不等于平台。") }
        if (platforms.isEmpty()) item { Text("扫描和确认后，平台会显示在这里。") }
        items(platforms, key = { it.id }) { platform ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(platform.name, style = MaterialTheme.typography.titleMedium)
                    Text("验证码 ${platform.verificationCodeCount} 条 · 最近 ${platform.latestAtText}")
                    Text(platform.simAndSlotsText)
                    Text(platform.confidenceText)
                    Button(onClick = { onNavigation(PlatformNavigationEvent.OpenPlatform(platform.id)) }) { Text("查看注销线索") }
                }
            }
        }
    }
}

@Composable
fun PlatformEvidenceScreen(
    platformName: String,
    evidence: List<EvidenceMessageUi>,
    onOpenEvidence: (Long) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { TextButton(onClick = onBack) { Text("返回平台") } }
        item { Text(platformName, style = MaterialTheme.typography.headlineMedium) }
        item { Text("以下是本机短信证据。请自行前往平台处理账号，本应用不执行注销。") }
        if (evidence.isEmpty()) item { Text("当前没有可展示的证据短信。") }
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
fun PendingLabelScreen(candidate: PendingCandidateUi?, callbacks: PlatformScreensCallbacks, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("待标注", style = MaterialTheme.typography.headlineMedium)
        if (candidate == null) {
            Text("目前没有需要确认的候选。")
            return@Column
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(candidate.suggestedPlatform ?: "请选择或新建平台", style = MaterialTheme.typography.titleMedium)
                Text(candidate.explanation)
                candidate.suggestedPlatform?.let {
                    Button(onClick = { callbacks.onAcceptSuggestedLabel(candidate.messageId) }) { Text("确认此平台") }
                }
                candidate.existingLabels.forEach { label ->
                    TextButton(onClick = { callbacks.onChooseExistingLabel(candidate.messageId, label.id) }) { Text("选择 ${label.name}") }
                }
                OutlinedTextField(
                    value = candidate.newLabelDraft,
                    onValueChange = callbacks.onNewLabelDraftChange,
                    label = { Text("新平台标签") },
                    singleLine = true,
                )
                TextButton(
                    onClick = { callbacks.onCreateLabel(candidate.messageId) },
                    enabled = candidate.newLabelDraft.isNotBlank(),
                ) { Text("新建平台并确认") }
            }
        }
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
