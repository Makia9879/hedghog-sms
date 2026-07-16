---
title: 组装平台筛选与标签快捷绑定的实施验收合同
label: wayfinder:task
status: closed
assignee: codex
parent: 0000-decision-map.md
blocked_by:
  - 0001-platform-overview-simplification.md
  - 0002-pending-label-search-interaction.md
  - 0003-label-lookup-data-contract.md
  - 0005-independent-message-detail.md
---

## 问题

如何把已关闭票据的结论组装为一个最小纵向实施切片，明确 Compose 状态和控件、导航状态保留、ViewModel intent、DAO/事务边界、错误与空态，并覆盖平台卡槽筛选、标签搜索/去重、新建或已有标签绑定、重复提交幂等、隐私和现有导航/训练回归？

## 产物

形成可直接实施的变更清单与 JVM、Room/ViewModel、Compose UI、隐私回归验收矩阵；若结论要求独立标签实体或数据库迁移，必须显式拆分数据切片与 UI 切片。

## 解决记录

本 PRD 的最小纵向实施切片不新增独立标签实体、别名实体或数据库迁移。实现应围绕现有 `message_classification`、`message_index`、`training_*` 表和 `SmsSource.byId()` seam 扩展查询、状态和事务。

### 实施边界

必须实现：

1. 平台页按扫描时卡槽快照筛选，默认“全部”，可选“卡槽 1 / 卡槽 2 / 未知卡槽”。
2. 平台卡片精简为平台名称、当前筛选内验证码数量、当前筛选内最近接收时间和进入详情操作。
3. 平台证据列表沿用进入时的平台和卡槽筛选，返回后恢复原筛选与滚动上下文。
4. 待标注页一次只处理一条候选，提供可搜索单选标签下拉、固定快速创建入口、显式“绑定并训练”按钮。
5. 标签候选临时复用已分类平台事实源，候选项必须携带 `labelId/platformKey/displayName` 三元组。
6. 快速创建只形成临时已选标签；只有“绑定并训练”事务成功后才通过人工分类和训练样本进入事实源。
7. 独立短信详情页以 `messageId` 实时读取 Provider，并在返回时恢复来源列表或平台证据上下文。
8. 删除态、撤权态、加载态和敏感页面 `FLAG_SECURE` 规则必须覆盖详情与待标注敏感正文区域。

明确不做：

1. 不新增标签表、别名表、标签治理迁移、拼音/模糊搜索、远程搜索、批量标注或平台详情分页。
2. 不实现 SIM 集合筛选、当前物理卡槽迁移、标签重命名/合并/拆分治理。
3. 不把短信正文、验证码、sender 原文或正文预览写入 Room、日志、测试 fixture、缓存或备份。

### 数据与 DAO

1. 新增或调整平台聚合查询，使其接受卡槽筛选参数，并按以下口径聚合：
   - `ALL`：全部已识别平台，跨卡槽平台只出现一次。
   - `SLOT_1`：`message_index.slotSnapshotIndex=0` 且 `slotMappingStatus='RESOLVED'`。
   - `SLOT_2`：`message_index.slotSnapshotIndex=1` 且 `slotMappingStatus='RESOLVED'`。
   - `UNKNOWN`：`slotMappingStatus!='RESOLVED'`、`slotSnapshotIndex IS NULL` 或 `slotSnapshotIndex NOT IN (0,1)`。
2. 平台聚合字段只返回 `platformKey/displayName/otpCount/latestMessageDate`；现有卡槽分布字段可以保留给旧代码，但新 UI 不展示。
3. 平台证据查询必须同时接受 `platformKey` 和卡槽筛选参数，确保详情列表数量与平台概览数量同一口径。
4. `knownPlatforms()` 或新的标签候选查询继续只来自已存在 `platformKey/platformDisplayName` 的分类行，并在 ViewModel 层转换为 `labelId/platformKey/displayName`。
5. 增加统一名称规范化工具：`trim`、Unicode NFC、连续空白折叠、`lowercase(Locale.ROOT)` 比较 key。`PlatformRuleClassifier.stablePlatformKey()` 和 `stablePlatformLabelId()` 必须使用同一规范化结果，避免大小写或 Unicode 等价产生重复标签。
6. 收紧人工绑定事务：提交前确认目标 `sourceMessageId` 仍存在且分类状态仍为 `PENDING_LABEL`。同一 `sourceMessageId/sampleId + labelId + keyId` 重试幂等；改选不同标签时沿用扣旧加新的 correction 语义，并与分类表写回保持同一 Room transaction。
7. 详情删除态应通过 `SmsSource.byId(messageId)==null` 驱动；清理索引、训练样本和训练特征可以在本切片内接入已有删除 helper，或形成紧邻实现任务，但 UI 必须先正确展示删除态。

### ViewModel 与状态

