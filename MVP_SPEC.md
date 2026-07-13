# 刺猬选短信 Android MVP 规格

版本：1.0  
状态：可直接实现  
日期：2026-07-12  
包名：`com.makia.hedgehogsms`

## 1. 文档地位

本文是 MVP 的单一实现事实源。`issues/` 和 `docs/` 保留决策过程、依据与测试细节；与本文冲突时以本文为准。原型只证明交互语义，正式实现不得直接复用其 throwaway 代码。

已裁决的冲突：

1. 系统短信删除后，删除对应 `MessageIndex`、`TrainingSample` 和 `TrainingFeature`，但**不**从已聚合的 MNB 参数中执行 `model.forget`；已学知识保留，并在产品说明披露。
2. 本地模型自动预测永不回灌训练。训练来源仅有人工确认，或用户已逐批批准、且在 LLM 自动确认档位下通过阈值的结果。LLM 自动确认可以创建新标签。
3. 标签合并保留旧标签为别名，历史结果立即按主标签聚合，模型统计并入主标签；这是标签治理，不执行模型遗忘。
4. 卡槽是当前物理/逻辑位置，SIM 集合是一张卡的历史身份；两者不得互换。
5. Android 备份可包含模型参数，但恢复后若原 HMAC Keystore 密钥不可用或 `secretKeyId` 不匹配，旧模型不得启用，必须清除模型/特征并重新学习；历史平台记录可保留为不可打开或待重新关联记录。
6. 交互采用原型 A“引导式首页”；B、C 不实现。

## 2. 产品目标

帮助双卡 Android 用户从真实系统短信中找出：每个 SIM 集合曾在哪些平台接收过验证码、对应证据短信和最近接收时间，以便用户自行前往平台注销或管理账号。

App 只读系统短信，不代替用户登录、注销、回复、发送、删除或标记已读。

### 2.1 MVP 成功标准

- 用户完成可理解的授权后，可以立即浏览真实短信，后台分类不阻塞列表。
- 能按 SIM 集合、当前卡槽、未知卡槽查看短信；能从平台概览进入平台短信证据和实时详情。
- 双卡字段有效时正确归类；任何不确定性明确显示“未知卡槽”，不猜测。
- 首次历史扫描可暂停、恢复、强杀恢复，不卡 UI、不造成明显发热。
- 平台分类完全可离线运行，并在用户纠正后端侧增量学习。
- 默认无短信数据外发；可选 LLM 只处理用户逐批批准的脱敏样本。

### 2.2 首个验收环境

- 小米 17 Pro Max
- HyperOS
- Android 16 / API 36
- 双卡真实短信样本
- 侧载 APK

其他 Android 10+ 设备尽力兼容。发布 APK 前必须在目标机验证 hard-restricted SMS 权限的实际安装/授权路径；未通过不得宣称普通点击 APK 即可使用。

## 3. 范围

### 3.1 必须实现

- 权限解释、分步系统授权、拒绝/撤销/恢复。
- 真实 SMS 列表和详情，只读、实时从系统 Provider 读取正文。
- SIM 集合、当前卡槽、历史卡槽快照与未知卡槽。
- 首次历史分批扫描、进度、暂停、继续、错误恢复。
- 新短信事件触发、Provider 落库后增量同步、前台/周期补偿。
- 验证码保守检测、平台分类、三档置信、待标注和人工纠正。
- 平台概览、平台证据短信列表、最近接收时间、使用过的 SIM/卡槽。
- 平台标签重命名、合并、别名和必要的人工拆分。
- 纯 Kotlin 端侧增量分类器。
- 发送者弱特征和正文不明确时的回退 SOP。
- 可选 OpenAI-compatible LLM 辅助标注与受限三工具 harness。
- 本地数据清除、防截屏、日志/备份/Keystore 边界。

### 3.2 明确不在范围

