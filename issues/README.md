# 刺猬选短信决策追踪器

本目录是 Wayfinder 本地 Markdown 追踪器。

- `0000-mvp-decision-map.md` 是原 Android MVP 地图；新 PRD 使用独立子目录，并在各自目录内从 `0000` 重新编号。
- `status: open`、`assignee: null` 且 `blocked_by` 全部关闭的票据位于可执行前沿。
- 开始处理票据前，必须先填写 `assignee` 进行领取。
- 一次会话最多解决一张票据。
- 解决时把结论写入票据的“解决记录”，将 `status` 改为 `closed`，再向地图“已有决策”追加一行链接索引。
- 新票据先创建，随后再填写依赖关系；地图只索引已关闭票据，不重复保存详细结论。

## 当前前沿

- [`裁定平台概览的精简内容与卡槽筛选交互`](platform-filter-label-binding/0001-platform-overview-simplification.md)
- [`裁定待标注页的可搜索下拉与绑定语义`](platform-filter-label-binding/0002-pending-label-search-interaction.md)

二者均属于独立 PRD 地图 [`平台筛选与待标注快捷绑定决策地图`](platform-filter-label-binding/0000-decision-map.md)，可并行领取。原 Android MVP 地图仍保持关闭，既有安全与实现边界继续以 [`../MVP_SPEC.md`](../MVP_SPEC.md) 为事实源。
