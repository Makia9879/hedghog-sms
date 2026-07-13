# 原生 Android 技术基线与端侧增量学习 ADR

状态：已决定  
日期：2026-07-12  
依赖决策：短信能力调研、验证码与平台分类合同

## 决策摘要

MVP 采用单 Activity 的原生 Kotlin Android App：Jetpack Compose + Material 3、单向数据流、Room、WorkManager、协程/Flow。`compileSdk = 36`、`targetSdk = 36`、`minSdk = 29`。

平台分类器选定为：**规则前置 + HMAC 字符 n-gram 特征 + 纯 Kotlin 在线多项式朴素贝叶斯（Online Multinomial Naive Bayes，MNB）**。它不是下载来的静态推理模型：用户确认或纠正标签后，App 在本机直接增减充分统计量，下一条短信立即使用更新后的模型。MVP 不引入 TFLite、ONNX Runtime、MediaPipe LLM 或端侧大模型。

选择 MNB 的理由：

- 原生支持增量训练：每个样本只更新类别文档数、类别特征总数和稀疏特征计数。
- 可动态新增平台类别，不需要重建固定输出维度的神经网络。
- 训练和推理都是短信长度的线性工作量，无模型下载、量化和 native ABI 体积。
- 人工纠正可以用该样本已保存的脱敏特征从旧类扣除，再计入新类；这是真实训练，不是把发送方写成规则。
- 算法和存储接口足够窄，后续可替换为支持端侧训练的在线线性模型，而不改 UI、短信读取或标签合同。

## Android 基线

| 范围 | 选型 | 决策 |
| --- | --- | --- |
| 语言/构建 | Kotlin、Gradle Kotlin DSL、版本目录 | 不引入跨平台层；开启 Java/Kotlin 17 toolchain |
| UI | Jetpack Compose、Material 3、Navigation Compose | 单 Activity；状态由 `StateFlow` 驱动；列表使用惰性容器和稳定 key |
| SDK | min 29 / compile 36 / target 36 | Android 10 起覆盖 `SubscriptionManager.getSlotIndex()`；目标机 Android 16/API 36 |
| 数据 | Room 2.8.4 + KSP | 存索引、标签、脱敏训练特征、模型统计和扫描水位；不存正文/验证码 |
| 后台 | WorkManager 2.11.2 + `CoroutineWorker` | 历史分批、恢复扫描、广播后的增量补扫；唯一工作避免重复 |
| 并发 | Kotlin coroutines + Flow | Provider/Room/分类在 `Dispatchers.IO`；主线程只更新 UI |
| DI | 手工构造的 application container | MVP 规模不引入 Hilt；接口仍可测试替换 |
| 网络 | 可选的窄 HTTP client | 仅用户开启并逐批确认 LLM 辅助标注时使用 |

Compose 使用实现时最新的 **stable Compose BOM** 并锁入版本目录；不得使用 alpha/beta BOM。Room/WorkManager 版本是 Android 官方文档在本决策日给出的稳定示例版本。若创建项目时官方稳定版已更新，只允许单独升级并跑回归测试，不把动态版本写入构建文件。

`minSdk = 29` 是有意的产品边界：首个目标为 Android 16，降低旧版权限/后台/订阅兼容分支。API 34 的 `getSubscriptionId(slotIndex)` 不是必需路径；API 29 已有 `getSlotIndex(subscriptionId)`。

## 处理流水线

```text
系统短信 Provider（正文临时读取）
  -> 验证码确定性规则
  -> 明确平台签名/用户覆盖规则
  -> FeatureExtractor
  -> IncrementalPlatformClassifier.predict
  -> 阈值策略
  -> 本地索引（messageId、标签、来源、置信状态）
```

正文只在查询游标到分类调用之间驻留内存。`FeatureExtractor` 输出后不把正文传给 Room；详情页再次从系统 Provider 实时读取。

## 可训练模型的精确定义

### 特征

1. 正规化 Unicode、统一拉丁字母大小写、折叠空白。
2. 在特征提取前替换验证码、连续数字、手机号、URL 查询值、日期/金额等易变或敏感片段为类型占位符。
3. 提取字符 1–3 gram，并加入少量布尔结构特征（短信签名存在、英文验证码语义、发送地址形态等）。中文不依赖分词器。
4. 以 Android Keystore 保存的每安装随机密钥做 HMAC-SHA-256；取结果映射到 `2^14 = 16,384` 个 bucket。数据库只存 bucket id 和离散计数，不存明文 n-gram。
5. 单个 bucket 的词频截断为 3，避免重复字符支配结果。

HMAC 特征是本地的降低泄露设计，不应宣称匿名化。卸载/清除数据导致密钥丢失时，模型与特征库必须一起清空或重建。