1. 平台页状态新增 `PlatformSlotFilter`，用可恢复状态保存；切换一级页面再返回仍保留，直到用户主动更改或进程结束。
2. 平台详情状态保存 `platformKey`、平台显示名、进入时 `PlatformSlotFilter`、证据列表加载状态、错误状态和滚动 key。
3. 待标注状态保存当前候选 `messageId`、搜索文本、当前已选标签、快速创建弹窗状态、提交中状态和失败提示。
4. 绑定 intent 必须显式携带 expected `messageId` 和选中标签三元组；如果 ViewModel 当前候选已变化，拒绝提交并刷新候选或保留失败提示。
5. 独立详情状态使用 `messageId + sourceContext` 建模。`sourceContext` 至少区分短信列表和平台证据，并携带恢复来源所需的筛选、平台和滚动锚点。
6. `SmsPermissionUnavailableException` 进入 UI 状态，不抛到 Compose；详情、历史列表、平台证据和待标注都不得在撤权后保留正文。

### Compose UI

1. 平台概览顶部使用单选控件或等价下拉，选项固定为“全部 / 卡槽 1 / 卡槽 2 / 未知卡槽”。
2. 平台卡片移除卡槽分布行和硬编码“已分类”状态，只保留本切片裁定的三项事实和进入详情操作。
3. 具体卡槽无结果时显示“此卡槽暂无已识别平台”，并提供“查看全部”快捷操作；全部无数据时保留扫描/确认后出现平台的空态。
4. 待标注标签下拉展开后第一行固定搜索框，第二行固定快速创建入口；结果从第三行开始，可滚动并适配小屏和软键盘。
5. 快速创建弹窗校验空名和精确重复，失败留在弹窗内；成功只设为当前选择，不提交。
6. “绑定并训练”按钮只在有效选择且非提交中可用；提交中禁用下拉、快速创建和提交动作；失败保留候选、搜索和已选标签。
7. 独立详情页加载态不显示旧正文；删除态显示“系统短信已删除”；撤权态显示“短信读取权限不可用”并提供重授权和返回来源操作。
8. 页面内返回和系统返回行为一致：从短信列表来就回到原短信列表上下文；从平台证据来就回到原平台详情上下文。

### 测试矩阵

JVM 单测：

1. 名称规范化覆盖首尾空白、连续空白、大小写、NFC 等价、空名称拒绝和精确重复拒绝。
2. 平台卡槽筛选 reducer 覆盖全部、卡槽 1、卡槽 2、未知和“查看全部”。
3. 待标注状态 reducer 覆盖选择已有标签、快速创建成功/取消/重复、提交中禁用、失败保留状态、成功清空临时选择。
4. 详情导航 reducer 覆盖从短信列表进入/返回、从平台证据进入/返回、系统返回和页面内返回一致。

Room/仪器测试：

1. 平台聚合与证据查询在四种卡槽筛选下数量和最近时间一致。
2. 人工绑定同标签重复提交不重复训练。
3. 分类状态不再是 `PENDING_LABEL` 或候选 `messageId` 变化时拒绝绑定。
4. 分类表写回失败时训练统计不得单边提交。
5. `SmsSource.byId()` 返回 `null` 时详情进入删除态，且清理逻辑不扣减已聚合模型统计。

Compose/UI 测试：

1. 平台筛选切换、进入详情、返回后筛选与滚动保持。
2. 待标注下拉在小屏和软键盘场景下搜索框、快速创建入口和结果列表均可操作。
3. 快速创建重复名错误留在弹窗内；成功后仍需点击“绑定并训练”。
4. 详情从平台证据进入后返回仍在平台证据页，不跳到短信首页。
5. 删除态、撤权态和加载态均不展示正文。

真机无线调试验收：

1. 使用无线调试真机安装 debug APK，目标设备按当前项目基线优先使用 Android 16 / API 36 双卡真实短信环境。
2. 验证 hard-restricted SMS 权限路径：首次授权、拒绝、撤权、恢复均不崩溃，撤权后详情和待标注正文隐藏。
3. 验证平台卡槽筛选：全部、卡槽 1、卡槽 2、未知的数量与证据列表一致；进入详情返回保留筛选和滚动。
4. 验证待标注：搜索已有标签、快速创建新标签、重复名错误、显式绑定训练、重复点击防护和失败重试。
5. 验证独立详情：从短信列表和平台证据分别进入，系统返回和页面返回均恢复来源上下文；系统短信删除或权限撤销时显示明确不可用状态。
6. 验证隐私：Room/文件/日志不出现短信正文、验证码、sender 原文或正文预览；敏感页面默认防截屏。

### 建议执行顺序

1. 先加规范化工具和 DAO 查询，补 JVM/Room 测试。
2. 再重构 ViewModel 状态与 intents，补 reducer/状态测试。
3. 再改 Compose 平台筛选、待标注下拉和独立详情导航。
4. 最后跑单元/仪器测试、assemble debug，并用无线真机做上述验收。
