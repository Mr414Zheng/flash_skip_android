# FlashSkip 能力清单与验证现状

基线为 `e354a99`，盘点日期为 2026-10-13。清单覆盖当前仓库中的业务职责，用于安排规格和测试，不表示发现了同等数量的缺陷。契约登记见 [规格索引](README.md)，执行顺序见 [分阶段计划](roadmap.md)。

## 仓库基线

- 单个 Android `app` 模块，81 个已跟踪生产 Kotlin 文件：根目录 3、application 4、data 4、domain 24、platform 40、ui 6。
- 6 个已跟踪测试 Kotlin 文件，包含 5 个测试类和 1 个测试支撑文件；现有 75 例通过记录仅覆盖 SDD-001 的自动化范围。
- 已配置 JUnit 4、Robolectric 和协程测试；没有已跟踪的 `app/src/androidTest/` 测试、CI 配置或覆盖率门槛。此处只描述仓库，不推断远端管理设置。
- 当前只有 `capture-save-retry` 功能规格包。P0 新增项目级文档，不创建其他能力的空规格包或占位测试。

## 职责与状态来源

[AppContainer](../app/src/main/kotlin/com/zhenghuaizhi/flashskip/AppContainer.kt) 装配依赖。HomeViewModel 发起用户操作，RuleCaptureCoordinator 协调采集和规则管理；采集与偏好分别持久化在两个 DataStore。CompositeRuleCatalog 从样本异步派生目录，服务消费采集状态并连接平台事件与自动执行引擎。领域端口承接时钟、页面动作和存储，Android 节点与系统生命周期留在平台层。

推广 SDD 沿用这些边界。跨能力的共享语义在主责规格中定义，消费方引用；不为了文档结构重排源代码或拆分 Gradle 模块。