- MMS、RCS、data SMS、iOS、桌面端、Web 端。
- 修改系统信箱、发送/回复短信、标记已读、成为默认短信应用。
- 自动登录平台、自动注销账号、注销 API 或注销结果跟踪。
- 强制读取/填写手机号；无号码也必须可用。
- 云端训练、自动上传、账号系统、跨设备同步、远程备份。
- 通用号码反查数据库、后台 sender 查询或把通道主体当平台。
- Google Play/国内商店上架与审核。
- 第三方分析/崩溃 SDK、广告、推送增强。
- 端侧 LLM、TFLite/ONNX 静态模型冒充用户增量学习。

## 4. 技术基线

| 项 | 规格 |
| --- | --- |
| 语言 | Kotlin；Java/Kotlin 17 toolchain |
| UI | 单 Activity、Jetpack Compose、Material 3、Navigation Compose、单向数据流 |
| SDK | `minSdk 29`、`compileSdk 36`、`targetSdk 36` |
| 数据 | Room 2.8.4 + KSP；无正文数据库 |
| 后台 | WorkManager 2.11.2、`CoroutineWorker`、协程/Flow |
| 依赖注入 | 手工 application container，接口可替换 |
| Compose | 实现时锁定 stable Compose BOM，禁止动态/alpha/beta 版本 |
| 模型 | HMAC 字符 n-gram + 纯 Kotlin 在线多项式朴素贝叶斯 |
| 网络 | 默认不用；LLM 功能开启时使用窄 HTTPS client |

Provider、Room、分类和网络都不得在主线程运行。

## 5. 系统能力与权限

### 5.1 权限

| 权限 | 目的 | 拒绝/撤销后的行为 |
| --- | --- | --- |
| `READ_SMS` | 读取真实 SMS 列表、详情和扫描 | 停止全部短信读取；隐藏列表/详情，保留本地数据并引导重授权 |
| `READ_PHONE_STATE` | 读取活动订阅并映射卡槽/SIM | 仍可浏览短信；卡槽归类降级为未知 |
| `RECEIVE_SMS` | 接收新 SMS 通知 | 历史浏览可用；新短信在前台恢复/周期补偿时补齐 |
| `INTERNET` | 可选 LLM API | 本地模式不产生网络请求；不开启 LLM 时不影响任何核心功能 |

不申请 `READ_PHONE_NUMBERS` 来强求号码。`READ_SMS`、`RECEIVE_SMS` 是 hard-restricted；运行时授权前必须确认安装器允许授予。

### 5.2 授权流程

1. 首屏解释“短信不搬家，只帮你归类”、只读、本地默认、可撤权。
2. 用户主动点击后依次请求 `READ_SMS`、`READ_PHONE_STATE`、`RECEIVE_SMS`。
3. 每一步显示用途和拒绝后的具体降级，不循环弹窗。
4. 引导完成前不得扫描历史短信。
5. `SecurityException` 等价于权限不可用，转为状态，不崩溃。
6. 重授权后从 checkpoint/增量水位继续，不强制全量重扫。

## 6. 信息架构：方案 A

### 6.1 首页

按顺序展示：

1. 产品目的：找出每个 SIM 集合曾接收验证码的平台，供用户自行注销。
2. 隐私承诺：正文位于系统信箱，App 只存索引和脱敏特征。
3. 扫描状态卡：数量、近似百分比、具体等待原因、暂停/继续/重试。
4. SIM 集合入口：默认名“卡槽 1”“卡槽 2”，显示当前所在卡槽、平台数；允许用户改名。
5. 平台摘要：平台、验证码数量、最近时间、涉及 SIM/卡槽、置信状态和“查看注销线索”。
6. 最近真实短信。

底部一级入口：短信、平台、待标注、标签。云端辅助标注是可选次级入口，不占据默认主线。

### 6.2 页面与行为

