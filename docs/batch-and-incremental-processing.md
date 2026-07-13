# 历史分批扫描与新短信增量处理协议

状态：已决定  
日期：2026-07-12  
目标设备：小米 17 Pro Max、HyperOS、Android 16

## 决策摘要

历史扫描采用 `(date, _id)` 降序 keyset、启动时冻结上界、每批 25 条；每个 `CoroutineWorker` 最多处理 4 批或运行 5 秒，先到即停。每批的本地索引和 checkpoint 在同一个 Room 事务中提交，Worker 片段之间默认延迟 15 秒。扫描不使用 `OFFSET`，不把所有短信读进内存，也不在 BroadcastReceiver 或主线程分类。

新短信广播只增加“待同步代次”并追加唯一 WorkManager 工作；增量任务从系统 Provider 读取落库记录并幂等 upsert。广播丢失、Provider 延迟落库、任务失败都由 App 恢复时补扫和 12 小时低频对账补偿。

## 任务与状态

Room 中的 `ScanRun` 至少保存：

- `runId`、`generation`、`mode`（history/incremental/reconcile）
- `status`：`IDLE/RUNNING/PAUSED/WAITING_BATTERY/WAITING_THERMAL/WAITING_PERMISSION/FAILED/COMPLETED`
- 历史快照 `upperDate/upperId`、下一页 `cursorDate/cursorId`
- `processedCount/estimatedTotal`、最近成功时间、错误类型与尝试次数
- 扫描开始时的订阅快照版本、分类/特征版本
- 增量 `requestedGeneration/completedGeneration` 和最近已观察的 `_id/date`

`generation` 是取消/暂停栅栏。Worker 开始、每批开始及提交事务前都检查它；旧 generation 即使取消尚未生效，也不能提交索引或推进 checkpoint。

进度总数是启动时快照的估算值，短信增删后可变化；界面显示“已处理 N 条”和近似百分比，不承诺精确分母。

## 历史快照与 keyset

### 建立快照

权限引导完成且用户点“开始扫描”后：

1. 读取当前最大排序键 `(date, _id)` 作为 `upperDate/upperId`。
2. 记录订阅映射快照和模型/特征版本。
3. 第一页以冻结上界为包含边界；之后严格小于最后成功行的 `(date, _id)`。

排序固定为 `date DESC, _id DESC`。下一页条件为：

```sql
date < :cursorDate OR (date = :cursorDate AND _id < :cursorId)
```

第一批把 `cursor` 视为快照上界并允许命中上界行。新到短信位于冻结上界之外，交给增量任务，不改变历史扫描的集合。重复读取由 `_id` 唯一键 upsert 消除；扫描中被删除的行自然跳过。

### Provider 查询限制

使用 `ContentResolver.query(..., queryArgs, CancellationSignal)`，在 Bundle 中传：

- `QUERY_ARG_SORT_COLUMNS = ["date", "_id"]`
- `QUERY_ARG_SORT_DIRECTION = QUERY_SORT_DIRECTION_DESCENDING`
- `QUERY_ARG_LIMIT = 25`
- keyset selection 与参数

Provider 可能不声明已处理全部 structured query args。实现必须检查 `Cursor` 返回的 honored args；若 `LIMIT` 未被支持，仍只消费前 25 行后立即关闭 Cursor，不得把 `LIMIT 25` 拼进 `sortOrder` 形成厂商/SQLite 私有合同。目标小米真机须验证 Provider 实际行为。

## 历史 Worker 切片

- **页大小固定 25 条**。MVP 不做运行时自适应批量，以减少不可复现的发热和恢复行为。
- 一个 Worker 最多 4 页（100 条）或 5 秒；每页后检查停止、权限、电量、温度、generation 和用户暂停状态。
- 每页内可逐条分类，但 `MessageIndex` upsert 与新 checkpoint 必须在同一个 Room 事务中；事务成功后才更新进度。
- 一个 Worker 达到切片上限且仍有数据时，向同一历史唯一链追加下一片，`setInitialDelay(15 seconds)`，主动留出冷却与系统调度时间。
- Cursor、正文引用和临时特征在页结束前释放；正文不跨 Worker、不写 checkpoint。
- Worker 停止时调用协程取消并关闭 Cursor；不得用 `GlobalScope` 继续处理。

控制目标不是最快完成，而是在冷机条件下约 100 条/20 秒的有界节奏。系统可能延后 WorkManager，UI 必须写“等待系统调度”，不能显示虚假的预计完成时间。

## 唯一工作协议

