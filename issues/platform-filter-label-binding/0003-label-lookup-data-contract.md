---
title: 锁定标签查找去重与人工绑定的数据合同
label: wayfinder:research
status: closed
assignee: codex
parent: 0000-decision-map.md
blocked_by:
  - 0002-pending-label-search-interaction.md
---

## 问题

本切片的标签候选应临时复用已有平台汇总/`knownPlatforms()`，还是必须先接入独立标签与别名事实源；名称的空白、大小写、Unicode 等价、精确匹配和重复创建如何处理；重复点击、绑定失败、候选短信已变化时，怎样保证只把预期短信绑定并训练一次？

## 产物

把代码事实、推荐合同和不纳入本切片的标签治理边界写入本票解决记录，供最终实施合同引用。

## 解决记录

代码事实：

1. 当前没有独立标签表、别名表或“空标签”持久化事实源。可读事实源是 `message_classification` 中已存在 `platformKey/platformDisplayName` 的行，DAO 暴露为 `knownPlatforms()`；平台概览也来自同一分类表聚合。
2. 当前待标注页把 `uiState.platforms` 映射成候选标签，`LabelChoiceUi.id` 实际是 `platformKey`。选择已有标签再反查 `platformKey` 后调用 `confirmPendingLabel(displayName)`；这能跑通临时路径，但不是最终数据合同，因为 UI 没有稳定携带 `labelId/platformKey/displayName` 三元组。
3. `confirmPendingLabel()` 只做 `trim()` 后用 `PlatformRuleClassifier.stablePlatformLabelId()` 和 `stablePlatformKey()` 生成训练标签与平台 key；这两个稳定函数当前只做 `lowercase(Locale.ROOT)`，没有 Unicode 归一化或内部空白归一。
4. `TrainingRepository.confirmHumanClassification()` 已把训练样本、模型统计和 `message_classification` 人工确认写入放在同一个 Room transaction；同一 `sampleId + labelId` 重试不会重复增加模型统计，已有测试覆盖中断后重试的幂等与原子性。
5. 当前 `confirmHumanLabel()` 的 SQL 没有限制原状态为 `PENDING_LABEL`，也没有把“用户看到的候选短信仍是同一条”编码进提交前置条件；最终切片需要补齐这一层防竞态合同。

推荐合同：

1. 本切片不新增独立标签表、别名表、空标签持久化或标签治理数据模型。标签候选事实源临时锁定为 `knownPlatforms()`，即已在本机分类表中出现过的 `platformKey/platformDisplayName` 去重结果；尚无平台证据的独立标签、别名、重命名、合并和拆分继续不纳入本切片。
2. ViewModel/UI 层的标签候选必须显式携带三元组：`labelId`、`platformKey`、`displayName`。`labelId` 由规范化后的显示名生成，用于训练模型；`platformKey` 用于写回分类表和平台聚合；`displayName` 用于展示和写回人工确认名称。不得再把 `platformKey` 当作 label id。
3. 名称规范化使用同一条合同：先 `trim()`，再做 Unicode NFC 归一化，再把连续空白折叠为单个空格，最后用 `lowercase(Locale.ROOT)` 生成比较 key。显示名保留用户输入在 `trim + NFC + 折叠空白` 后的形态；比较 key 只用于搜索、精确重复判定和稳定 id/key 生成。
4. 搜索下拉按规范化比较 key 做大小写不敏感的包含匹配；“精确匹配”指搜索输入与某个候选的比较 key 完全相等。只有不存在精确匹配时才允许确认快速创建；如果存在精确匹配，弹窗内提示重复并引导选择已有标签。
5. 快速创建在本切片中只创建当前页面的临时已选标签，不立即写数据库。只有用户随后点击“绑定并训练”且事务成功时，该新标签才通过 `message_classification` 人工确认与训练样本自然进入事实源；取消、返回、提交失败或候选变化均不得留下空标签。
6. “绑定并训练”必须以当前待标注短信的 `sourceMessageId` 作为 expected id 提交。提交前重新确认该行仍存在且仍为 `PENDING_LABEL`；如果已被扫描删除、权限撤销、状态改变或当前候选已切换，提交失败并保留或刷新 UI，不得把选择绑定到新候选。
7. 提交事务沿用并收紧 `TrainingRepository.confirmHumanClassification()`：同一 `sourceMessageId/sampleId + labelId + keyId` 重试幂等；同一短信改选不同标签时必须按既有 correction 路径扣回旧标签统计再加到新标签，且整段与分类表写回同一事务提交。
8. 重复点击由 UI 和仓库双层防护：提交中禁用控件；仓库层仍必须保证同标签重试不重复训练。失败重试不得增加第二份训练样本，也不得在分类表写回失败时留下训练统计单边提交。

实施验收最低覆盖：

1. JVM 单测覆盖名称规范化：首尾空白、连续空白、大小写、NFC 等价、空名称拒绝、精确重复拒绝。
2. ViewModel 或 JVM 状态测试覆盖快速创建只形成临时选择，取消/失败不持久化，成功绑定后才进入候选事实源。
3. Room/仪器测试覆盖：同标签重复提交幂等；状态不再是 `PENDING_LABEL` 时拒绝绑定；事务失败不留下单边训练统计。
4. 真机无线调试验证放到 `0004` 后的实施切片：小屏软键盘下搜索、快速创建和提交禁用态可用，但不改变本票的数据合同。