- **短信列表**：全部、当前卡槽 1、当前卡槽 2、未知卡槽、SIM 集合筛选；未分类短信立即显示“分析中/未分类”。
- **短信详情**：正文、发送者和时间实时读取；显示 SIM 集合、扫描时卡槽快照、当前卡槽、平台与证据来源。引用不存在时显示“系统短信已删除”，随后清理索引。
- **平台概览**：每个平台显示使用过的 SIM 集合/卡槽、验证码数、最近接收时间、确认状态。
- **平台详情**：证据短信列表、实时详情入口、“自行前往平台处理”的提示；不提供注销按钮。
- **待标注**：一次只突出一个最佳候选及解释；用户选择已有平台或新建平台并训练。
- **标签管理**：重命名、合并、别名、拆分入口。拆分需逐条人工重新标注。
- **设置**：置信档位、敏感页防截屏、LLM provider、备份说明、外部 sender claim、清除全部本地数据。

短信详情、人工标注、LLM 脱敏预览默认 `FLAG_SECURE`；普通概览/设置不限制。用户关闭前必须确认风险。

## 7. 真实信箱与双卡合同

### 7.1 唯一正文事实源

使用 `Telephony.Sms.CONTENT_URI` 读取 `_ID/DATE/TYPE/ADDRESS/BODY/SUBSCRIPTION_ID`。Room 不保存正文、验证码、原始 sender 或正文预览。列表/详情需要文本时每次从 Provider 读取。

本地 `MessageIndex.sourceMessageId` 唯一，所有历史、增量、重试和对账均幂等 upsert。

### 7.2 卡槽映射

1. 读取短信 `sub_id`；null、缺列或 `<0` => `UNKNOWN_NO_SUB_ID`。
2. 用扫描时 `SubscriptionSnapshot` 或 `SubscriptionManager.getSlotIndex` 映射。
3. 只接受设备有效逻辑槽范围内的索引；0/1 展示“卡槽 1/2”。
4. 无关联旧订阅 => `UNKNOWN_INACTIVE_SUBSCRIPTION`；非法值 => `UNKNOWN_INVALID_SLOT`。
5. 广播 extra 只触发工作，绝不作为非默认短信 App 的卡槽事实。

### 7.3 SIM 集合

- `SimCollection` 是一张具体 SIM/eSIM 的本地历史身份；`slotIndex` 是当前位置。
- 用系统允许的订阅证据、运营商和可用号码生成本机 HMAC 指纹；不要求号码。
- 证据充分时恢复已有集合；证据不足时让用户选择“这是旧卡”或“作为新卡”。
- 换卡不重写历史短信的集合和扫描时卡槽快照；旧卡插到另一槽时，集合恢复并显示当前槽。
- 默认集合名是首次出现时的“卡槽 1/2”，允许改名。

## 8. 分类合同

### 8.1 验证码检测

自动判定必须同时满足：

- 有动态数字/字母码；
- 有验证码、校验码、OTP、verification code 等用途语义。

订单号、快递码、金额或普通数字不算。模糊项进入待标注。

### 8.2 平台标签

- 每条验证码短信最多一个主平台。
- 正文明确品牌/签名优先；sender 不是平台事实。
- 无法确认 => `UNKNOWN/PENDING_LABEL`。
- 人工标签永远覆盖本地模型、LLM 和重扫结果。
- 新/纠正样本只影响当前短信和未来预测，不自动改写其他历史短信。

### 8.3 三档置信

模型同时检查最高后验、第一/第二名 margin、该类最少确认样本：

| 档位 | 后验 | margin | 最少样本 |
| --- | ---: | ---: | ---: |
| 偏保守 | 0.95 | 0.50 | 3 |
| 平衡（默认） | 0.85 | 0.30 | 2 |
| 偏自动 | 0.70 | 0.15 | 2 |

这是版本化 MVP 初值，需用目标机数据评测；UI 只显示档位，不显示原始参数。零样本、只有一个平台或类别不足门槛时不得自动分类。

## 9. 端侧增量学习

### 9.1 特征

1. Unicode NFC、字母统一、空白折叠。
2. 在特征前把验证码、连续数字、手机号、URL 参数、日期/金额换为占位符。
3. 字符 1–3 gram + 少量结构特征；不依赖中文分词。
4. 每安装随机 Keystore 密钥做 HMAC-SHA-256，映射到 16,384 bucket。
5. 单 bucket 词频截断 3；sender 特征每条最多计数 1。

