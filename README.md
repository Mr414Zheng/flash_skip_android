<div align="center">

<img src="docs/assets/app-icon.png" alt="FlashSkip App 图标" width="128" height="128">

# FlashSkip

**在手机本地采集规则的 Android 开屏广告关闭器**

通过系统无障碍服务识别关闭节点，在匹配唯一且满足安全约束时自动点击。

[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com/about/versions/oreo)
[![Kotlin 2.3.21](https://img.shields.io/badge/Kotlin-2.3.21-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?logo=jetpackcompose&logoColor=white)](https://developer.android.com/compose)
![Offline](https://img.shields.io/badge/Network-Offline-2E7D32)

[功能亮点](#功能亮点) · [使用方法](#使用方法) · [本地开发](#本地开发) · [隐私与安全](#隐私与安全) · [已知限制](#已知限制)

</div>

> [!IMPORTANT]
> FlashSkip 目前面向开发者本人侧载使用。项目不提供远程规则、规则市场、截图识别、OCR 或固定坐标点击。

## 为什么是 FlashSkip？

传统开屏广告规则通常需要开发者预先登记包名、分析页面结构，并随目标应用变化发布新版本。FlashSkip 将适配过程放到手机本地：用户亲自点击一次广告关闭按钮，应用只复制生成规则所需的有限无障碍语义，随后由同一套安全引擎执行。

```mermaid
flowchart LR
    A["点击采集规则"] --> B["自动返回 Home"]
    B --> C["手动启动目标应用"]
    C --> D["点击一次关闭按钮"]
    D --> E["返回 FlashSkip 确认完成"]
    E --> F["生成本机规则"]
    F --> G["用户手动启用"]
```

## 功能亮点

| 能力 | 说明 |
| --- | --- |
| 通用规则采集 | 无需预先登记应用，成功采集后按实际包名动态生成应用项 |
| 本机规则目录 | 不同关闭选择器累计为多条规则，相同选择器合并 Activity 和证据 |
| 一次采集可用 | 具备可靠强选择器的有效样本可直接生成 Ready 规则 |
| 独立应用开关 | 新规则默认关闭，必须由用户明确启用，不会采集后自动生效 |
| 安全自动点击 | 动作前复核包名、Activity、活动窗口和选择器唯一性 |
| 有界启动会话 | 仅在目标应用进入前台后的 10 秒内尝试执行 |
| 可解释记录 | 最多保存最近 20 条结果、执行方式、阶段耗时和规则 ID |
| 完全离线 | 无网络权限，规则与执行记录只保存在应用私有存储中 |

## 使用方法

### 1. 开启无障碍服务

1. 安装并打开 FlashSkip。
2. 点击首页的“前往系统设置”。
3. 在无障碍设置中开启“FlashSkip 开屏关闭服务”。
4. 返回 FlashSkip，确认首页显示“服务运行正常”。

### 2. 采集关闭规则

1. 点击“采集规则”，FlashSkip 会暂停自动点击并返回 Home。
2. 手动启动需要适配的应用。
3. 等待开屏广告出现，只点击一次关闭或跳过按钮。
4. 返回 FlashSkip，点击“采集完成”。
5. 在动态生成的应用项中检查规则卡，然后手动开启自动关闭开关。

> [!TIP]
> 采集会绑定离开 Home 后首个验证成功的应用。开始采集后请直接启动目标应用，不要先打开其他应用。

### 3. 验证规则

重新冷启动目标应用并观察最近执行记录：

- `已关闭`：动作已被接受，且动作后的页面证据确认原广告区域已移除。
- `已点击，关闭未确认`：动作已被接受，但观察时间内证据不足或原关闭目标仍存在。
- `已放弃`：尚未提交动作，因页面变化、匹配歧义或安全限制而停止执行。
- `执行失败`：点击或手势接口未接受动作。

## 工作原理

FlashSkip 只注册一个 `AccessibilityService`。采集状态的优先级高于自动化状态，任何活动采集会话都会暂停全局自动点击。

```mermaid
flowchart TB
    UI["Compose 首页"] --> Coordinator["采集协调器"]
    Service["AccessibilityService"] --> Coordinator
    Coordinator --> Store["DataStore v2"]
    Store --> Analyzer["规则分析与选择器生成"]
    Analyzer --> Catalog["动态规则目录"]
    Catalog --> Engine["单写者自动化引擎"]
    Service --> Engine
    Engine --> Gateway["节点查询 / 点击网关"]
    Engine --> Records["最近 20 条执行记录"]
```

自动执行遵循以下约束：

1. 只有已启用且存在 Ready 规则的应用可以启动执行会话。
2. 包名、经验证 Activity、活动应用窗口必须同时一致。
3. 每次动作都重新查询节点，不跨挂起保存节点或坐标。
4. 唯一节点优先执行 `ACTION_CLICK`；仅在节点不支持直接点击时尝试节点中心手势。
5. 动作接受后进入独立观察流程；页面切换、动作接口返回成功和手势完成不直接等于广告关闭。
6. 补点须重新证明原目标唯一存在、页面边界稳定并留足响应时间；同一次前台会话最多确认成功一次，所有动作受 10 秒窗口约束。

## 隐私与安全

FlashSkip 的采集数据仅包含生成本机规则所需的有限字段：

- 目标应用包名与应用名称
- 经 `PackageManager` 验证的 Activity
- 限长的 `resourceId`、`text`、`contentDescription`
- 节点可见性、可点击性和相对点击时序

明确不会保存或申请：

- 截图、OCR 数据或完整页面节点树
- 固定坐标或长期持有的 Android 节点对象
- 剪贴板、外部存储或网络访问
- `QUERY_ALL_PACKAGES` 权限

纯数字只有在数字节点到实际点击控件的链路上存在明确关闭语义或 `skip` / `close` / `dismiss` 控件 ID 提示时，才可作为倒计时候选。普通数量数字不能凭稳定 ID 或用户确认生成规则；启动时会清理缺少关闭依据的旧数字样本及其相关差分证据，保留正常关闭规则。

Debug 日志仅记录状态、计数、耗时和规则/控件 ID 指纹，不输出页面文本、描述、节点内容或坐标；Release 构建关闭这些调试日志。

排查采集问题时，可在 Android Studio Logcat 使用 `tag:FlashSkipCapture`，或执行：

```bash
adb logcat -v threadtime FlashSkipCapture:D '*:S'
```

按 `session` 关联同一次采集，`seq` 是进程内输出顺序，`relativeMs` 是本次会话的相对时序。重复阶段先输出首条，再以 `repeatCount` 汇总额外次数；真实点击始终单独输出。`capture_summary` 标记采集、停止监听、保存、取消或服务断开的结局；`target_window_summary` 和 `capture_scan_summary` 给出事件与完整/未完整扫描总数，近期数值轨迹最多保留 48 条。`trailUnchanged=true` 表示沿用该会话之前已经输出的轨迹，`sample_saved` 记录实际保存的证据类型及资源 ID、文本、描述的数量。采集页面只显示操作提示，详细排查依据通过日志取得。

排查自动执行问题时，在 Android Studio Logcat 使用 `tag:FlashSkipAutomation`，或同时读取两条链路：

```bash
adb logcat -v threadtime FlashSkipAutomation:D FlashSkipCapture:D '*:S'
```

按 `engine` 与 `session` 区分服务重连前后的执行；`session=0` 表示尚未开始执行的前台信号。`FOREGROUND_SKIPPED` / `RULE_SKIPPED` 给出未启用、没有兼容规则、Activity 不匹配、启动延迟或冷却期等原因。`SESSION_START`、`QUERY`、`NODE_CLICK` / `GESTURE` 记录前台会话、匹配与动作接口结果；`ACTION_ACCEPTED` 只说明系统接受动作。`OBSERVATION_SCHEDULED`、`TARGET_OBSERVATION`、`RETRY_DECISION` 和 `VERIFY_CLICK` 分别记录观察调度、原目标证据、独立补点决策和补点接口结果。`FINISHED` 记录最终结果，`observation=CONFIRMED/TARGET_PRESENT/INSUFFICIENT` 区分关闭已确认、原目标仍在与证据不足。暂停、页面边界或超时退出记录在 `INVALIDATED`。`targetHash`、`ruleHash` 是包名和规则 ID 的 Kotlin `hashCode()` 无符号十六进制指纹，`selectorIndex` 从 0 开始，`actionAncestorDistance=0` 表示点击匹配节点自身。相同初始查询按 `repeatCount` 汇总，每次动作和观察单独输出。

采集到的“跳过”使用严格文案/倒计时匹配，不接受“跳过推荐”等普通前缀。平台会刷新根节点、候选及约束祖先，拒绝失效或已经隐藏的缓存节点。`source=platform` 日志通过 `targetHash`、`windowId`、`selectorHash` 与引擎匹配日志关联；`CANDIDATE_CHECK` 输出缓存匹配、刷新和当前匹配结果及资源 ID 指纹，每次扫描最多输出 8 条。`SCAN_RESULT` 保留异常或有候选时的结论；`durationMs` 记录整个扫描耗时，`phase`、`indexLookupMs`、`visitedNodes`、`maxDepth` 和 `limit=TIME/NODE_COUNT/DEPTH` 区分索引与遍历的瓶颈。不输出页面文案或节点位置，超限时的零候选不代表节点已消失。

节点点击与中心手势使用同一套动作后确认标准。动作前只复制原目标、稳定容器及局部父区域的有限数值摘要，调用结束即释放全部节点。观察优先完整读取原广告局部区域：单独的空索引、按钮隐藏、仅按钮被移除但广告内容未改变，均不能证明关闭；原容器或广告内容被移除还须有动作后的页面事件，并在相隔至少 200ms 的两次观察中取得一致证据。原窗口消失也须验证同应用的替换窗口、原广告区域已移除，以及后续窗口事件。Activity 或包边界变化不能单独作为确认。

首次观察在动作后至少 250ms；后续页面事件以 120ms 合并，400ms 定时查询兜底，整个观察时间窗为 2.4 秒并受原启动会话期限约束。局部读取上限为 64 节点、8 层和 60ms，父区域最多读取至原容器所在的 4 层边界，不遍历复杂首页的整个子树。局部证据不足时，1.2 秒后最多允许两次整页兜底，每次限制 500 节点、40 层和 100ms；整页读取必须完整证明原广告区域与原目标均已移除，超限或不可读仍是证据不足。`source=platform stage=ACTION_EVIDENCE_READ` 的 `phase`、`complete`、`visitedNodes`、`durationMs`、`limit` 和目标计数给出实际读取路径；`fallbackAllowed` 仅表示本次允许兜底，不表示一定执行了整页读取。

补点与确认独立：只有原目标重新刷新后唯一匹配、原窗口和语义一致、证据稳定至少 200ms，且距上次动作至少 750ms，才允许一次节点补点。观察不完整不触发补点；后续证据恢复时重新判断，不进入永久“只查询”状态。中心手势不补点。`GESTURE_CALLBACK` 记录完成或取消事实，但不会把回调升级为关闭确认。仍无法确认时保留动作方式与接受事实，记录 `FINISHED outcome=ABANDONED`，页面显示“已点击，关闭未确认”；只有确认关闭才更新成功记录与冷却期。历史 v2 记录兼容读取，不改写旧结论。

## 本地开发

### 环境要求

- Android Studio
- Android SDK 36.1
- JDK 21（运行 Gradle 与 Robolectric；Java 源码兼容级别为 17）
- Android 8.0（API 26）或更高版本的设备/模拟器

项目已包含 Gradle Wrapper，核心版本如下：

| 组件 | 版本 |
| --- | --- |
| Android Gradle Plugin | 9.2.1 |
| Gradle | 9.4.1 |
| Kotlin | 2.3.21 |
| Compose BOM | 2026.06.00 |
| minSdk / targetSdk | 26 / 36 |

### 构建与安装

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

也可以直接使用 Android Studio 选择已连接设备并运行 `app` 配置。

### 检查命令

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleRelease
```

本地测试位于 `app/src/test/`：JUnit 4 与协程虚拟时钟验证状态竞争、超时和执行安全；Robolectric 4.17 在 API 26、36 上验证 Android 存储、v2 编解码、服务配置、日志脱敏及手势回调适配。Robolectric 首次运行需要下载对应 Android 测试运行库。

每项修复应附带回归测试。真实目标应用的无障碍事件、厂商后台策略和实际手势效果仍需连接设备验收；本地测试通过不代表真机验收完成。

## 项目结构

```text
app/src/main/kotlin/com/zhenghuaizhi/flashskip/
├── application/   # 服务状态与规则采集协调
├── data/          # DataStore 与版本化本地数据
├── domain/        # 自动化引擎、领域模型和规则分析
├── platform/      # 无障碍、系统设置和包信息适配
└── ui/            # Compose 首页与主题
```

关键模块：

- `RuleCaptureCoordinator`：管理采集状态、超时、取消和样本提交。
- `RuleCandidateAnalyzer`：生成、排序、合并并校验本机规则。
- `CompositeRuleCatalog`：组合本机规则与可选内置规则。
- `AutomationEngine`：串行处理前台事件、查询、动作和执行记录。
- `FlashSkipAccessibilityService`：维护系统事件订阅和前台隐私边界。

## 已知限制

- WebView、自绘界面或不暴露可靠无障碍语义的广告可能无法适配。
- 规则不绑定目标应用版本；目标应用升级后需要重新验证规则是否仍然安全有效。
- Android 厂商对后台和无障碍服务的管理策略不同，服务持续性需要在实际设备上验证。
- FlashSkip 不会使用固定坐标或视觉识别兜底，因此安全失败可能多于激进点击工具。

## 反馈

发现问题时，请在 [GitHub Issues](https://github.com/Mr414Zheng/flash_skip_android/issues) 提交设备型号、Android 版本、复现步骤和脱敏后的错误日志。请勿上传完整页面节点树或包含个人信息的截图。
