# 刺猬选短信

刺猬选短信是一个面向双卡 Android 手机的本地只读短信整理 App。它从系统短信信箱读取验证码短信，按 SIM/卡槽和平台归类，帮助用户看清「哪张卡在哪些平台收过验证码」，再由用户自行去对应平台处理账号。

项目当前处于 Android MVP 阶段。实现规格以 [`MVP_SPEC.md`](MVP_SPEC.md) 为单一事实源，本文只做功能介绍和开发入口说明。

## 核心功能

### 1. 只读短信浏览

- 读取系统 SMS Provider 中的真实短信列表和详情。
- App 不成为默认短信应用，不发送、不回复、不删除、不标记已读。
- 短信详情需要正文时实时从系统信箱读取；本地数据库不保存短信正文。

### 2. 双卡与卡槽归类

- 识别短信对应的 `subscription_id`，映射到当前卡槽 1、卡槽 2 或未知卡槽。
- 支持按全部、卡槽 1、卡槽 2、未知卡槽筛选。
- 对权限不足、旧订阅、无效卡槽等情况明确降级为未知，不猜测。
- 规格层定义了 SIM 集合概念，用来区分「一张卡的历史身份」和「当前插在哪个槽」。

### 3. 历史扫描与增量同步

- 首次扫描历史短信时按小批次索引，避免长时间阻塞 UI。
- 扫描进度可暂停、继续、重启。
- 新短信通过接收广播触发同步；前台恢复时也会补偿同步。
- 索引写入 Room，使用短信 ID 做幂等更新。

### 4. 平台识别与证据查看

- 检测验证码语义，避免把订单号、金额、普通数字误判为验证码。
- 从正文签名、品牌信息和端侧分类结果生成平台归类。
- 平台页展示验证码数量、最近接收时间、涉及卡槽统计。
- 平台详情展示证据短信入口，方便用户回到真实短信上下文核对。

### 5. 待标注与本地学习

- 无法自动确认的平台进入待标注。
- 用户可以选择已有平台或创建新平台完成绑定。
- 人工确认会进入本地训练队列，端侧模型后续增量学习。
- 本地自动预测不会自动回灌训练；训练来源必须是用户确认或用户批准的辅助标注结果。

### 6. 标签治理

- 支持平台标签展示、搜索和管理入口。
- 规格层支持重命名、合并、别名和人工拆分语义。
- 合并标签会聚合历史结果，但不会对已聚合模型参数做遗忘操作。

### 7. 隐私与安全边界

- 默认不联网。
- Room 不保存短信正文、验证码、原始 sender 或正文预览。
- 文本特征在本地脱敏后用 Keystore/HMAC 映射到 bucket。
- 敏感页面可启用防截屏。
- 清除本地数据需要二次确认。
- 可选 LLM 辅助标注只允许处理用户批准的脱敏批次，且限制 HTTPS provider 和工具能力。

## 当前界面

底部主入口：

- `扫描`：查看扫描状态、最近短信和历史列表。
- `查看平台列表`：按平台查看验证码统计，可按卡槽筛选和搜索。
- `查看卡槽`：查看卡槽 1、卡槽 2、未知卡槽下的平台分布。
- `查看标签`：搜索和管理平台标签。

二级页面：

- 平台证据短信列表。
- 短信详情。
- 待标注绑定页面。
- 标签详情与创建标签页面。

## Android 权限

App 申请以下权限：

- `READ_SMS`：读取系统短信列表、详情和扫描数据。
- `READ_PHONE_STATE`：把短信订阅映射到卡槽。
- `RECEIVE_SMS`：收到新短信后触发增量同步。

其中 `READ_SMS` 和 `RECEIVE_SMS` 是 Android hard-restricted 权限。侧载 APK 在不同系统上的授权路径可能不同，发布前必须在目标设备验证。

## 技术栈

- Kotlin + Java 17 toolchain
- 单 Activity
- Jetpack Compose + Material 3
- Room + KSP
- WorkManager
- Coroutine / Flow
- minSdk 29，compileSdk/targetSdk 36
- 纯 Kotlin 端侧分类器：HMAC 字符 n-gram + 在线多项式朴素贝叶斯

## 开发入口

常用命令：

```sh
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
```

本机如存在 Android SDK 环境变量冲突，可显式指定：

```sh
GRADLE_USER_HOME=$PWD/.gradle-user ANDROID_USER_HOME=$PWD/.android-user \
ANDROID_HOME=/Users/makia/Library/Android/sdk ANDROID_SDK_ROOT=/Users/makia/Library/Android/sdk \
./gradlew testDebugUnitTest assembleDebug --no-daemon
```

## 重要文档

- [`MVP_SPEC.md`](MVP_SPEC.md)：Android MVP 实现规格，优先级最高。
- [`issues/0000-mvp-decision-map.md`](issues/0000-mvp-decision-map.md)：MVP 决策地图。
- [`issues/README.md`](issues/README.md)：本地 Markdown 票据追踪规则。
- [`docs/android16-dual-sim-capability.md`](docs/android16-dual-sim-capability.md)：Android 16 双卡能力调研。
- [`docs/batch-and-incremental-processing.md`](docs/batch-and-incremental-processing.md)：历史扫描与增量处理设计。
- [`docs/sender-identity-enrichment.md`](docs/sender-identity-enrichment.md)：发送者身份增强边界。

## 项目边界

明确不做：

- 自动注销账号或自动登录平台。
- 云端同步、账号系统、远程备份。
- 修改系统信箱。
- MMS、RCS、iOS、桌面端、Web 端。
- 第三方分析 SDK、广告、推送增强。

一句话：刺猬选短信只帮你把本机短信证据整理清楚，后续处理权仍留在用户手里。
