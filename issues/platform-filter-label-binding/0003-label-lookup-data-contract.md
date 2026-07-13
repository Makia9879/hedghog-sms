---
title: 锁定标签查找去重与人工绑定的数据合同
label: wayfinder:research
status: open
assignee: null
parent: 0000-decision-map.md
blocked_by:
  - 0002-pending-label-search-interaction.md
---

## 问题

本切片的标签候选应临时复用已有平台汇总/`knownPlatforms()`，还是必须先接入独立标签与别名事实源；名称的空白、大小写、Unicode 等价、精确匹配和重复创建如何处理；重复点击、绑定失败、候选短信已变化时，怎样保证只把预期短信绑定并训练一次？

## 产物

把代码事实、推荐合同和不纳入本切片的标签治理边界写入本票解决记录，供最终实施合同引用。
