# FlashSkip 能力清单与验证现状

业务基线为 `e354a99`，P1 首次远端验收对应 `85fcd27`，主干合并提交为 `2818232`，盘点日期为 2026-10-14。清单覆盖当前仓库中的业务职责，用于安排规格和测试，不表示发现了同等数量的缺陷。契约登记见 [规格索引](README.md)，执行顺序见 [分阶段计划](roadmap.md)。

## 仓库基线

- 单个 Android `app` 模块，84 个已跟踪生产 Kotlin 文件：根目录 4、application 5、data 4、domain 25、platform 40、ui 6。
- P1 基线为 6 个测试 Kotlin 文件（5 个类、1 个支撑文件）、75 例。本轮新增 18 个测试文件（16 个类、2 个支撑文件）；P2 验收时共 21 个类、201 例全部通过，0 失败/错误/跳过。原 75 例本轮全部通过，不能代替 P2 专项验收。
- 已配置 JUnit 4、Robolectric 和协程测试；P1 GitHub Actions 已通过远端 Linux 验收，并随 PR #1 合入 main。主干运行 `38362313424` 的 28 个检查器测试及现有 75 例均通过，报告上传和下载已核对。尚未配置必需检查或分支保护；没有已跟踪的 `app/src/androidTest/` 测试或覆盖率门槛，分项证据见 [持续检查](ci.md)。
- P0 时只有 `capture-save-retry` 功能规格包；本轮以 main `ad01dc7` 新增 [规则就绪](rule-readiness/spec.md) 与 [自动执行](automation-execution/spec.md) 规格及离线测试，三项政策已审定并完成生产修复，具体结果分别登记；PR #3 已合入 main（`c2a286b`），主干 CI `38376388018` 通过并下载核验 201 例及构建/Lint 报告，见 [主干 CI 记录](ci.md#p2-合并与主干-ci-验收)，无新增设备证据。

## 职责与状态来源

[AppContainer](../app/src/main/kotlin/com/zhenghuaizhi/flashskip/AppContainer.kt) 装配依赖。HomeViewModel 发起用户操作，RuleCaptureCoordinator 协调采集和规则管理；采集与偏好分别持久化在两个 DataStore。CompositeRuleCatalog 从样本异步派生目录，服务消费采集状态并连接平台事件与自动执行引擎。领域端口承接时钟、页面动作和存储，Android 节点与系统生命周期留在平台层。

推广 SDD 沿用这些边界。跨能力的共享语义在主责规格中定义，消费方引用；不为了文档结构重排源代码或拆分 Gradle 模块。