| 工作 | 唯一名称 | 入队策略 | 约束 |
| --- | --- | --- | --- |
| 历史扫描 | `hedgehog.history.v1` | 首次/恢复以新 generation `REPLACE`；正常续片 `APPEND_OR_REPLACE` | battery-not-low、storage-not-low；无网络 |
| 新短信增量 | `hedgehog.incremental.v1` | 每次事件先递增 requested generation，再 `APPEND_OR_REPLACE` | 无网络；MVP 不因低电量丢失短任务 |
| 低频补偿 | `hedgehog.reconcile.v1` | unique periodic `KEEP`，12 小时周期 | battery-not-low、storage-not-low；无网络 |

选择 `APPEND_OR_REPLACE` 是为了让运行中的工作后面仍有一次机会处理新事件；单用 `KEEP` 存在“广播到达时旧 Worker 即将结束、这次入队被丢弃”的窗口。代次计数与幂等 upsert仍是正确性来源，工作链只是唤醒机制。

暂停时先在 Room 增加 generation 并写 `PAUSED`，再 `cancelUniqueWork`；取消是尽力而为，generation 栅栏保证旧 Worker 不提交。恢复创建新 generation，从最后成功 checkpoint 继续，不清零索引。暂停只影响历史扫描；新短信极短任务仍可运行，用户撤销权限则全部停止。

## 新短信和最终补齐

### 广播路径

`SMS_RECEIVED_ACTION` receiver 只做两件事：在很短的 Room 事务中增加 `requestedGeneration`，然后入队增量唯一工作并返回。不解析/保存正文，不训练，不信任广播中的卡槽 extra。

增量 Worker：

1. 读取开始时的 requested generation。
2. 以 Provider `_id` 高水位为主，并重扫最近 24 小时窗口，按 `_id/date` 幂等 upsert。窗口补偿迟写入、时间字段异常或水位边界；不能假定广播必达。
3. 每次最多处理 25 条、总运行最多 2 秒；有更多则向唯一链追加下一片。
4. 提交后把 completed generation 推进到本次目标。若 requested 更大，链上的下一工作继续处理。

### Provider 延迟与重试

广播后没有发现新行不等于成功：可能默认短信 App 尚未写入 Provider。增量工作使用指数退避，基准 10 秒（WorkManager 最小 backoff），最多 3 次，即大致 10/20/40 秒。仍无新行时保留 requested > completed 的待处理状态，等待 App 恢复或周期对账，不无限唤醒。

每次 App 进入前台且拥有权限时，都先做一次最近 24 小时/高水位补扫。12 小时周期任务再做分段一致性校验，包括新增遗漏和已删除系统短信的失效引用。周期任务不是实时保证，系统可推迟它。

## 幂等和并发

- `MessageIndex(sourceMessageId)` 建唯一索引；重复广播、重试、历史/增量重叠均是 upsert。
- 自动分类只可更新无人工标签的记录；人工标签和用户纠正永远不被扫描覆盖。
- 同一 `_id` 的系统记录已不存在时按同步合同删除本地引用；模型不自动 `forget`。
- 每条索引记录带 `classificationVersion`、`subscriptionSnapshotVersion` 和 `lastSeenAt`；版本升级不会在本票中触发全历史重算。
- 历史与增量可以被系统交错调度，但 Room 写入串行事务；同一 `_id` 冲突时人工结果优先，其次较新的明确版本。不要用进程内 mutex 作为唯一正确性保障。

## 错误、约束与退避

### 历史工作

- transient Provider/Room I/O：`BackoffPolicy.LINEAR`、30 秒基准，最多 3 次；超过后写 `FAILED`，用户可从 checkpoint 重试。
- `SecurityException`/权限撤销：写 `WAITING_PERMISSION` 并成功结束，不 retry；重新授权后新 generation 恢复。
- 存储不足：由 WorkManager storage-not-low 约束等待；运行中出现磁盘满则 `FAILED_STORAGE`，不循环重试。
- WorkManager 约束未满足是等待，不计作业务失败或重试次数。

### 增量工作

- 仅 Provider 尚未出现新行或瞬时 I/O 使用 `BackoffPolicy.EXPONENTIAL`、10 秒基准、最多 3 次。
- 权限拒绝、schema/编程错误不 retry；保留待处理代次供修复/授权后补偿。
- 没有网络约束；本地短信分类绝不依赖 LLM API。

所有重试都必须幂等。UI 展示用户可理解的原因，不显示异常堆栈或短信数据。

## 电量和温控

WorkManager 没有温度 constraint，因此 Worker 自己在每页前检查：

