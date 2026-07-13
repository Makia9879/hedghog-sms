---
title: 验证 Android 16 双卡短信与卡槽字段的真实能力
label: wayfinder:research
status: closed
assignee: /root/android16_capability
parent: 0000-mvp-decision-map.md
blocked_by: []
---

## 问题

在 Android 16，尤其小米 17 Pro Max + HyperOS 上，侧载只读 App 能通过哪些官方 API 和短信库字段读取历史/新增短信，并把 `subscription_id` 稳定映射到物理卡槽？权限拒绝、号码不可读、换卡、旧订阅、彩信或字段缺失时应采用什么可验证的降级规则？

## 产物

链接一份基于 Android 官方资料与目标真机能力探测方案的 Markdown 调研记录。

## 解决记录

调研资产：[`Android 16 双卡短信能力与真机验证方案`](../docs/android16-dual-sim-capability.md)。

结论：标准 Android API 足以实现只读历史 SMS、接收新 SMS 通知及有效 `subscriptionId -> logicalSlotIndex` 映射，但不能保证每条短信都有可解析卡槽。MVP 仅在短信 `sub_id` 有效且订阅映射可信时显示“卡槽 1/2”；字段缺失、失效/旧订阅、非法槽位或缺少电话权限一律显示“未知卡槽”，绝不依据当前插卡或发送方猜测。新短信广播只作为增量任务触发，卡槽以系统短信库落库后的 `sub_id` 为准。

`READ_SMS`、`RECEIVE_SMS` 是 hard-restricted 权限，侧载安装器是否允许授予是目标机上的首要分发闸门；Android 官方文档无法替代小米 17 Pro Max + HyperOS 真机取证。资产定义了权限降级、换卡规则、脱敏探测矩阵和通过标准。MVP 只纳入 SMS；MMS 虽有官方 `SUBSCRIPTION_ID`，但正文解析与分类明确留待后续。