| 能力 / 优先级 | 主要入口 | 负责的行为与副作用 | 已有证据与缺口 |
| --- | --- | --- | --- |
| C01 保存与安全重试 / 已有基线 | [RuleCaptureCoordinator](../app/src/main/kotlin/com/zhenghuaizhi/flashskip/application/rulecapture/RuleCaptureCoordinator.kt)、HomeViewModel | 合法会话保存、先关闭再提交、失败重试、取消、旧请求隔离及保存时开启竞争 | SDD-001 自动化 75 例通过；T-12 未完成且停止，不作设备完成声明 |
| C02 规则就绪与目录 / 高 | [RuleCandidateAnalyzer](../app/src/main/kotlin/com/zhenghuaizhi/flashskip/domain/rulecapture/RuleCandidateAnalyzer.kt)、[RuleValidator](../app/src/main/kotlin/com/zhenghuaizhi/flashskip/domain/rules/RuleValidator.kt)、[CompositeRuleCatalog](../app/src/main/kotlin/com/zhenghuaizhi/flashskip/domain/rules/CompositeRuleCatalog.kt) | 安全样本转为规则、就绪判断、整组校验、目录发布和版本匹配；目录是派生状态 | [SDD-002](rule-readiness/spec.md) 已落盘，稳定结构政策已审定并修复；专项 32 例全部通过，见 [验证记录](rule-readiness/validation.md) |
| C03 自动执行与动作确认 / 高 | [AutomationEngine](../app/src/main/kotlin/com/zhenghuaizhi/flashskip/domain/automation/AutomationEngine.kt)、[PostActionObserver](../app/src/main/kotlin/com/zhenghuaizhi/flashskip/domain/automation/PostActionObserver.kt)、[AccessibilityPageGateway](../app/src/main/kotlin/com/zhenghuaizhi/flashskip/platform/accessibility/AccessibilityPageGateway.kt) | 前台会话、查询、点击/手势、观察确认、补点、冷却和执行记录；实际动作经 PageGateway | [SDD-003](automation-execution/spec.md) 已落盘，目录失效和最近动作事件政策已审定并修复；专项 94 例全部通过，见 [验证记录](automation-execution/validation.md) |
| C04 采集生命周期 / 高 | [RuleCaptureCoordinator](../app/src/main/kotlin/com/zhenghuaizhi/flashskip/application/rulecapture/RuleCaptureCoordinator.kt)、[FlashSkipAccessibilityService](../app/src/main/kotlin/com/zhenghuaizhi/flashskip/platform/accessibility/FlashSkipAccessibilityService.kt) | 配置、目标进入、阶段转换、离开/返回、过期、断连和新旧会话身份；写采集状态 | [SDD-004](capture-lifecycle/spec.md) 已落盘，生命周期测试组织 196 例全部通过，含 14 例真实服务／实际存储的 Robolectric 集成；结果见 [验证](capture-lifecycle/validation.md) |
| C05 采集证据与目标识别 / 高 | [AccessibilityCaptureAdapter](../app/src/main/kotlin/com/zhenghuaizhi/flashskip/platform/accessibility/AccessibilityCaptureAdapter.kt)、[AccessibilityCaptureProbe](../app/src/main/kotlin/com/zhenghuaizhi/flashskip/platform/accessibility/AccessibilityCaptureProbe.kt)、[CaptureRuleSafetyPolicy](../app/src/main/kotlin/com/zhenghuaizhi/flashskip/domain/rulecapture/CaptureRuleSafetyPolicy.kt) | 包/窗口身份、真实点击恢复、候选和语义差分、扫描预算及覆盖情况；向协调器提交证据 | [SDD-005](capture-evidence/spec.md) 已落盘，证据测试组织 82 例全部通过；结果见 [验证](capture-evidence/validation.md) |
| C06 持久化与兼容 / 高 | [PreferencesRuleCaptureStore](../app/src/main/kotlin/com/zhenghuaizhi/flashskip/data/preferences/PreferencesRuleCaptureStore.kt)、[PreferencesFlashSkipStore](../app/src/main/kotlin/com/zhenghuaizhi/flashskip/data/preferences/PreferencesFlashSkipStore.kt)、[RuleCaptureCodec](../app/src/main/kotlin/com/zhenghuaizhi/flashskip/data/preferences/RuleCaptureCodec.kt)、[ActionRecordCodec](../app/src/main/kotlin/com/zhenghuaizhi/flashskip/data/preferences/ActionRecordCodec.kt) | 文件提交、编码、安全过滤、初始化清理、读取降级及记录保留 | P4 最小修复已合入 main，本地、PR 及主干专项 68 例通过；[验证](persistence-compatibility/validation.md) 含条目隔离、写保护、可用性与服务恢复证据 |
| C07 规则管理 / 高 | [RuleCaptureCoordinator](../app/src/main/kotlin/com/zhenghuaizhi/flashskip/application/rulecapture/RuleCaptureCoordinator.kt)：setAppEnabled、clearTarget、clearAll | 显式开关、单目标/全部删除、无关数据隔离；写开关并清除样本与会话 | P4 已合入 main，本地、PR 及主干专项 47 例通过；[验证](rule-management/validation.md) 含不可用读取阻断、锁内重试校验与部分提交证据 |
| C08 服务与首页交互 / 中 | [ServiceStatusCoordinator](../app/src/main/kotlin/com/zhenghuaizhi/flashskip/application/ServiceStatusCoordinator.kt)、[HomeViewModel](../app/src/main/kotlin/com/zhenghuaizhi/flashskip/ui/home/HomeViewModel.kt)、[HomeScreen](../app/src/main/kotlin/com/zhenghuaizhi/flashskip/ui/home/HomeScreen.kt) | 系统授权与连接状态、页面生命周期、控件和反馈、导航失败；将用户操作传入协调器 | [SDD-008](service-and-home/spec.md) 最小修复后累计新增 67 例全部通过；原 13 个红例关闭，修复前报告保留；实际 Compose／设备未验证 |
| C09 诊断与数据边界 / 中 | [CaptureDiagnosticJournal](../app/src/main/kotlin/com/zhenghuaizhi/flashskip/platform/diagnostics/CaptureDiagnosticJournal.kt)、[CaptureTraceLog](../app/src/main/kotlin/com/zhenghuaizhi/flashskip/platform/accessibility/CaptureTraceLog.kt)、AppContainer、[Manifest](../app/src/main/AndroidManifest.xml) | 诊断开关、元数据筛选、队列与文件上限、写入失败、备份边界；诊断文件副作用 | [SDD-009](diagnostics/spec.md) 最小修复后累计新增 74 例全部通过；原 6 个红例关闭，修复前报告保留；Release 单测任务缺失 |

优先级基于错误点击、证据失真和数据丢失的影响；“中”不代表可省略。间接覆盖不能据此宣称整个能力通过，也不在缺少测量时声称全项目覆盖率。

## 交叉边界的归属

| 共享语义 | 主责与消费方 |
| --- | --- |
| 会话身份、可保存状态 | C04 负责整体状态机；C01 已审定的保存资格与旧请求契约保持有效，C04 引用而不重写 |
| 规则资格与目录新鲜度 | C02 定义资格和发布；C07 消费开启条件，C03 消费可执行规则；C01/AC-06 保留已有保存交错约束 |
| 动作证据与关闭结论 | C05 定义采集证据来源；C03 定义自动动作后的结果确认，不能混用两类证据 |
| 采集期间暂停执行 | C04 提供采集状态，C08/平台服务传递，C03 执行暂停；离线测试不能替代已停止的 T-12 设备验证 |
| 编码与业务政策 | C06 负责持久化边界；可保存资格、就绪和删除策略分别归所属能力，不由 Codec 自行定义 |
| 错误展示与业务结果 | 所属能力定义结果，C08 映射 UI 反馈；C09 诊断不改变业务结果 |

