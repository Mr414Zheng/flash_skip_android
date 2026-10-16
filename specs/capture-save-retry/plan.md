# 实施计划：采集结果保存失败与安全重试

## 当前状态（2026-10-16 用户手工验收）

T-12 按用户手工验收口径收尾：D-01～D-04 用户确认按步骤通过，但未留截图或日志；D-05 复用已有 QQ 音乐限定场景证据。详见[分项结果与证据限制](validation.md#t-12-用户手工验收收尾)。没有独立复验；样本数量、持久化细节、时序和精确构建关联不能从现有材料独立确认的部分继续保留。P5 其他缺口不关闭，契约与验收定义不变。

以下初次 75 例、模拟器尝试及停止决定保留为历史背景；任务清单和追踪表中的 T-12 状态已按本次用户确认更新，不将原失败追溯改为通过。

状态：规格已获用户审定；依据 [spec.md](spec.md)。本轮已按续授权完成 UI 与真实存储集成测试，连同此前协调器测试共 75 例通过，未修改运行代码。已按续授权准备 Android 15 模拟器和京东并探测页面；T-12 未完成，已按用户要求停止京东广告复现排查及 D-01～D-05 剩余验证，T-13 已记录此决定；执行证据见 [validation.md](validation.md)。

## 当前实现依据

以下链接和符号对应基线 `3fa4dc0`，是定位入口，不代表验证结论。

| 位置 | 已确认事实与验证重点 |
| --- | --- |
| [RuleCaptureCoordinator](../../app/src/main/kotlin/com/zhenghuaizhi/flashskip/application/rulecapture/RuleCaptureCoordinator.kt)：`finish`、`operation`、`persistState` | 同一互斥锁内先关闭开关，再更新采集状态；捕获 `IOException`，传播取消；核对部分失败与返回丢失 |
| 同文件：`setAppEnabled` | 在操作锁内校验采集阻塞、目录样本一致性和规则就绪；核对请求等待锁后的行为 |
| [HomeViewModel](../../app/src/main/kotlin/com/zhenghuaizhi/flashskip/ui/home/HomeViewModel.kt)：`finishCapture`、`retryLastOperation`、`launchOperation` | 保存请求携带会话 ID；操作中忽略新操作；新操作替换 `lastOperation`；核对仍保留的旧重试不切换到新会话 |
| [PreferencesFlashSkipStore](../../app/src/main/kotlin/com/zhenghuaizhi/flashskip/data/preferences/PreferencesFlashSkipStore.kt) / [PreferencesRuleCaptureStore](../../app/src/main/kotlin/com/zhenghuaizhi/flashskip/data/preferences/PreferencesRuleCaptureStore.kt) | 两个独立 DataStore；单次 `edit` 提交各自状态，无跨库事务 |
| [CompositeRuleCatalog](../../app/src/main/kotlin/com/zhenghuaizhi/flashskip/domain/rules/CompositeRuleCatalog.kt) | 异步派生规则，快照包含 `sourceSamples`；不能把保存成功等同于目录已就绪 |
| [FlashSkipAccessibilityService](../../app/src/main/kotlin/com/zhenghuaizhi/flashskip/platform/accessibility/FlashSkipAccessibilityService.kt)：`observeCaptureState` | 有效采集状态驱动自动执行暂停；需要设备证据补充领域测试 |
| [app/build.gradle.kts](../../app/build.gradle.kts) | 已有 JUnit 4、Robolectric、coroutines-test；基线时无已跟踪测试源码 |

## 目标结构与实施边界

沿用现有 UI → 协调器 → 存储端口 / 规则目录的结构，不新增通用事务层或重试框架。

- 协调器负责会话身份、串行顺序、保存和开启条件。
- 存储适配器负责单次原子提交与编码；不自行决定重试、开启或回滚。
- UI 负责显式操作、失败反馈和固定原会话的重试；不复制业务校验，不新增请求队列或历史重试记录。
- 测试通过已有端口注入故障；故障控制、虚拟时钟与屏障仅放入测试源码。

本试点不修改端口签名、持久化键、JSON 版本或依赖版本，不需要数据迁移。若验证发现现有边界无法满足契约，先记录具体失败和备选方案，不通过顺带重构扩大范围。

不新增保存回执、结果查询接口、跨进程重试、新目标应用夹具或采集算法改造。设备准备曾获续授权并已执行；2026-10-13 用户决定停止排查京东广告复现原因及 T-12 剩余验证。T-12 保留未完成状态，不再推进，不修改采集算法或制造目标夹具。

## 状态、提交与并发模型

必须顺序完成：`校验会话 → 提交关闭开关 → 提交样本/清除会话`。保存提交后，调用返回与异步目录发布没有规定的先后顺序；测试应分别等待和验证两者。

保存资格按锁内会话 ID、阶段及载荷检查，称为“可保存会话”；不额外检查期限。失效转换先提交则保存返回 `Stale`；保存先成功提交则针对旧会话的后续取消/失效转换不得撤销样本。缺失载荷同样返回 `Stale` 且零写入。

第二次提交前失败时允许出现“目标关闭、原会话仍在”的中间状态，这是已确认的安全策略。互斥锁仅保证本协调器内操作串行，不提供两份存储的原子提交或进程崩溃恢复。

关闭已提交、保存未提交时发生协程取消：传播取消，保留上述中间状态并释放锁；在新调用协程中对仍可保存的原会话重试。存储失败与取消分别断言，不要求取消创建 UI 重试入口。

直接调用协调器的开启请求可能等待保存释放锁；必须按锁内最新状态判定，不能要求所有与保存时间重叠的请求都立即被拒绝。分别保存提交快照与全部操作结束快照：前者目标关闭、原会话清除；后者允许反映后续合法开启、新会话或删除。目录发布仅更新资格，不重放被拒绝的请求。UI 操作中忽略新输入的行为单独测试，不用 UI 构造锁等待。

AC-08 验证持久化提交与调用方获知成功之间的区别：已提交后的取消不代表回滚，旧 ID 重试必须返回 `Stale` 且零写入，与 AC-05 一致。`Stale` 不证明历史成功；在无后续删除的受控用例中按本次样本 ID、目标及内容重读确认，既有样本必须保留。调用方忽略成功返回的用例另行验证，不要求抛取消异常。

## 建议测试文件与职责

以下六个文件均已实现并通过测试。路径均位于 `app/src/test/kotlin/com/zhenghuaizhi/flashskip/`。

| 建议文件 | 职责 | 验收关联 |
| --- | --- | --- |
| `testing/CaptureSaveFixtures.kt` | 合法点击样本、人工候选、其他目标数据；可控存储/目录与时钟；两次提交前、中间取消及保存提交后屏障；快照记录 | AC-01～AC-08 |
| `application/rulecapture/RuleCaptureSaveTest.kt` | 成功、两类写入失败、重试、旧 ID、缺载荷、失效竞争、证据类型 | AC-01～AC-05、AC-07 |
| `application/rulecapture/RuleCaptureCancellationTest.kt` | 两次提交之间取消、锁释放后重试、提交后取消与忽略成功返回、后续会话及显式开启隔离 | AC-03、AC-04、AC-08 |
| `application/rulecapture/RuleCaptureEnableInterleavingTest.kt` | 保存与开启的锁顺序、目录滞后和拒绝分支 | AC-06 |
| `ui/home/HomeViewModelSaveTest.kt` | 失败反馈、原 ID 绑定、操作中忽略输入、新操作替换旧重试、目录反馈；不模拟 UI 排队 | AC-01～AC-07 |
| `data/preferences/CaptureSavePersistenceTest.kt` | Robolectric 下真实 DataStore、编码、目录派生与关闭实例后重读；两存储最终状态 | AC-01、AC-04、AC-06～AC-08 |

优先使用小型手写替身和现有依赖。替身只模拟存储原子提交、失败点与调度，不复制 `finish` 或规则分析算法。合法 READY 数据应能通过真实规则分析器；集成验证使用真实 `CompositeRuleCatalog`。

## 实施顺序与检查点

1. **审定规格。** 核对 AC、可保存会话、快照边界和不在范围内的恢复策略，确认后再开始测试实现。
2. **建立测试支撑。** 使用 `runTest`、测试调度器与可注入时钟；每个用例独立初始化并取消作用域。用显式屏障控制交错，不用真实休眠制造竞争。
3. **执行协调器契约测试。** 覆盖两种保存输入、两次提交的失败、两次提交之间及保存后的取消、缺失载荷、重复/旧请求，以及保存与失效操作两种顺序。断言对外结果、锁释放和分时快照，不只检查调用次数。
4. **执行开启及 UI 测试。** 协调器测试控制保存锁与目录发布。UI 测试保留真实协调器：保留旧 ViewModel 的存储失败重试状态，绕过该 ViewModel、直接调用同一协调器取消旧会话并建立新会话，再触发旧重试；另测同一 UI 主动新操作后旧入口被替换或清除。不得通过改 UI 语义制造测试路径。
5. **补真实持久化证据。** 使用独立临时 DataStore 文件；关闭并等待旧实例结束后重建读取。设备检查作为单独的补充集成任务，预先登记现有设备与目标；不具备条件时报告阻塞。
6. **处理偏差。** 先定位失败属于测试环境、测试替身还是产品实现；提出最小修复及影响 AC。后续获得实施授权后才修改生产代码，重跑受影响测试和必要构建检查。
7. **汇总验收。** 为每条 AC 附测试名、执行结果和证据路径；分别报告保存契约自动化结果与设备集成状态。可完成自动化契约验证而设备项仍阻塞，不据此宣布完整设备验证或发布就绪。

## 验证影响与限制

- 单元测试证明操作契约与确定性并发顺序，不能证明 Android 事件投递或所有设备上的无障碍暂停。
- Robolectric 验证真实适配器和编码，不能代替真实断电、文件系统故障或服务重连测试。
- AC-08 用提交后屏障/取消及忽略成功返回模拟结果未送达；不把提交后抛 `IOException` 冒充“写入未提交”。
- 两次提交之间的取消使用确认关闭已提交、保存尚未提交的屏障；新调用必须使用未取消的协程，检查锁释放后可继续操作。
- 持久化重读只验证已提交数据，不调用服务恢复逻辑，不承诺恢复未保存采集。
- 设备暂停检查必须先在同一环境证明非采集时能触发自动执行，再验证采集期间无点击；正向对照失败则该检查不可判定，不能算通过。
- 协程测试需正确设置与还原 Main dispatcher，订阅按需启动的 UI 状态，并清理长期运行的目录收集任务。
- 当前协调器、UI 状态与真实持久化测试共 75 例通过，未发现需要生产修复的偏差；T-12 未完成且已按用户要求停止推进，设备验收未通过。
