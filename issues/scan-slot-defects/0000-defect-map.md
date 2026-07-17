---
status: closed
assignee: codex-main
labels: [wayfinder:map, defect-fix]
---

# 卡槽列表与扫描速度缺陷地图

## Destination

修复两个已确认缺陷：卡槽卡片显示有短信但进入后为空；历史短信扫描速度过慢。完成后至少通过仓库内单元测试或构建验证。

## Notes

- 技能流程：`aiops-agent-team`，主代理只负责范围、票据、集成审核和验收；实现由子代理完成。
- Debug environment：USB Android 设备 `ed0dd3ca`，型号 `2509FPN0BC`，Android 16。
- Deployment method：Android Studio/Android Gradle debug 构建，`./gradlew installDebug` 推送安装。
- Test tools：仓库内 Gradle/JUnit 可用时使用；设备级 E2E 和部署验证因缺少环境跳过。
- Test tasks：本地单元/构建验证；若后续提供设备环境，再补卡槽点击和扫描吞吐 smoke。

## Defect List

- [0001 卡槽卡片统计与点入列表语义不一致](0001-slot-card-empty-detail.md)
- [0002 历史扫描固定慢速节流导致吞吐过低](0002-history-scan-throughput.md)

## Decisions so far

- 本次不扩展标签治理、平台详情分页或设备端 E2E；只修复两个用户反馈缺陷的最小行为面。
- [0001 卡槽卡片统计与点入列表语义不一致](0001-slot-card-empty-detail.md) — 卡槽详情改为按卡槽索引展示短信条目，和卡片统计同口径。
- [0002 历史扫描固定慢速节流导致吞吐过低](0002-history-scan-throughput.md) — 扫描片策略改为 25 条/页、18 页/片，并按上一片处理速率动态选择续片延迟。

## Acceptance Evidence

- `git diff --check` 通过。
- `./gradlew test` 通过；普通沙箱因 `~/.gradle` wrapper lock 权限失败，提权后成功。
- `./gradlew installDebug` 首次因设备已有不同签名包失败；经用户批准卸载旧包后安装成功。
- 真机启动 `com.makia.hedgehogsms/.MainActivity` 成功，进程 PID `24543`，最近目标日志无 `AndroidRuntime` 崩溃。
- 通过 debug intent 重启历史扫描后，`scan_run` 为 `RUNNING`；约 6 秒内 `processed` 从 7050 增至 8000，`message_index` 同步增至 8000。
- 真机索引卡槽分布：卡槽 1 为 602，卡槽 2 为 4912，未知为 2486。

## Skipped Acceptance

- 部署验证：已补做 USB 真机 debug 安装和启动 smoke。
- E2E 回归：缺少自动化点击脚本，未做卡槽页面逐步点击录像式验证。
- 性能压测：缺少性能工具和目标指标，只做本地合同/构建验证。