| 能力 / 优先级 | 主要入口 | 负责的行为与副作用 | 已有证据与缺口 |
| --- | --- | --- | --- |
| C01 保存与安全重试 / 已有基线 | [RuleCaptureCoordinator](../app/src/main/kotlin/com/zhenghuaizhi/flashskip/application/rulecapture/RuleCaptureCoordinator.kt)、HomeViewModel | 合法会话保存、先关闭再提交、失败重试、取消、旧请求隔离及保存时开启竞争 | SDD-001 自动化 75 例通过；T-12 未完成且停止，不作设备完成声明 |
| C02 规则就绪与目录 / 高 | [RuleCandidateAnalyzer](../app/src/main/kotlin/com/zhenghuaizhi/flashskip/domain/rulecapture/RuleCandidateAnalyzer.kt)、[RuleValidator](../app/src/main/kotlin/com/zhenghuaizhi/flashskip/domain/rules/RuleValidator.kt)、[CompositeRuleCatalog](../app/src/main/kotlin/com/zhenghuaizhi/flashskip/domain/rules/CompositeRuleCatalog.kt) | 安全样本转为规则、就绪判断、整组校验、目录发布和版本匹配；目录是派生状态 | 试点经过真实分析及目录链；门槛、非法输入、冲突回退和旧计算取消缺少独立契约 |
| C03 自动执行与动作确认 / 高 | [AutomationEngine](../app/src/main/kotlin/com/zhenghuaizhi/flashskip/domain/automation/AutomationEngine.kt)、[PostActionObserver](../app/src/main/kotlin/com/zhenghuaizhi/flashskip/domain/automation/PostActionObserver.kt)、[AccessibilityPageGateway](../app/src/main/kotlin/com/zhenghuaizhi/flashskip/platform/accessibility/AccessibilityPageGateway.kt) | 前台会话、查询、点击/手势、观察确认、补点、冷却和执行记录；实际动作经 PageGateway | 无专项测试；动作允许/拒绝、迟到命令、记录失败不重复动作等风险优先覆盖 |
| C04 采集生命周期 / 高 | [RuleCaptureCoordinator](../app/src/main/kotlin/com/zhenghuaizhi/flashskip/application/rulecapture/RuleCaptureCoordinator.kt)、[FlashSkipAccessibilityService](../app/src/main/kotlin/com/zhenghuaizhi/flashskip/platform/accessibility/FlashSkipAccessibilityService.kt) | 配置、目标进入、阶段转换、离开/返回、过期、断连和新旧会话身份；写采集状态 | 试点覆盖部分失效与保存竞争；完整状态转换、恢复、双时钟和服务事件顺序待验证 |
| C05 采集证据与目标识别 / 高 | [AccessibilityCaptureAdapter](../app/src/main/kotlin/com/zhenghuaizhi/flashskip/platform/accessibility/AccessibilityCaptureAdapter.kt)、[AccessibilityCaptureProbe](../app/src/main/kotlin/com/zhenghuaizhi/flashskip/platform/accessibility/AccessibilityCaptureProbe.kt)、[CaptureRuleSafetyPolicy](../app/src/main/kotlin/com/zhenghuaizhi/flashskip/domain/rulecapture/CaptureRuleSafetyPolicy.kt) | 包/窗口身份、真实点击恢复、候选和语义差分、扫描预算及覆盖情况；向协调器提交证据 | 试点验证最终证据类型，未验证证据如何从节点生成；缺少错误窗口、歧义、不可读和不完整扫描用例 |
| C06 持久化与兼容 / 高 | [PreferencesRuleCaptureStore](../app/src/main/kotlin/com/zhenghuaizhi/flashskip/data/preferences/PreferencesRuleCaptureStore.kt)、[PreferencesFlashSkipStore](../app/src/main/kotlin/com/zhenghuaizhi/flashskip/data/preferences/PreferencesFlashSkipStore.kt)、[RuleCaptureCodec](../app/src/main/kotlin/com/zhenghuaizhi/flashskip/data/preferences/RuleCaptureCodec.kt)、[ActionRecordCodec](../app/src/main/kotlin/com/zhenghuaizhi/flashskip/data/preferences/ActionRecordCodec.kt) | 文件提交、编码、安全过滤、初始化清理、读取降级及记录保留 | 已有真实保存/重开测试；损坏内容、旧版本、初始化并发、记录边界待独立审定验证 |
| C07 规则管理 / 高 | [RuleCaptureCoordinator](../app/src/main/kotlin/com/zhenghuaizhi/flashskip/application/rulecapture/RuleCaptureCoordinator.kt)：setAppEnabled、clearTarget、clearAll | 显式开关、单目标/全部删除、无关数据隔离；写开关并清除样本与会话 | 开启与保存交错已测；删除中的部分失败、残留开关和全部删除中断缺少专项测试 |
| C08 服务与首页交互 / 中 | [ServiceStatusCoordinator](../app/src/main/kotlin/com/zhenghuaizhi/flashskip/application/ServiceStatusCoordinator.kt)、[HomeViewModel](../app/src/main/kotlin/com/zhenghuaizhi/flashskip/ui/home/HomeViewModel.kt)、[HomeScreen](../app/src/main/kotlin/com/zhenghuaizhi/flashskip/ui/home/HomeScreen.kt) | 系统授权与连接状态、页面生命周期、控件和反馈、导航失败；将用户操作传入协调器 | 已有 18 例保存相关 ViewModel 测试；未覆盖完整服务状态、实际 Compose 控件及系统生命周期 |
| C09 诊断与数据边界 / 中 | [CaptureDiagnosticJournal](../app/src/main/kotlin/com/zhenghuaizhi/flashskip/platform/diagnostics/CaptureDiagnosticJournal.kt)、[CaptureTraceLog](../app/src/main/kotlin/com/zhenghuaizhi/flashskip/platform/accessibility/CaptureTraceLog.kt)、AppContainer、[Manifest](../app/src/main/AndroidManifest.xml) | 诊断开关、元数据筛选、队列与文件上限、写入失败、备份边界；诊断文件副作用 | 有实现和历史诊断资料，无专项契约测试；不把日志存在视为诊断安全或完整性的证明 |

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

## 待审定事项

以下是后续规格的决策输入，不是 P0 批准的新产品行为，也不要求现在调整实现：

| 事项 | 当前事实或问题 | 审定阶段 |
| --- | --- | --- |
| 规则门槛 | 分析器当前稳定样本门槛为 1；门槛及负例需要明确业务依据 | P2 / SDD-002 |
| 自动动作确认与补点 | 当前区分动作接受和关闭确认，并限制补点；哪些证据足够、时间边界如何解释 | P2 / SDD-003 |
| 采集恢复与期限 | 当前服务首次观察部分遗留阶段时会失败结算；过期恢复涉及墙钟和单调时钟 | P3 / SDD-004 |
| 数据兼容与回退 | 当前使用 v2、清理旧键、对无效内容回退空状态；是否作为长期策略，哪些历史数据需要保留 | P4 / SDD-006 |
| 删除部分失败 | 目标停用与采集清理不是跨库事务；全部删除涉及多个目标，需定义中断快照和重试 | P4 / SDD-007 |
| 诊断承诺 | 当前诊断有队列与容量边界；允许记录什么、丢失如何表达、release 行为需独立规定 | P5 / SDD-009 |

T-12 停止决定不是待审定事项；不得因上述清单重新启动京东排查或设备验证。
