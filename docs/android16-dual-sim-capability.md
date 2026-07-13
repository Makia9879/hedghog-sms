# Android 16 双卡短信能力与真机验证方案

更新日期：2026-07-12  
目标设备：小米 17 Pro Max、HyperOS、Android 16  
结论性质：官方 API 能力判断；厂商真机结果仍需按本文方案取证

## 结论

MVP 在标准 Android API 上可行，但“短信一定能归到卡槽”不能作为无条件保证：

- 历史短信可通过 `Telephony.Sms` 对应的系统短信 ContentProvider 读取；每条 SMS 的官方列 `Telephony.TextBasedSmsColumns.SUBSCRIPTION_ID`（实际列名 `sub_id`）表示该短信所属订阅。官方明确规定：无法确定时其值小于 0。
- 当前活动订阅可通过 `SubscriptionManager.getActiveSubscriptionInfoList()` 读取，并从 `SubscriptionInfo.getSubscriptionId()`、`getSimSlotIndex()` 建立 `subscriptionId -> logicalSlotIndex` 映射。Android 10/API 29 起也可用 `SubscriptionManager.getSlotIndex(subscriptionId)`；没有关联时返回 `INVALID_SIM_SLOT_INDEX`。
- 卡槽索引是从 0 开始的逻辑槽位；产品文案显示为“卡槽 1 / 卡槽 2”。它不是手机号，也不是 SIM 的永久身份。换卡、停卡、eSIM/远程 SIM、旧订阅或厂商数据缺失时，旧短信不能用“当前卡槽装着哪张卡”反推。
- 新短信可用 `Telephony.Sms.Intents.SMS_RECEIVED_ACTION` 获得通知（需 `RECEIVE_SMS`，不是默认短信应用也可以接收）。但该广播的官方合同只保证 PDU；只有默认短信应用收到的 `SMS_DELIVER_ACTION` 明确列出可选的 `subscription` 和 `slot`。因此本 App 收到广播后只能触发短任务，再从系统短信库读取已落库记录及其 `sub_id`，不能依赖广播中的厂商私有 extra。
- MMS 也定义了 `Telephony.BaseMmsColumns.SUBSCRIPTION_ID`，同样允许小于 0。产品目标是“短信/验证码”，MVP 应只支持 SMS；遇到 MMS 不解析正文、不纳入验证码分类，可在后续扩展时单独验证。
- `READ_SMS` 和 `RECEIVE_SMS` 都是 dangerous 且 hard-restricted 权限。除了运行时授权，安装器还必须把权限加入允许列表。侧载 APK 因安装渠道/HyperOS 策略不同，不能假定普通安装后权限弹窗一定可用；它是首个真机闸门。`READ_PHONE_STATE` 是读取活动订阅列表所需的 dangerous 权限。
- 手机号不是分类前提。官方订阅接口即便可见，号码字段也可能因权限或运营商/SIM 未提供而为空；MVP 以卡槽名为主，不申请 `READ_PHONE_NUMBERS` 来强求号码。

因此产品承诺应是：**能取得有效 `sub_id` 且能找到可信订阅映射时显示卡槽，否则明确显示“未知卡槽”，绝不猜测。**

## 建议的标准接口

| 用途 | 官方接口/字段 | 权限 | MVP 处理 |
| --- | --- | --- | --- |
| 历史 SMS | `Telephony.Sms.CONTENT_URI`；`_ID`、`DATE`、`TYPE`、`ADDRESS`、`BODY`、`SUBSCRIPTION_ID` | `READ_SMS` | 分页/分批查询；正文只在内存中分类或详情实时读取 |
| 当前订阅 | `SubscriptionManager.getActiveSubscriptionInfoList()` | `READ_PHONE_STATE` | 建立当前 `subscriptionId -> slotIndex` 快照 |
| 单个订阅映射 | `SubscriptionManager.getSlotIndex(subId)` | 以真机调用结果为准 | 仅接受 `0..activeModemCount-1`；非法值降级 |
| 新 SMS 通知 | `SMS_RECEIVED_ACTION` + `getMessagesFromIntent()` | `RECEIVE_SMS` | 仅唤醒/登记工作；落库后按 `_id`/时间水位增量查询 |
| 订阅变化 | `OnSubscriptionsChangedListener` | 与订阅可见性一致 | 刷新映射快照；不改写已有短信的已确认历史归类 |

不要使用裸字符串假定厂商列，也不要把广播 extra `slot` 当作非默认短信 App 的标准保证。

## 权限与安装闸门

引导顺序：

1. 先展示只读、仅本机处理、正文不复制的用途说明。
2. 请求 `READ_SMS`。拒绝时 App 保持可进入，但无法展示信箱或扫描。
3. 请求 `READ_PHONE_STATE`。拒绝时仍可浏览短信，但所有卡槽归类为“未知卡槽”。
4. 请求 `RECEIVE_SMS`。拒绝时历史浏览仍可用；新短信依靠下次启动/恢复时增量补扫。
5. 每次调用前检查实际授权结果；出现 `SecurityException` 时转为对应降级状态，不崩溃、不循环弹窗。

真机安装验收必须记录安装方式与安装器包名。若 `READ_SMS`/`RECEIVE_SMS` 无法授予，应先判定为“受限权限未被安装器 allowlist”，而不是误判 API 不支持。MVP 发布说明需给出已验证的安装路径；在这条闸门通过前，不能宣称普通点击 APK 即可使用。

## 卡槽归类算法

对每条系统短信记录：