### 9.2 模型

在线 MNB 为每个标签保存 `N_c`、`T_c` 和稀疏 `(labelId,bucketId)->count`；`alpha=1.0`，log 概率与 log-sum-exp 归一化。类别可动态新增。

训练事务：

- 人工确认：保存脱敏特征并增加标签统计。
- 人工纠正：从旧标签精确扣除样本统计，再加入新标签；不得负数。
- 本地自动预测：只写分类结果，不训练。
- 删除系统短信：删除样本记录，但不扣减已经聚合的模型统计。
- 清除全部本地数据：删除完整模型，不逐样本 forget。

模型、特征、密钥均带 schema/version/key id；不兼容时新建模型，不静默混用。

### 9.3 标签治理

- 重命名只改规范名称。
- 合并在事务中创建 `Alias(old -> canonical)`，历史查询通过 canonical 立即聚合，并把旧类充分统计量加到主类；旧名保留为别名。
- 合并不调用 `forget`，未来训练统一使用主标签。
- 拆分不能自动完成；用户逐条重标，之后的纠正事务正常移动样本统计。已经因系统短信删除而只剩聚合参数的部分不能精确拆分，应在 UI 披露。

## 10. Sender 身份增强 SOP

### 10.1 证据边界

普通号、106 码/扩展码、虚拟号、字母 Sender ID 只能作为弱证据。监管查询通常只得到码号/通道主体；共享通道、租赁/复用和冒用风险使其不能证明最终平台。106 扩展码不是 Android data SMS port；MVP 不处理 data SMS。

### 10.2 本地特征与门槛

保存 HMAC 后的 `sender_full/type/prefix` 结构特征，不存原始地址。相同 sender：

- 少于 2 条已确认样本：不展示历史推断；
- 3 条且跨 2 天：显示“本机曾确认属于 X”的候选解释；
- 至少 5 条、跨 3 天、主标签 `>=95%` 且无冲突：标“本机强佐证”，但仍不能单独绕过完整模型阈值；
- 出现不同平台人工标签：立即标共享/冲突并降级。

### 10.3 回退顺序

1. 正文验证码和签名/品牌规则。
2. 正文 + sender HMAC 特征的本地模型。
3. 未过阈值或冲突 => 待标注。
4. 用户主动点“查询发送者”后，披露 sender、目标域名和隐私风险；只允许受信官方页面/未来 allowlist provider。
5. claim 必须分 `CARRIER/CHANNEL_OWNER/PLATFORM_CANDIDATE`；通道主体不能进入平台选择。
6. 外部 claim 不能单独自动确认或训练；用户确认后才形成训练样本。

MVP 不捆绑远程 `SenderLookupProvider`，默认实现只在用户同意后打开系统浏览器或复制 sender。原始 sender 不上传给 LLM。

## 11. 历史扫描

### 11.1 Keyset 与批次

- 启动时冻结最大 `(upperDate,upperId)`；排序 `date DESC,_id DESC`。
- 下一页条件：`date < cursorDate OR (date=cursorDate AND _id<cursorId)`。
- 使用 structured query args，`QUERY_ARG_LIMIT=25`；若 Provider 不 honor limit，只消费前 25 行并关闭 Cursor，禁止拼接私有 SQL `LIMIT`。
- 每页 25 条；每 Worker 最多 4 页/100 条或 5 秒；续片默认延迟 15 秒。
- 每页索引和 checkpoint 在同一 Room 事务提交；成功后才推进游标。

### 11.2 唯一工作