## 政策决定与后续待审定事项

P2 三项政策已按后续授权修复；其他条目是后续规格的决策输入，不要求现在调整实现：

| 事项 | 当前事实或问题 | 审定阶段 |
| --- | --- | --- |
| 规则门槛 | 保留 1 份样本、0 份采集前唯一性门槛；稳定结构政策已批准，纯文本只能候选，修复及证据见 SDD-002 | P2 / SDD-002 |
| 自动动作确认与补点 | 目录变化使受影响会话失效、支持事件晚于最近接受动作已批准并修复；其余参数沿用基线，见 SDD-003 | P2 / SDD-003 |
| 采集恢复与期限 | P3 已批准未过期待保存载荷在断连／恢复时保留与单调硬截止；已按续授权修复并通过本地契约验证 | P3 / SDD-004 |
| 数据兼容与回退 | P4 已批准保留 v2 缺省、弃用 v1、坏条目隔离与不可用原文防覆盖；最小修复完成，原空值回退失败保留为历史 | P4 / SDD-006 |
| 删除部分失败 | P4 已批准部分停用保留、失败即停止、旧删除重试范围变化失效；最小修复及本地验收通过 | P4 / SDD-007 |
| 诊断承诺 | P5 已确认仅进程内关联、禁止稳定内容散列、损坏索引保留与尽力记录；最小修复与本地自动化验收通过，Release 证据仍阻塞 | P5 / SDD-009 |

T-12 停止决定不是待审定事项；不得因上述清单重新启动京东排查或设备验证。

## P3 本地证据

基线 main `34dc102` 加五类政策最小生产修复和内部接缝。全量 41 类、479 例全部通过，保留原 201 个和修复前 453 个身份与预期。P3 相对 main 新增 20 类、278 例：207 JVM、57 Robolectric SDK 36 组件、14 Robolectric 真实服务／容器／实际 DataStore 集成。原 42 个红例转绿，前后报告分别保留于验证记录。

本地、PR 及主干 CI 契约验收通过，[PR #4](https://github.com/Mr414Zheng/flash_skip_android/pull/4) 已合入 main（`471ccd2`）。[CI 38491677713](https://github.com/Mr414Zheng/flash_skip_android/actions/runs/38491677713) 的 479 例、构建、Lint 及报告核验通过；没有设备或 Compose 证据。依赖、构建配置和远端设置未改，SDD-001/T-12 继续停止。

[主干 CI 38493360255](https://github.com/Mr414Zheng/flash_skip_android/actions/runs/38493360255) 的 479 例、28 个检查器、构建、Lint 与产物门禁通过，两组报告已下载并核验合并 SHA；详情见 [主干验收](ci.md#p3-合并与主干-ci-验收)。

## P5 首轮本地证据（历史）

main `0c9a53f` 上新增 8 个测试类并在既有真实服务类追加 8 例，共 107 例；全量 701 例中 19 失败，原 594 例全部通过。新增层级为 33 JVM、28 JVM 真实文件、38 Robolectric SDK 36 组件及 8 Robolectric 真实服务／AppContainer／实际 DataStore 集成。原服务 19 例仅为回归。结果见两包 validation；不修改生产或构建，不宣称独立评审／QA、设备、Compose 或远端 CI。

## P5 最小修复与本地验收

2026-10-14 用户续授权 19 个失败对应的最小生产修复和必要内部接缝，保留 701 个测试身份及预期。当前完整 61 类、735 例全部通过，零失败／错误／跳过；新增 34 回归，P5 累计 141 例：54 JVM、28 JVM 真实文件、48 Robolectric SDK 36 组件、11 Robolectric 真实服务／AppContainer／实际 DataStore 集成。原 19 个失败全部关闭；修复前报告和测试源码快照保留。

Debug 构建、28 个检查器测试、规格和必需身份／产物门禁通过，Lint 0 错误／12 个既有警告。服务就绪周期、固定确认期限、前台会话导航、反馈归属及诊断进程关联／异常隔离已修复。证据位于本机忽略目录 `app/build/reports/p5-contracts/repaired-final/`，失败历史保留在 `full/`；具体 AC 及证据层级见 [SDD-008](service-and-home/validation.md#修复后本地验收)、[SDD-009](diagnostics/validation.md#修复后本地验收)。

Release 单测任务缺失继续阻塞，静态配置经源指纹核对后复用首轮证据；真实 Compose／设备未验证，完整分层 P5 验收未完成。无提交／推送／PR、远端 CI 或独立评审／QA，不改持久化格式、依赖、构建、工作流或远端设置。T-12 未完成且继续停止，P6 未启动。
