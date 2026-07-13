---
title: 组装并验收刺猬选短信 Android MVP 规格
label: wayfinder:task
status: closed
assignee: /root/android16_capability
parent: 0000-mvp-decision-map.md
blocked_by:
  - 0001-android16-dual-sim-capability.md
  - 0002-classification-contract.md
  - 0003-native-android-technical-baseline.md
  - 0004-live-mailbox-index-sync.md
  - 0005-permission-and-scan-experience.md
  - 0006-batch-and-incremental-processing.md
  - 0007-privacy-and-security-boundary.md
  - 0009-sender-identity-enrichment.md
---

## 问题

如何把所有已关闭票据的决策组装成一份无冲突、可直接实现的单一 MVP 规格，并用端到端验收场景覆盖权限拒绝/恢复、双卡与未知卡槽、历史分批扫描、新短信补处理、真实信箱删除同步、平台纠正、完全离线和大短信量性能？

## 产物

在项目根目录生成单一事实源 `MVP_SPEC.md`，并完成逐项一致性检查。

## 解决记录

已生成单一事实源：[`MVP_SPEC.md`](../MVP_SPEC.md)。

规格已合并全部关闭票据和方案 A 原型评审结论，覆盖产品范围、功能/非功能、Android 权限与真机闸门、真实 Provider/SIM/卡槽合同、分类和端侧学习、sender 回退、分批/增量状态机、LLM 三工具 harness、Room 数据模型、接口、备份/清除与 A–H 端到端验收矩阵。

冲突已显式裁决：系统短信删除会删索引/样本但不执行模型 `forget`；本地自动预测不训练，而逐批批准的 LLM 自动确认可以过阈值训练并新建标签；标签合并保留别名并合并模型统计、不遗忘；SIM 集合与当前卡槽分离；恢复备份缺少原 HMAC Keystore 密钥时保留历史标签但隔离并重建模型。