### 模型状态

每个平台标签维护：已确认文档数 `N_c`、特征总数 `T_c`、稀疏 `(labelId, bucketId) -> count`。全局维护已确认样本数和词表有效维度。采用 Laplace/Lidstone 平滑，默认 `alpha = 1.0`；用 log 概率求和，再经 log-sum-exp 归一化为候选分数。

训练输入只能来自：用户人工确认、用户纠正，或用户确认后的 LLM 候选。自动预测不能回灌模型，避免错误自强化。

### 新增、纠正和删除

- 新确认：在一个 Room 事务中保存样本的 HMAC bucket 计数并增加新标签统计；新平台可以即时创建新类别。
- 纠正：同一事务先从旧标签精确扣除该样本计数，再增加到新标签；所有计数不得小于 0。
- 系统短信删除：索引删除，但已确认训练样本默认保留其脱敏特征，以维持用户教给模型的知识；用户在隐私设置选择“删除学习记录”时才扣除并清除。
- 重命名平台：只改标签元数据；合并平台需合并充分统计量；拆分必须由用户逐条重新标注，不能凭模型自动拆。

`modelSchemaVersion`、`featureSchemaVersion`、`secretKeyId` 必须随模型保存。任一特征算法不兼容升级都新建模型，不静默混用旧统计。

### 冷启动和置信

- 零样本或只有一个可选平台：不输出模型自动分类，结果为“未知平台/待标注”。
- 某类别少于 2 条人工确认样本时只作为候选，不自动通过阈值。
- 明确正文品牌签名属于可解释的前置证据，但必须链接到规范平台目录；冲突时进入待标注。
- 模型输出最高后验和第一/第二名 margin。三档策略同时检查最低后验、最低 margin、最少训练样本，初始建议为：偏保守 `0.95/0.50/3`，平衡 `0.85/0.30/2`，偏自动 `0.70/0.15/2`。
- 朴素贝叶斯概率可能过度自信，这些数值是 MVP 初值而非统计校准声明。真机标注集达到足够规模后，只能通过版本化评测调整；UI 不展示原始参数。

## 为什么不选择静态推理运行时

| 方案 | 不作为 MVP 主分类器的原因 |
| --- | --- |
| TFLite/ONNX 的预训练文本模型 | 能端侧推理不等于能按用户标签端侧增量训练；固定类别头也不能自然新增平台 |
| 端侧小语言模型 | APK/模型体积、内存、耗电和发热远超短信分类需要；纠正后的微调链路复杂 |
| 发送方到平台映射表 | 是规则记忆，不满足用户标注后训练分类器的已定合同；共享号码会误分 |
| 纯关键词词典 | 可作高精度前置证据，但不能承接用户持续新增的平台语言模式 |

未来若替换为在线逻辑回归/FTRL，其实现必须在目标 Android 设备上提供 `update` 和 `unlearn`，不能只暴露 `predict`。

## 可替换接口

核心层只依赖以下语义，不依赖 Room、HTTP 或具体算法类型：

```kotlin
interface FeatureExtractor {
    fun extract(text: CharSequence): SparseFeatures
}

interface IncrementalPlatformClassifier {
    suspend fun predict(features: SparseFeatures): RankedCandidates
    suspend fun learn(sampleId: Long, labelId: Long, features: SparseFeatures)
    suspend fun correct(sampleId: Long, oldLabelId: Long, newLabelId: Long)
    suspend fun forget(sampleId: Long)
    suspend fun snapshotInfo(): ModelSnapshotInfo
}

interface AssistedLabelProvider {
    suspend fun suggest(approvedBatch: RedactedBatch): List<LabelSuggestion>
}
```

`correct/forget` 是替换实现的硬要求。分类器不接收短信 Provider、网络 client 或原始正文；云端 provider 也不允许调用 `learn`。

## Room 边界

建议实体职责：

- `MessageIndex`：系统 message id、sub id/槽位结果、平台结果、来源、置信档位、扫描版本；无正文。
- `PlatformLabel`：平台规范名称、合并关系和用户元数据。
- `TrainingSample` / `TrainingFeature`：message 引用、人工标签、HMAC bucket 与计数、特征版本。
- `ModelClassStat` / `ModelFeatureStat`：MNB 充分统计量。
- `ScanCheckpoint` / `SubscriptionSnapshot`：分批水位、订阅映射及版本。

训练更新必须是数据库事务。模型缓存只是 Room 状态的可丢弃镜像；进程被杀后可由 Room 恢复，不能只存在内存或不可审计二进制文件中。

## WorkManager 边界