1. 读取 `_id` 与 `SUBSCRIPTION_ID`；列不存在、为 null 或 `< 0`，结果为 `UNKNOWN_NO_SUB_ID`。
2. 若 `sub_id` 出现在本次扫描开始时取得的活动订阅快照中，取其 `simSlotIndex`。
3. 否则调用/查询系统能否将该订阅解析为有效槽位；无关联时为 `UNKNOWN_INACTIVE_SUBSCRIPTION`。
4. 仅当槽位为 `0` 或 `1`（并与设备报告的有效逻辑槽范围一致）时，分别显示“卡槽 1”“卡槽 2”；其他值为 `UNKNOWN_INVALID_SLOT`。
5. 用户手工纠正属于本地覆盖，优先于自动结果，并记录来源；不得用同一发送方的其他短信卡槽批量猜测。

建议索引保留 `messageId`、原始 `subscriptionId`、解析时的 `slotIndex`、`mappingSource`、`mappingStatus` 和订阅快照版本。它们不是正文副本，用于换卡后解释旧结果。若系统短信被删除，本地索引按同步规则删除。

## 换卡与旧订阅规则

- `subscriptionId` 是订阅数据库标识，不是物理卡槽号；不要持久化 `sub_id=1 -> 卡槽 1` 这类全局规则。
- 每轮扫描固定一份订阅快照，避免扫描途中换卡造成同一批结果前后不一致；收到订阅变化后结束/暂停当前批并开启新快照。
- 已经用当时有效快照解析的历史短信可以保留其“当时解析结果”和来源。重新全量校验时，若旧订阅已无法解析，不用当前新卡覆盖它，标为“未知卡槽（旧订阅）”。
- SIM 号码读取不到不影响映射；界面默认只显示“卡槽 1 / 卡槽 2”。

## 小米 Android 16 真机能力探测

需制作最小 probe APK（这是后续实现/验证任务，不属于本调研票），在目标机上留下脱敏 JSON/Markdown 结果。探测不得导出短信正文、地址或验证码。

### 环境记录

- 设备精确型号、Android API level、HyperOS 完整版本、Build fingerprint。
- 安装方式、安装器包名、三个权限的请求结果与系统设置页状态。
- `FEATURE_TELEPHONY_MESSAGING`、`FEATURE_TELEPHONY_SUBSCRIPTION`、活动 modem 数和最大活动订阅数。

### 样本矩阵

在卡槽 1、卡槽 2 各接收至少：普通短信 2 条、验证码短信 2 条、长短信/多段短信 1 条。再覆盖：

- 飞行模式/重启后补扫；App 被强停后收到短信，再启动补扫。
- 拒绝 `READ_PHONE_STATE`、拒绝 `RECEIVE_SMS`、撤销 `READ_SMS`。
- 关闭或临时移除一张 SIM 后查看旧短信；条件允许时换卡再查看。
- 可获得时加入一条 MMS，仅验证是否被明确排除。

### 只记录的脱敏字段

每个样本记录：短信 `_id` 的不可逆哈希、日期桶、`sub_id` 是否存在及其数值、`getSlotIndex` 结果、活动订阅 `(subscriptionId, simSlotIndex)`、期望物理槽位、实际归类状态。正文只记录类型标签（普通/验证码/长短信），不记录内容。

### 通过标准

- 两个卡槽的 SMS 均能读到，且已知样本的有效 `sub_id -> slot` 映射 100% 与人工记录一致。
- 无 `sub_id`、失效订阅、权限撤销均进入预定降级状态，无误分和崩溃。
- `SMS_RECEIVED_ACTION` 只负责触发；系统短信落库后增量查询能去重获得样本。广播缺失时，下次启动补扫可恢复。
- 普通 APK 安装路径能够真实授予 hard-restricted SMS 权限；否则 MVP 分发路径尚未通过，需另开决策，不得绕过。

## 尚不能仅靠文档确认的事项

- HyperOS 的侧载安装器是否会 allowlist `READ_SMS`/`RECEIVE_SMS`，以及是否有额外“受限设置”确认步骤。
- 小米短信 Provider 是否在目标版本对全部历史 SMS 稳定返回 `sub_id`。
- 换卡后旧订阅到旧槽位的系统映射保留多久。
- 厂商是否延迟写入系统短信库，以及广播与 Provider 可见之间的最大延迟。

这些都必须由上述 probe 取证；官方 API 的存在不能替代真机验收。

## 官方资料

- [Telephony.TextBasedSmsColumns](https://developer.android.com/reference/android/provider/Telephony.TextBasedSmsColumns) — `SUBSCRIPTION_ID` 的列名、类型和 `< 0` 语义。
- [SubscriptionManager](https://developer.android.com/reference/android/telephony/SubscriptionManager) — 活动订阅、权限、`getSlotIndex()`、无关联返回值。
- [SubscriptionInfo](https://developer.android.com/reference/android/telephony/SubscriptionInfo) — `subscriptionId` 与 `simSlotIndex`。
- [Telephony.Sms.Intents](https://developer.android.com/reference/android/provider/Telephony.Sms.Intents) — `SMS_RECEIVED_ACTION`、`SMS_DELIVER_ACTION` 与权限/extra 合同差异。
- [Manifest.permission](https://developer.android.com/reference/android/Manifest.permission#READ_SMS) — `READ_SMS`、`RECEIVE_SMS` 的 dangerous/hard-restricted 定义。
- [Telephony.BaseMmsColumns](https://developer.android.com/reference/android/provider/Telephony.BaseMmsColumns) — MMS `SUBSCRIPTION_ID` 及未知值语义。