| 名称 | 用途 | 策略 | 约束 |
| --- | --- | --- | --- |
| `hedgehog.history.v1` | 历史扫描 | 首次/恢复 `REPLACE`；续片 `APPEND_OR_REPLACE` | battery-not-low、storage-not-low |
| `hedgehog.incremental.v1` | 新短信 | 递增 requested generation 后 `APPEND_OR_REPLACE` | 无网络 |
| `hedgehog.reconcile.v1` | 补偿/删除校验 | 12 小时 unique periodic `KEEP` | battery-not-low、storage-not-low |

`generation` 是取消栅栏。暂停先增加 generation 并写 `PAUSED`，再取消 unique work；恢复用新 generation 从 checkpoint 开始。旧 Worker 提交前必须检查 generation。

### 11.3 温控和错误

- 历史扫描只在充电或电量 `>=20%`；未充电恢复 `>=25%`。
- `PowerManager.currentThermalStatus >= MODERATE` 暂停历史；`>=SEVERE` 时增量也只保留待处理代次。
- 电池温度 `>=40°C` 暂停，`<=38°C` 恢复。
- 历史瞬时 I/O：linear 30 秒，最多 3 次；增量：exponential 10 秒，最多 3 次。
- 权限问题转 `WAITING_PERMISSION`，不无限 retry；存储满转明确失败。

## 12. 新短信与同步

Receiver 只在短事务增加 `requestedGeneration` 并 enqueue，不解析正文、不训练。

增量 Worker：

- 以 `_id` 高水位为主，并回看最近 24 小时；Provider 落库后的 `_id/sub_id` 才是事实。
- 每次最多 25 条/2 秒；幂等 upsert；推进 `completedGeneration`。
- 未发现行时按 10/20/40 秒重试；仍无行则保留 requested > completed。
- 每次 App 前台补扫 24 小时；每 12 小时低频对账新增遗漏和删除引用。

列表实时发现 Provider 引用失效时立即清理。系统短信删除后的动作固定为：

1. 删除 `MessageIndex`；
2. 删除对应 `TrainingSample/TrainingFeature`；
3. 不调用模型 `forget`，不改其他历史分类；
4. 平台计数按剩余索引刷新；模型保留的知识在说明页披露。

## 13. 可选 LLM 辅助标注

### 13.1 前提

- 默认关闭；用户配置 HTTPS OpenAI-compatible base URL、模型和 API key。
- API key 用 Android Keystore 中不可导出 AES/GCM 密钥加密，密文不进 Room 备份；恢复后重填。
- 每批发送前展示 provider 主机名、数量和逐条脱敏预览；用户明确批准后才能发送。
- 请求不得含验证码、手机号、原始 sender、短信 `_id`、SIM/卡槽、联系人或账号标识。

### 13.2 三工具 harness

只暴露：

```text
get_samples(batch_id)
submit_answer(batch_id, annotations)
train_local_model(batch_id)
```

- 仅不透明 batch/sample id；仅用户已批准批次。
- 无 Provider、短信库、联系人、文件、Shell、任意网络、API key 权限。
- 配额、超时、状态机校验和无敏感正文的本地审计。
- LLM 可返回已有标签、未知或建议新标签。

### 13.3 确认档位

- **人工确认**：每条 LLM 答案由用户确认后才能写标签/训练/创建新标签。
- **自动确认**：仅对本批已批准样本，答案达到用户选择的置信档位时可直接写标签并训练；允许创建新标签。低置信仍进入待标注。
- sender lookup claim 不降低 LLM 门槛，通道主体不得作为平台。

这是用户对受限批次的显式授权，不等于本地模型预测回灌。

## 14. 数据模型

字段名可调整，但语义不可删除。

### 14.1 Room 实体

`MessageIndex`

- `sourceMessageId`（唯一）、`sourceLinkState`
- `messageDate`、`messageType`（元数据，无正文）
- `rawSubscriptionId`、`simCollectionId?`
- `slotSnapshotIndex?`、`slotMappingStatus/source`、`subscriptionSnapshotVersion`
- `platformLabelId?`、`classificationSource`、`confidenceMode/score/status`
- `isHumanConfirmed`、`classificationVersion`、`featureVersion`
- `firstSeenAt/lastSeenAt`

