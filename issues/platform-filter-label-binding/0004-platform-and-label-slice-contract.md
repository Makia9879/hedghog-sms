---
title: 组装平台筛选与标签快捷绑定的实施验收合同
label: wayfinder:task
status: open
assignee: null
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
