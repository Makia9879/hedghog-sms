---
title: 选择原生 Android 技术基线与端侧分类方案
label: wayfinder:research
status: closed
assignee: /root/android16_capability
parent: 0000-mvp-decision-map.md
blocked_by:
  - 0001-android16-dual-sim-capability.md
  - 0002-classification-contract.md
---

## 问题

基于目标设备能力和分类合同，MVP 应选择怎样的原生 Android 技术基线（语言、UI、最低/目标 SDK、本地数据库、后台调度），以及规则引擎和哪类端侧小模型/推理运行时能在完全离线、低发热前提下完成平台分类？

## 产物

链接一份带取舍、体积/性能预算和可替换边界的 Markdown 技术决策记录。

## 解决记录

技术决策资产：[`原生 Android 技术基线与端侧增量学习 ADR`](../docs/native-android-technical-baseline.md)。

MVP 选定 Kotlin、Jetpack Compose/Material 3、`minSdk 29`、`compileSdk/targetSdk 36`、Room、WorkManager 和协程/Flow。平台分类采用规则前置、HMAC 字符 n-gram 特征与纯 Kotlin 在线多项式朴素贝叶斯；用户确认/纠正通过增减本地充分统计量即时训练，支持动态新增平台及精确撤销，不使用只能静态推理的 TFLite/ONNX 模型冒充增量学习。

资产同时锁定了冷启动与三档置信初值、Room 事务/模型版本、WorkManager 职责、体积和真机性能预算，以及包含 `predict/learn/correct/forget` 的替换接口。可选 OpenAI-compatible API 仅提供逐批确认后的脱敏候选标签，LLM 不能直接训练或修改本地模型；API key 由 Android Keystore 密钥加密保存。