- `PowerManager.currentThermalStatus`（API 29）。`NONE/LIGHT` 可继续；达到 `MODERATE` 立即结束当前已提交页并写 `WAITING_THERMAL`；达到 `SEVERE` 或更高时增量任务也只保留待处理代次，不分类。
- `BatteryManager.BATTERY_PROPERTY_CAPACITY`。历史扫描仅在充电或电量 `>= 20%` 时工作；跌破 20% 写 `WAITING_BATTERY`。未充电时恢复门槛为 `>= 25%`，形成滞回。
- 通过 `ACTION_BATTERY_CHANGED` 的 `EXTRA_TEMPERATURE` 读取电池温度作为辅助信号。历史扫描在 `>= 40.0°C` 停止，降至 `<= 38.0°C` 才恢复。此值不是 CPU/机身温度，不替代系统 thermal status。

温度/电量等待时不使用快速 retry。进程存活时 thermal listener 可刷新 UI；恢复由充电/前台检查或 15 分钟延迟的单次唤醒尝试完成。用户不能在设置中关闭安全停机门槛。

## 前台状态

权限引导结束后才出现扫描卡片，至少显示：

- 正在扫描：已处理数量、近似百分比、暂停按钮。
- 已暂停：继续按钮，明确不会丢失进度。
- 等待低温/电量/系统调度：具体原因，不承诺完成时间。
- 权限已撤销：重新授权入口；不显示旧正文。
- 失败：错误类别、从 checkpoint 重试；不要求“重新扫描全部”。
- 已完成：最后同步时间；新短信仍会增量处理。

## 可测预算

以下在目标小米设备、室温 `23–27°C`、屏幕关闭或停留进度页、LLM 关闭条件下测量：

- 单页 25 条：Provider 读取 + 特征/分类 + Room 提交 P95 `<= 750 ms`，任一页硬上限 2 秒。
- Worker 切片：P95 `<= 5 s`、最多 100 条；进程峰值额外内存 `<= 16 MiB`。
- 冷机连续处理 10,000 条：电池温升相对起点 `<= 5.0°C`，电量消耗 `<= 3` 个百分点；测试中不得达到 `THERMAL_STATUS_SEVERE`。达到停止门槛本身算安全机制生效，但预算测试需报告未完成量。
- 主线程禁止 Provider/Room/分类 I/O；StrictMode 相关违例为 0。扫描期间进度页 P95 frame time `<= 32 ms`、冻帧（`>700 ms`）为 0、ANR 为 0。
- 点击暂停后：当前页事务若尚未提交可以回滚；状态在 2 秒内变为 paused，之后 processed/checkpoint 不再增长。
- 强杀发生在任意页前、页中或事务后，重启恢复后最终索引集合一致、无重复，最多重做最后未提交的 25 条。
- 新短信正常路径：Provider 可见后 30 秒内出现在 App 索引；模拟广播丢失/强停时，下一次 App 前台恢复完成补扫。WorkManager 受系统调度时不作严格实时承诺。

若 10,000 条真实短信不足，可用测试 Provider/仪器化夹具验证算法与中断，再用真实短信子集验证温控；报告必须区分两者。

## 验收矩阵

1. 0/24/25/26/10,000 条边界下分页无遗漏、无重复。
2. 相同 `date` 的多条短信用 `_id` 次键稳定翻页。
3. 每个事务边界强杀并恢复；验证 checkpoint 只在成功事务后推进。
4. 连续发送重复广播、运行中再来广播、Provider 延迟落库；最终只保留一条索引且 completed generation 追上 requested。
5. 扫描中新增、删除短信；冻结历史集合与增量/对账共同收敛。
6. 暂停、恢复、权限撤销/恢复、低电量、充电、40°C 温控和系统 thermal 状态切换。
7. 人工标签存在时重扫/重试，确保不覆盖。
8. 使用 Macrobenchmark/Perfetto、StrictMode、WorkManager test driver 和 Room 故障注入保留测量证据。

## 官方资料

- [WorkManager: Managing work](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/manage-work) — unique work、`KEEP/REPLACE/APPEND_OR_REPLACE` 语义。
- [WorkManager: Define work requests](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work) — constraints、取消、重试与 backoff。
- [ContentResolver](https://developer.android.com/reference/android/content/ContentResolver#QUERY_ARG_LIMIT) — structured query args、`QUERY_ARG_LIMIT`、排序参数与 honored args。
- [PowerManager](https://developer.android.com/reference/android/os/PowerManager) — API 29 thermal status 与 listener。
- [BatteryManager](https://developer.android.com/reference/android/os/BatteryManager#BATTERY_PROPERTY_CAPACITY) — 电量容量属性。
- [Optimize for Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby) — 后台工作可能被系统延迟，不能承诺精确执行时间。