`SimCollection`

- `id`、`displayName`、`localFingerprint`、`identityStatus`
- `currentSubscriptionId?`、`currentSlotIndex?`、`carrierMetadata?`
- `firstSeenAt/lastSeenAt`

`SubscriptionSnapshot`

- `version`、`capturedAt`、脱敏的 `(subId,slotIndex,simCollectionId,mappingStatus)`

`PlatformLabel` / `PlatformAlias`

- 规范标签、显示名、别名到主标签映射、创建来源、状态和时间。

`TrainingSample` / `TrainingFeature`

- 样本 id、可空 message 引用、canonical label、确认来源、feature/key/schema version；HMAC bucket/count。

`ModelClassStat` / `ModelFeatureStat`

- MNB 类别文档数、特征总数、稀疏计数、model schema/key id。

`ScanRun`

- run/generation/mode/status、upper/cursor key、processed/estimated、错误、版本、requested/completed generation。

`LlmProviderConfig` / `LlmBatch` / `LlmAnnotation` / `LlmAudit`

- provider 非秘密配置；批次批准状态、不透明样本、脱敏载荷、答案、确认档位、审计。API key 只存 Keystore 加密 blob。

`SenderClaim`

- sender HMAC、claim kind、display name、evidence URL/tier、observed/expiry；无原始 sender。

### 14.2 明确禁止落盘

- 短信正文、验证码、正文预览、原始 sender、原始手机号。
- 未脱敏 LLM 请求/响应、API key、Keystore 明文密钥。
- 联系人、任意 Provider dump。

## 15. 核心状态机

### 15.1 权限/可用性

```text
NOT_ONBOARDED -> EXPLAINED -> REQUESTING -> READY
                                  |-> DEGRADED_PHONE_STATE
                                  |-> DEGRADED_RECEIVE_SMS
                                  |-> BLOCKED_READ_SMS
READY/DEGRADED --撤权--> BLOCKED_* --重授权--> READY/DEGRADED
```

### 15.2 扫描

```text
IDLE -> RUNNING <-> PAUSED
          |-> WAITING_BATTERY
          |-> WAITING_THERMAL
          |-> WAITING_PERMISSION
          |-> FAILED -> RUNNING（从 checkpoint）
          `-> COMPLETED
```

### 15.3 分类

```text
UNSCANNED -> ANALYZING -> NON_OTP
                      |-> AUTO_LABELED
                      |-> PENDING_LABEL -> HUMAN_LABELED
AUTO_LABELED/HUMAN_LABELED -> HUMAN_CORRECTED
```

人工状态不可被自动状态覆盖。

### 15.4 LLM 批次

```text
DRAFT -> PREVIEWED -> USER_APPROVED -> SENT -> ANSWERED
  |         |              |                    |-> PENDING_CONFIRMATION
  `------ CANCELED --------'                    |-> AUTO_ACCEPTED -> TRAINED
                                               `-> USER_ACCEPTED -> TRAINED
```

未批准、已取消、过期或样本不属于批次时三工具全部拒绝。

## 16. 核心接口

```kotlin
interface SmsSource {
    suspend fun page(keyset: SmsKeyset?, limit: Int, fence: SmsFence?): List<SmsRecord>
    suspend fun byId(id: Long): SmsRecord?
}

interface SubscriptionResolver {
    suspend fun snapshot(): SubscriptionSnapshot
    fun resolve(subId: Long, snapshot: SubscriptionSnapshot): SlotResolution
}

interface SimCollectionResolver {
    suspend fun resolve(evidence: SimEvidence): SimCollectionResolution
    suspend fun confirmOldOrNew(decision: UserSimDecision)
}

interface FeatureExtractor { fun extract(text: CharSequence, sender: String?): SparseFeatures }

interface IncrementalPlatformClassifier {
    suspend fun predict(features: SparseFeatures): RankedCandidates
    suspend fun learn(sampleId: Long, labelId: Long, features: SparseFeatures)
    suspend fun correct(sampleId: Long, oldLabelId: Long, newLabelId: Long)
    suspend fun forget(sampleId: Long)
    suspend fun snapshotInfo(): ModelSnapshotInfo
}

