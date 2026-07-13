---
title: 确定历史分批扫描与新短信增量处理策略
label: wayfinder:research
status: closed
assignee: /root/android16_capability
parent: 0000-mvp-decision-map.md
blocked_by:
  - 0003-native-android-technical-baseline.md
  - 0004-live-mailbox-index-sync.md
---

## 问题

在 Android 16 的后台执行约束下，首次历史扫描和新短信事件应采用怎样的批大小、调度、断点、重试、去重、暂停、温控/电量退避与前台可见状态，才能避免发热和卡顿并保证最终补齐？

## 解决记录

决策资产：[`历史分批扫描与新短信增量处理协议`](../docs/batch-and-incremental-processing.md)。

历史扫描锁定为 `(date, _id)` 降序 keyset 和启动时冻结上界，每页 25 条；每个 Worker 最多 4 页或 5 秒，每页索引与 checkpoint 同一事务提交，续片默认间隔 15 秒。历史、新短信和低频补偿分别使用 WorkManager unique work，采用 generation 栅栏、幂等 upsert、明确约束和有界退避；暂停/强杀最多重做最后未提交的 25 条。

新短信广播只增加待同步代次并入队，Provider 落库后读取 `sub_id`；延迟/丢失由指数重试、每次前台 24 小时回看和 12 小时对账补齐。历史工作在电量不足、存储不足、系统温度达到 moderate 或电池温度达到 40°C 时等待。资产给出了主线程、每页/Worker、电量温升、暂停时延和增量可见性的目标机验收预算。