- 历史扫描使用唯一工作名和可持久化 checkpoint；每批有明确条数/时间预算，批间让出调度权。
- `SMS_RECEIVED_ACTION` receiver 只 enqueue 唯一增量工作并立即返回，不在 receiver 中读取/训练。
- Worker 按 Provider `_id/date` 水位查询、幂等 upsert；权限撤销返回可解释状态，而不是无限 retry。
- 用户点暂停时写入状态并取消后续工作；恢复创建新请求读取同一 checkpoint。
- 仅在用户明确打开 LLM 辅助后，相关工作添加网络约束；本地扫描/分类不得有网络约束。

具体批量、温度和重试参数由后续处理预算票据决定，本 ADR 不重复决定。

## OpenAI-compatible LLM API 辅助标注边界

它是可选的**候选标签提供者**，不是主分类器，也不是端侧模型的训练服务：

- 默认关闭；用户配置 HTTPS base URL、模型名和 API key。MVP 只定义兼容的 chat/completions 风格 JSON 适配器，不承诺所有自称兼容的扩展字段。
- 每批请求前展示提供商主机名、条数和逐条脱敏预览，用户确认后才发送。禁止后台自动上传。
- 请求只含随机请求 id、脱敏文本和已有平台名候选；移除验证码、手机号、地址、URL 参数及可识别账号。原始短信 id、卡槽、SIM 信息不发送。
- 强制结构化本地校验：返回值只接受请求 id、候选平台名和可选解释；未知字段丢弃，候选不能直接写入最终标签。
- 用户逐条或整批确认后，由 App 调用本地 `learn`；拒绝项不训练。LLM 无权读数据库、调用 MCP、控制手机或改模型。
- API key 不直接保存到 Room/SharedPreferences。Android Keystore 保存不可导出 AES/GCM 密钥，API key 以加密 blob 保存；日志、崩溃信息和备份中排除 key。
- 禁止明文 HTTP；证书校验失败不降级。关闭功能后允许用户清除 provider 配置和 key。

## 体积与性能预算

以下是 MVP 的验收上限，不是未经测量的性能承诺：

- 分类算法新增 APK 体积：`<= 250 KiB`（纯 Kotlin 代码，无 native runtime/模型文件）。
- 完整 universal debug APK 暂定 `<= 30 MiB`；release APK `<= 15 MiB`。超过时用 APK Analyzer 给出依赖归因。
- MNB 持久化状态：常规 200 个平台/10 万个非零 `(label,bucket)` 计数时 `<= 12 MiB`；达到 `20 MiB` 提示可重建/清理学习记录，不让稀疏表无限增长。
- 单条 500 字符以内短信的特征提取 + 推理：目标机 P95 `<= 20 ms`、训练更新 P95 `<= 30 ms`，均在后台线程；峰值临时内存 `<= 8 MiB`。
- 批处理期间 UI 主线程无磁盘/Provider/模型操作；滚动帧卡顿与温升由后续批处理票设定真机验收。
- LLM 辅助不计入本地分类延迟；断网或 API 失败不得影响本地浏览、扫描和模型训练。

若 MNB 未达到准确率要求，先用已确认样本离线评测特征与阈值。只有证据显示模型族不足时才通过接口替换，不能直接引入大模型掩盖数据问题。

## 验收证据

实现阶段必须证明：

1. 在飞行模式下，从零创建两个平台，各人工确认至少 2 条后，新短信预测随训练更新。
2. 将一个样本从平台 A 纠正到 B 后，A 的统计量精确减少、B 增加，重启 App 后结果一致。
3. 自动预测不增加任何训练计数；LLM 候选未被用户确认也不增加计数。
4. Room 事务中途失败不会留下半更新或负计数。
5. 清除/丢失 Keystore 密钥时拒绝混用旧特征，并走明确重建流程。
6. 记录目标机 P50/P95 延迟、Room 大小、APK Analyzer 结果，并满足上述预算。

## 官方与一手资料

- [Set up the Android 16 SDK](https://developer.android.com/about/versions/16/setup-sdk) — `compileSdk`/`targetSdk` 36。
- [Jetpack Compose BOM](https://developer.android.com/develop/ui/compose/bom) — 使用 stable BOM 对齐 Compose 依赖。
- [Save data in a local database using Room](https://developer.android.com/training/data-storage/room) — Room 基线及官方当前依赖示例。
- [Getting started with WorkManager](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started) — 持久后台任务和官方当前依赖示例。
- [Android Keystore system](https://developer.android.com/privacy-and-security/keystore) — 不可导出密钥和密钥材料保护。
- McCallum & Nigam, [A Comparison of Event Models for Naive Bayes Text Classification](https://www.cs.cmu.edu/~knigam/papers/multinomial-aaaiws98.pdf) — 多项式朴素贝叶斯文本分类的一手论文。