interface ScanCoordinator {
    suspend fun startOrResume()
    suspend fun pause()
    suspend fun requestIncrementalSync()
}

interface AssistedLabelProvider {
    suspend fun suggest(batch: ApprovedRedactedBatch): List<LabelSuggestion>
}

interface SenderLookupProvider {
    suspend fun lookup(sender: NormalizedSender, consent: ConsentToken): List<SenderClaim>
}
```

MVP 默认 `SenderLookupProvider` 不联网，只打开受信官方页面/复制。`forget` 不用于系统短信删除或标签合并，只保留给显式学习记录治理/未来替换实现；“清除全部”直接销毁模型。

## 17. 隐私、安全与备份

### 17.1 本地生命周期

- 正文只在 Provider Cursor 到显示/脱敏/特征提取之间驻留内存，随后释放。
- 禁止写 Room、文件缓存、临时文件、日志、崩溃信息和模型参数。
- MVP 无第三方分析/崩溃 SDK；诊断包仅用户主动导出且必须脱敏。
- 默认本地模式抓包应无短信数据外发。

### 17.2 清除

“清除全部本地数据”删除：索引、SIM 集合、标签/别名、训练记录、模型、扫描状态、LLM 批次/审计、sender claim、provider 配置和所有 Keystore 条目。执行前二次确认，完成后回到首次引导。

### 17.3 Android 系统备份

允许备份：平台目录/别名、分类索引、SIM 集合名称、人工标注、模型参数、置信档位和普通设置。

排除：API key/加密 blob、Keystore 密钥、正文、验证码、原始 sender、未脱敏样本、临时 LLM 批次、诊断日志和查询 URL。

恢复流程：

1. 所有恢复的 `MessageIndex` 初始为 `DETACHED`。
2. 获得权限后用 `_id` 和保存的非正文元数据谨慎重新关联；不满足条件则只作为历史平台记录，不提供详情入口。
3. API key 始终重填。
4. 校验 `secretKeyId`。原特征密钥不可用时清除恢复的 HMAC 训练特征和模型统计，保留人工历史标签并提示“本地模型需重新学习”。
5. 不因恢复数据猜测当前 SIM 或卡槽。

## 18. 非功能预算

### 18.1 体积与模型

- 分类算法新增 APK `<=250 KiB`；无 native runtime/模型文件。
- universal debug APK `<=30 MiB`；release APK `<=15 MiB`。
- 常规 200 平台/10 万非零模型计数 `<=12 MiB`；达到 20 MiB 提示清理/重建。
- 500 字符内特征+推理 P95 `<=20 ms`，训练更新 P95 `<=30 ms`，临时内存 `<=8 MiB`。

### 18.2 扫描与 UI

- 25 条单页 P95 `<=750 ms`，硬上限 2 秒。
- Worker P95 `<=5 s`、最多 100 条，进程额外峰值内存 `<=16 MiB`。
- 10,000 条冷机扫描：温升 `<=5°C`、耗电 `<=3` 个百分点，不到 `THERMAL_STATUS_SEVERE`。
- 主线程 StrictMode Provider/Room/分类违例 0；进度页 frame P95 `<=32 ms`；`>700 ms` 冻帧 0；ANR 0。
- 暂停后 2 秒内状态稳定，checkpoint 不再增长。
- Provider 可见后正常新短信 30 秒内出现在索引；系统延迟时以前台补偿为最终保证。

## 19. 端到端验收矩阵

### A. 安装与权限

- A1 记录侧载安装器并成功授予 hard-restricted `READ_SMS/RECEIVE_SMS`；失败则发布闸门不通过。
- A2 分别拒绝三个权限，验证准确降级和无循环弹窗。
- A3 运行中撤销/恢复权限，读取立即停止，数据保留，从 checkpoint 恢复。

### B. 双卡/SIM

- B1 两卡普通/验证码/长 SMS 的 `sub_id -> slot` 100% 符合人工记录。
- B2 null/负 sub id、失效订阅、非法槽位全部未知，不误分。
- B3 换卡、旧卡插入另一槽、证据不足人工确认；历史快照不被当前槽重写。
- B4 无手机号仍可完成浏览和平台汇总。

### C. 真实信箱

- C1 详情正文与系统信箱一致，Room/文件/日志无正文与验证码。
- C2 系统删除短信后索引/训练样本删除、平台计数更新，但模型统计不 forget。
- C3 恢复备份无法关联的记录不可打开，不伪装成真实短信。

### D. 扫描/增量

- D1 0/24/25/26/10,000 条 keyset 无遗漏重复；相同 date 用 `_id` 稳定。
- D2 每个事务边界强杀，最多重做 25 条，最终集合一致。
- D3 暂停/恢复、低电、温控、存储不足和重试状态正确。
- D4 重复/运行中广播、Provider 延迟、App 强停/广播丢失最终由退避、前台 24h 和 12h 对账补齐。

### E. 分类/学习

- E1 明显验证码、普通数字误报、未知平台和共享 sender 冲突。
- E2 两个平台各人工确认至少 2 条后，飞行模式下未来预测发生变化。
- E3 人工纠正精确移动统计，重启一致；自动预测不增加训练计数。
- E4 三档阈值、正文签名优先、低置信待标注、不自动改旧短信。
- E5 sender 2/3/5 条门槛、跨平台冲突、106/普通号/字母 Sender ID。
- E6 标签合并后历史按主标签聚合、旧名为别名、模型不 forget；拆分逐条人工完成。

### F. LLM 与安全

- F1 默认模式抓包无短信外发。
- F2 每批预览/批准；脱敏请求无验证码、手机号、sender、短信 id、SIM/卡槽。
- F3 未批准/跨批次/过期工具调用拒绝；恶意样本文本不能获得文件、Shell、网络或 Provider 权限。
- F4 人工模式逐条确认；自动模式仅过阈值样本训练且可新建标签；低置信待标注。
- F5 API key 不出现在 Room、备份、日志和诊断包；证书失败不降级 HTTP。

### G. 隐私/备份/清除

- G1 敏感页 `FLAG_SECURE` 默认有效，关闭有风险确认。
- G2 备份包含/排除清单逐项检查；缺 HMAC key 时模型隔离重建。
- G3 清除全部后 Room、文件、WorkManager 状态、缓存、审计和 Keystore 无残留。
- G4 sender 查询未同意时零网络；同意后仅发送 sender 到显示的受信域名，无正文/收件信息。

### H. 性能

- H1 用 Macrobenchmark/Perfetto/StrictMode/WorkManager test driver/Room 故障注入保留证据。
- H2 满足第 18 节体积、延迟、帧、内存、温升和耗电预算。

## 20. 实现顺序建议

这不是新范围，只是最小纵向切片顺序：

1. 项目骨架、Room schema、权限状态和目标机 SMS/双卡 probe。
2. 真实短信列表/详情 + SIM/卡槽降级。
3. 历史 keyset 扫描、进度、暂停恢复和增量补偿。
4. 验证码规则、平台目录、待标注和人工标签。
5. 在线 MNB 增量学习、三档阈值和 sender 特征。
6. 方案 A 平台/SIM 主线、标签合并、删除同步。
7. 隐私、备份、清除和性能硬化。
8. 可选 LLM 三工具 harness；它不得阻塞本地 MVP 验收。

## 21. 完成定义

只有同时满足以下条件才称为 MVP 完成：

- 目标机真实安装权限闸门通过；
- 所有必须功能在无网络下可用；
- A–H 验收矩阵留有可复核证据；
- Room/文件/日志/备份审计确认无短信正文或验证码；
- 性能预算通过或有用户接受的明确偏差记录；
- 可选 LLM 即使未配置/不可用也不影响核心流程。

