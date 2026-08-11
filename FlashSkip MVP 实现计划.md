# FlashSkip MVP 实现计划

## 1. 实现概要

- 从零建立单模块 Android 项目，`applicationId = com.zhenghuaizhi.flashskip`。
- 技术栈：Kotlin、Jetpack Compose、Material 3、Coroutines/StateFlow、Preferences DataStore。
- `minSdk 26`、`targetSdk 36`、`compileSdk 36.1`；构建时固定 AGP 9.2 稳定补丁、Gradle 9.4.1、JDK 17，不使用动态版本。[官方兼容说明](https://developer.android.com/build/releases/agp-9-2-0-release-notes)
- 只保留 `app` 模块，通过包边界分离 UI、自动化引擎、规则、数据和 Android 平台适配，不引入多模块或依赖注入框架。
- 首次安装三个应用开关全部关闭。
- 实施顺序为：建立本机采集领域与存储 → 复用服务完成采集闭环 → 发布 Ready 本地规则 → 三款应用真机验收。
- 开发阶段使用中性工具向自适应占位图标；Release 验收前必须补齐最终 Adaptive Icon 与 Android 13 单色图层。

## 2. 页面结构

唯一入口为 `MainActivity`，无导航、菜单、详情页、弹窗或底部栏。页面使用一个支持安全区的 `LazyColumn`，结构固定为：

1. `FlashSkip` 页面标题。
2. 服务状态区。
3. “目标应用”标题和 QQ音乐、淘宝、京东三个应用行。
4. “最近执行”标题和最多 20 条记录。
5. 隐私页尾：`完全离线 · 不截图 · 规则字段仅存本机`；存在采集数据时提供全部清除入口。

布局遵循现有设计系统：

- 360–599 dp 使用 24 dp 水平边距，小于 360 dp 降为 16 dp。
- 600 dp 以上内容最大宽度 560 dp，保持单列居中。
- 应用行最小 96 dp、执行记录最小 76 dp，字体放大时由内容撑高。
- 跟随系统深浅主题；不提供主题设置。
- 页面不使用卡片堆叠、阴影、渐变、统计指标或下拉刷新。

## 3. 组件拆分

| 组件 | 职责 |
|---|---|
| `FlashSkipScreen` | 组合整个单页、统一滚动、安全区和响应式宽度 |
| `ServiceStatusBlock` | 展示服务状态、启用数量、最近成功记录和条件式系统设置入口 |
| `SectionHeader` | 蓝色分节标记与标题 |
| `TargetAppRow` | 官方图标、应用名、独立自动关闭开关、采集控制和内联规则卡 |
| `RuleCard` | 展示 Candidate/Ready/Stale、样本/唯一性进度、版本和限长选择器摘要 |
| `ExecutionTimeline` | 按时间倒序展示最近 20 条记录 |
| `ExecutionItem` | 首行显示“应用名 · 结果”，次行显示方式、时间、规则 ID；失败时补充原因 |
| `EmptyExecutionState` | 无记录时显示固定空状态文案 |
| `InlineAction` | 仅在未开启或异常状态下跳转系统无障碍设置 |
| `PrivacyFooter` | 展示不可点击的离线和数据最小化说明 |

组件约束：

- 自动关闭 Switch 与“采集规则/采集完成”等按钮分别提供包含应用名或明确动作的 TalkBack 语义。
- 图标、时间线节点和蓝色分节条均为装饰语义。
- UI 组件只接收不可变状态和事件回调，不直接访问 DataStore、规则或无障碍服务。
- 单个页面/引擎文件不超过约 500 行；无障碍 Service 入口保持薄层，不承载规则匹配细节。

## 4. 状态设计

### 首页状态

`HomeUiState` 由服务连接、持久化开关和执行记录共同派生，作为页面唯一状态源：

- `serviceStatus`
- `enabledAppCount`
- `targetApps`
- `records`
- `latestSuccess`
- `currentTime`
- `captureState`、`installedVersions` 与可观察 `RuleCatalogSnapshot`

服务状态固定为：

| 状态 | 判定 | 页面表现 |
|---|---|---|
| `NotEnabled` | 系统未启用 FlashSkip 无障碍服务 | 未开启说明 +“前往系统设置” |
| `Confirming` | 从设置返回且系统显示已启用，等待服务连接 | “正在确认服务状态”，最长 2 秒 |
| `Running` | 系统已启用且 Service 已连接 | 正常状态、启用数量、最近成功 |
| `Abnormal` | 系统显示已启用，但 2 秒后仍未连接 | 异常说明 +“检查系统设置” |

应用行状态：

- `EnabledAndRunning`
- `EnabledButServiceUnavailable`
- `Disabled`
- `RuleUnavailable`：当前应用版本没有通过校验的内置规则或 Ready 本机规则时禁用，不能通过 UI 绕过。

采集会话状态固定为 `Configuring`、`WaitingForTarget`、`ObservingClick`、`Captured` 和 `Failed`；规则卡状态固定为 `Candidate`、`Ready` 和 `Stale`。任一活动采集会话全局暂停自动化。

自动化会话状态：

- `Idle`
- `Watching`：目标应用进入前台后的 10 秒窗口。
- `Querying`
- `Acting`
- `Terminal`：成功、失败或匹配后安全放弃；本次会话不再重试。

边界规则：

- 使用 `SystemClock.elapsedRealtime()` 管理会话、延迟和冷却，不使用墙上时间。
- `elapsed < 10_000 ms` 才允许动作；达到或超过 10 秒直接失效。
- 应用开关关闭、服务断开、页面变化或包名变化会取消待执行任务。
- 同一会话最多一次成功；出现歧义或已进入动作阶段后失败，也结束该会话。

## 5. 数据与架构边界

### 内部数据模型

- `TargetApp`：应用 ID、名称、包名、图标、规则列表。
- `AdRule`：规则 ID、包名、允许 Activity、候选选择器、启动延迟、冷却时间和可选目标应用版本约束。
- `NodeSelector`：可选目标资源 ID、文本精确/前缀、内容描述精确，以及最多 4 层的祖先资源 ID 约束；同一选择器条件为 AND，动作目标不能退化为不可点击父容器。
- `ActionRecord`：时间、应用 ID、规则 ID、执行方式、结果、可选原因。
- `ActionMethod`：`NODE_CLICK`、`CENTER_GESTURE`。
- `ActionOutcome`：`CLOSED`、`ABANDONED`、`FAILED`。
- `AbandonReason`：歧义、节点消失、页面变化、查询过期。
- `FailureReason`：手势拒绝、手势取消或动作未完成。
- `CaptureSession`、`CaptureSample`、`CapturedNode`、`CapturedSelector`：主动采集的状态与限长证据。
- `RuleCandidateAnalysis`：聚类、唯一性证据、规则卡状态和可发布规则。

不增加外部 API、规则文件协议或导入格式。

### 持久化

Preferences DataStore 保存：

- 三个独立布尔开关，默认均为 `false`。
- 一个版本化 JSON 字段保存最近执行记录，最多 20 条、最新在前。
- 独立版本化采集字段按应用/版本最多保存最近 10 个有效样本；损坏时安全降级为空。
- JSON 损坏时降级为空记录，不影响应用开关；下一次写入时重建。
- 写入在 DataStore 原子更新中完成，插入第 21 条时同步移除最旧记录。

主动采集只保存经 PackageManager 验证的 Activity（240 字符）、resourceId（180 字符）、text/contentDescription（各 80 字符）、应用版本、可见性布尔值和时序。不保存页面树、坐标、截图、剪贴板内容或 Android 节点对象。

### 依赖方向

- `UI → HomeViewModel → Repository/ServiceStatusMonitor`
- `AccessibilityService → AutomationEngine → RuleCatalog/Repository 接口`
- `data` 实现 DataStore 存储。
- `platform` 适配 `AccessibilityNodeInfo`、系统设置和手势。
- 自动化领域逻辑不得依赖 Compose、Activity、DataStore 或具体 Android 节点类型。
- 使用轻量 `Application` 容器共享 Repository、规则目录和服务连接状态，不引入 Hilt/Koin。

## 6. 交互与自动化流程

### 页面交互

- 点击独立 Switch 立即持久化对应开关；规则未 Ready 时 Switch 禁用。
- 服务未开启时仍可编辑开关，已启用行显示“服务开启后生效”。
- 点击服务 CTA 打开系统无障碍设置；返回后自动进入 `Confirming` 并刷新。
- 自动关闭过程中不显示 Toast、通知、悬浮层或跨应用反馈。
- 应用项提供采集、继续采集、单应用清除；页尾提供全部采集数据清除。不提供手工规则编辑、日志筛选、搜索或主服务开关。

采集流程为：请求开始后服务在 2 秒内暂停自动化并配置目标包点击监听；配置成功自动发送 Home Intent。目标应用须在 90 秒内进入，首个点击须在 15 秒内发生，捕获结果最多保留 10 分钟等待“采集完成”。取消、失败、超时、服务销毁和进程恢复都恢复默认服务配置并解除自动化暂停。

### AccessibilityService

常态配置不接收点击事件；采集时动态限定单个目标包并临时增加 `TYPE_VIEW_CLICKED`：

- `TYPE_WINDOW_STATE_CHANGED`
- `TYPE_WINDOW_CONTENT_CHANGED`
- `TYPE_VIEW_CLICKED`（仅活动采集会话）
- `canRetrieveWindowContent=true`
- `canPerformGestures=true`
- `FLAG_REPORT_VIEW_IDS`
- `FLAG_INCLUDE_NOT_IMPORTANT_VIEWS`
- `BIND_ACCESSIBILITY_SERVICE`

配置方式遵循 Android 官方无障碍服务要求。[官方说明](https://developer.android.com/guide/topics/ui/accessibility/service)

处理流程固定为：

1. 回调中只复制包名、类名、窗口 ID、事件类型和单调时间；不持有会被系统回收的 `AccessibilityEvent`。
2. 只接受目标包事件，并通过单线程协程调度器串行处理。
3. `WINDOW_STATE_CHANGED` 创建或更新前台会话；内容事件只触发查询，不延长 10 秒窗口。
4. 使用 120 ms 合并防抖；规则启动延迟到期后重新获取 `rootInActiveWindow`。
   本机 Ready 规则的启动延迟固定为 0；人工点击时序只保留为采集证据，不能把用户反应时间带入自动执行。
5. 每次遍历限制为最多 500 个节点、40 层、100 ms；超限安全退出。
6. 候选选择器按规则卡顺序执行：0 个匹配继续下一个，1 个进入验证，多于 1 个立即记录歧义并放弃。
7. 动作前再次获取最新根节点，并复核包名、Activity、窗口 ID、选择器唯一性和节点边界；不复用延迟前节点。
8. 首先执行 `ACTION_CLICK`；仅返回 `false` 时再次验证节点并向其可见中心发送约 60 ms 点击手势。
9. 手势只有收到完成回调才记为成功；拒绝或取消记为失败。
10. 普通无匹配、超时、禁用、冷却和非目标应用不写日志；匹配后因歧义、失效、换页或过期而停止时写 `ABANDONED`。
11. 开关变化在动作前再次检查；关闭应用时取消其当前会话和待执行任务。

节点点击返回成功后的复查若因广告关闭后进入复杂目标页而达到遍历上限，则结束复查并按 `NODE_CLICK / CLOSED` 记录，不再次点击；首次动作前查询达到遍历上限仍按“已放弃”处理。

常态通过活动 `TYPE_APPLICATION` 窗口分类维护前台隐私边界；采集配置则只订阅所选目标包。任何包白名单检查必须先于根节点或点击源读取，非目标包节点读取为零。

采集点击事件没有节点来源时，只允许在相同目标包、经验证 Activity 和活动窗口内进行有界恢复：优先按事件提供的 text/contentDescription 精确匹配；若按钮在点击回调到达前已经消失，可使用采集会话内存中最近 10 秒的同窗口唯一关闭候选快照。快照只包含限长节点语义链，不持有 Android 节点、不写入存储。歧义、换页、无根节点或遍历超限均失败，不等待或猜测下一次点击，也不保存完整节点树。

## 7. 规则前置与实施阶段

### 阶段 0：采集并冻结规则

QQ音乐、淘宝和京东通过应用内重复采集形成规则卡，必须包含：

- 当前安装版本、包名和允许 Activity。
- 至少 5 次开屏观察证据。
- 首选及备用选择器、选择器顺序。
- 唯一性结果、出现时机、人工点击时序、生效延迟和冷却时间；本机 Ready 规则固定立即开始匹配。
- 节点是否支持 `ACTION_CLICK`、手势降级是否有效。
- 已知无广告、节点缺失、WebView/自绘和页面快速切换情况。

任何应用如果只能依赖固定坐标、视觉识别或不稳定泛化文本，则暂停该应用接入；不得为其扩大引擎能力。三款规则均通过后，选择证据最稳定的一款作为纵切；稳定度相同则按 QQ音乐 → 淘宝 → 京东。

### 阶段 1：项目与静态首页

建立构建配置、主题 Token、目标图标资源和全部静态组件，使用伪状态验证正常、未开启、确认、异常、空日志和大字体布局。

### 阶段 2：持久化与服务状态

接入 DataStore、`HomeViewModel`、系统服务启用检测、运行连接监视和系统设置返回刷新，完成三个默认关闭开关及最近 20 条记录。

### 阶段 3：单应用纵切

实现会话追踪、串行调度、选择器匹配、最新页面验证、节点点击、中心手势和日志闭环；纵切应用通过安全测试后冻结引擎接口。

### 阶段 4：接入其余应用

通过同一采集状态机生成版本绑定的本机规则，不为单个应用新增特例分支或扩展选择器语法。

### 阶段 5：设备回归与 Release

完成三款应用实测、7 天试运行、Manifest/依赖/体积审计，并用最终 FlashSkip 启动器图标替换占位资产。

## 8. 测试计划

### 单元测试

- 选择器精确、前缀、AND 条件、候选顺序、大小写和歧义。
- 10 秒边界、规则延迟、冷却、会话切换和一次成功限制。
- 密集事件只产生串行查询，不重复动作。
- 节点消失、页面变化、窗口变化和查询过期均放弃。
- `ACTION_CLICK=false` 才允许中心手势；无唯一匹配绝不发送手势。
- 应用禁用、服务断开和非目标包不执行。
- 记录类型、最新优先、20 条裁剪、损坏数据恢复和默认开关。
- 规则目录的重复 ID、空选择器、非法包名和负延迟校验。
- 采集状态机的配置、Home 等待、首次点击锁定、取消、全部超时、服务重启和进程恢复。
- 选择器组合、泛化文本拒绝、2 次稳定聚类、1 次唯一性证据、版本隔离和 10 条裁剪。
- 采集期间查询、节点点击和中心手势调用次数均为零。

### UI/仪器测试

- 四种服务状态及其准确 CTA。
- 开关点击、进程/Activity 重建后的持久化。
- 日志空状态及成功、放弃、失败样式。
- 每个应用的采集按钮与自动关闭 Switch 是可区分的独立无障碍节点。
- 浅色、深色、200% 字体、横屏以及 360×800、390×844、412×915、600×960。
- 状态、结果和开关不依赖颜色表达；触控区域至少 48 dp。
- 系统设置 Intent 可解析，返回后自动刷新。

### 主力设备测试

- 每款应用至少 5 次、总计至少 20 次真实广告曝光。
- 至少 50 次无广告启动和正常页面操作。
- 覆盖快速返回、连续启动、页面切换、密集内容变化、开关中途关闭、服务关闭/重连、进程重启和目标应用升级。
- 单独验证至少一次节点点击失败后中心手势成功。
- 连续 7 天观察崩溃、ANR、电量和服务状态准确性。
- Release 构建执行单元测试、UI 测试、Lint、R8 和资源压缩，并审计 Manifest 与 APK。

## 9. MVP 验收标准

- 首页打开后 3 秒内能确认服务状态、启用应用数量和最近一次结果。
- 首装三个应用开关全部关闭；分别启用后即时生效并在重启后保留。
- 三款应用各有至少一条冻结、稳定、唯一的内置或 Ready 本机规则；不足 2 次或唯一性证据不足时保持 Candidate。
- 自动动作仅发生在目标应用有效 10 秒会话内；非目标、禁用、超时和冷却期间为零动作。
- 节点歧义、失效、页面切换或查询过期时为零点击，并产生符合策略的最小记录。
- 同一会话最多成功一次；无唯一节点时手势调用次数为零。
- 至少 20 次有效广告曝光的实际关闭成功率不低于 95%，且每款应用不少于 5 次。
- 至少 50 次无广告/正常操作中的误点击为 0。
- 最近执行最多 20 条，仅展示应用、结果、方式、时间、规则 ID 和允许的原因。
- TalkBack、200% 字体、深浅主题及规定视口无关键内容截断、重叠或重复焦点。
- Manifest 不包含 `INTERNET`、通知、悬浮窗、截图、`QUERY_ALL_PACKAGES` 或前台服务权限；无统计、广告、网络和崩溃分析 SDK。
- Release APK 经压缩后不超过 10 MB。
- 主力设备连续使用 7 天无崩溃、ANR、明显耗电或服务状态失真。
- 最终 Release 使用已冻结的工具向 Adaptive Icon；开发占位图标不能通过发布验收。
