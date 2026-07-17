---
status: closed
assignee: codex-main
labels: [wayfinder:task, defect]
blocked_by: []
---

# 卡槽卡片统计与点入列表语义不一致

## Problem

卡槽页面的卡片使用全量 `message_index` 统计短信数量，但点击卡槽后详情页只展示已识别为 OTP 且已归平台的平台汇总。若该卡槽有短信但还没有可展示平台，用户会看到卡片有 n 条短信、详情页为空。

## Acceptance

- 卡槽详情页的列表语义与卡片统计一致，至少能展示该卡槽下的短信索引对应的短信条目。
- 仍保留进入具体短信详情的能力。
- 不泄露超过现有页面允许展示的正文/发送者信息；正文仍从系统短信源实时读取。
- 增加或更新测试覆盖空平台但有卡槽短信的场景。

## Suggested Scope

- 优先在 `InboxViewModel` 增加按卡槽加载历史短信列表的状态和方法。
- `SlotDetailScreen` 改为渲染卡槽短信条目，不再把平台汇总当作卡槽详情的唯一内容。
- `MessageIndexDao` 可增加按 `PlatformSlotFilter` 分页查询索引的方法。

## Resolution

- `MessageIndexDao.pageBySlotFilter()` 以 `message_index` 的卡槽统计口径查询短信索引。
- `InboxViewModel.loadSlotDetail()` 从索引取短信 ID，再从系统短信源实时读取展示记录。
- `SlotDetailScreen` 改为展示卡槽短信条目，并支持从卡槽详情打开短信详情后返回原卡槽。
- 验证：`./gradlew test` 通过。
