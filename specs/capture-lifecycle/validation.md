# 验证记录：SDD-004：采集生命周期

状态：五类政策最小修复及内部接缝已完成，本地契约验收通过。本包 196 例全部通过。

## 修复后本地验收

基线 main `34dc102` 加未提交的 P3 规格、测试与最小生产修复，2026-10-13 本地执行。用户后续授权五项政策修复及必要内部测试接缝。本轮修改 7 个既有生产文件并新增内部 AppContainerRuntime；公共构造默认装配、存储格式、依赖、应用构建配置、CI 工作流及远端设置保持不变。未提交、推送或创建 PR。

完整重跑 41 个测试类、479 例，全部通过，0 失败／错误／跳过。修复前 453 个完整身份全部保留并通过，包括原 201 例和原 42 个失败反例；本次增加 26 例。相对 main 共新增 20 个测试类、278 例：207 JVM 例、57 Robolectric SDK 36 组件例、14 Robolectric SDK 36 真实服务生命周期集成例。层级分别登记，后者不是设备证据。

完整执行 `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug --rerun-tasks --no-build-cache --continue --console=plain` 成功；28 个检查器测试、规格检查及必需身份／产物门禁通过，Lint 0 错误、12 警告。独立静态代码评审最初发现恢复写失败后后续发射可绕过门禁，已修复并补充成功重试／持续写失败两例，复审关闭且无新增 finding；这不代表独立 QA。

当前报告归档于本机忽略目录 `app/build/reports/ci/p3/repaired-final/`（Gradle、JUnit XML/HTML、Lint、检查器、规格、产物门禁、summary、完整清单及文件指纹）。`final/` 仍保留修复前 453 例／42 失败，未覆盖历史；`repaired-initial/` 保存测试夹具编译问题日志，`repaired-check/` 保存 479 例／1 个服务正控制失败的中间证据。新检出须重跑，不能依赖本机忽略产物。

### 本次修复与追踪

| 政策 / AC | 实施与运行证据 |
| --- | --- |
| CL-01 / AC-04 | 8 类新证据入口在操作锁内检查当前阶段单调期限；原 32 个截止反例通过。RuleCaptureClockTest 由 7 增至 9 例，补齐同阶段完整 expiry key 及零预算首次时钟读推进；finish 无新增硬截止 |
| CL-02 / AC-05 | 断连与首次恢复复用协调器阶段分类：有效未过期载荷保留、监听关闭；过期完成超时；等待／观察服务不可用。RuleCaptureServiceUpdateTest 由 19 增至 27 例，包括恢复配置、不重发预算、IO 重试、旧 ID 与保存锁顺序 |
| AC-06、AC-07 | 新增 FlashSkipAccessibilityServiceLifecycleTest 14 例，真实服务连接、重连、interrupt、destroy；首读延迟和恢复写失败时阻止事件处理；每个新引擎先同步暂停；取消后经过真实窗口事件、查询和 ACTION_CLICK 正控制 |

本包组织 196 例全部通过：RuleCaptureClockTest 9、RuleCaptureDeadlineTest 48、RuleCaptureLifecycleTest 72、RuleCaptureServiceUpdateTest 27、CaptureObservationLoopLifecycleTest 9、CaptureWindowResolutionLifecycleTest 8、HomeViewModelLifecycleTest 9、FlashSkipAccessibilityServiceLifecycleTest 14。原 AC-01～AC-07 追踪表中的测试身份继续有效，表中红例与阻塞描述仅是下方修复前历史。

### 真实服务和实际存储证据

集成夹具运行真实 FlashSkipAccessibilityService、AppContainer、协调器、规则目录、AutomationEngine 和 Preferences stores，通过 PreferenceDataStoreFactory 创建两个独立临时文件及可取消的自有 Job。真实 DataStore 读写只在外层包装延迟／IOException；窗口和节点由 Robolectric shadow 控制；SDK 36 的 typed ComponentInfoFlags 查询由测试 shadow 桥接到 Robolectric 既有包索引（不替代目标校验）。PageGateway 查询装饰器只记录再委托真实查询。受控双时钟与 dispatcher 不复制业务判断。各例结束执行真实销毁、完成断连结算并取消自有存储 Job，避免跨例 singleton 污染。

生产公开构造保留默认装配；测试通过每服务实例内部 factory 注入容器，使用空 Application 宿主隔离生产 singleton。反射仅调用 Android 受保护的 onServiceConnected 生命周期回调，不替换私有业务对象。原 CaptureSavePersistenceTest 保存／重开证据继续重跑；新增 14 例提供真实服务与真实存储组合证据，但不声称每例都验证磁盘重开或覆盖所有存储故障。

### 剩余限制

本地 P3 约定范围内的契约验收通过。没有新增设备、Android 系统事件投递、Binder 资源生命周期、真实 Activity 或 Compose 控件证据，也没有本轮远端 CI；SDD-001/T-12 未完成且继续停止。后续阶段没有启动。

## 修复前历史记录（以下不代表当前状态）

状态：规格与离线测试工作完成，契约验收未通过。本包测试组织 172 例，136 通过、36 失败；0 错误／跳过。规格见 [spec](spec.md)，任务见 [tasks](tasks.md)。

## 执行与证据范围

基线 main `34dc102` 加本轮未提交的规格、测试及必需清单；2026-10-13 本地执行。生产代码、依赖、应用构建配置、CI 工作流和原有测试均无差异。未提交、推送或创建 PR，无本轮远端 CI、设备或 Compose 控件证据；SDD-001/T-12 未完成且停止。

最终全量 40 个类、453 例：411 通过、42 失败，0 错误、0 跳过；原 201 个完整身份逐项保留并全部通过，新增 19 个类、252 例（210 通过、42 失败）。新增 197 例 JVM（包括 VM＋真实协调器、替身存储）及 55 例 Robolectric SDK 36 组件；Robolectric 不能代表真实服务或设备。原 `CaptureSavePersistenceTest` 的真实 DataStore 证据本轮重跑，仍只证明 SDD-001 保存／重开，不证明完整服务恢复。

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --rerun-tasks --no-build-cache --continue --console=plain
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts/ci/tests -v
PYTHONDONTWRITEBYTECODE=1 python3 scripts/ci/check_specs.py
PYTHONDONTWRITEBYTECODE=1 python3 scripts/ci/check_test_results.py --require-artifacts
```

完整 Gradle 命令因测试失败返回 1，56 个任务实际执行；debug 构建成功，Lint 0 错误、12 警告。28 个检查器测试通过；规格检查 26 份文档、9 个编号通过。产物门禁返回 1，准确阻断 42 个失败，没有缺失必需身份或产物，没有降低门禁。

真实完整类名和方法名（包括参数化阶段／入口）登记在 [必需用例清单](../../.ci/required-tests.json)。本机原始报告保存在忽略目录 `app/build/reports/ci/p3/final/`：Gradle、JUnit XML、HTML、Lint、检查器／规格／产物检查、summary 与文件指纹。新检出不含这些报告，须重新执行。`preliminary/` 保留首次 356 例/4 失败，`full/` 保留 452 例/43 失败，`fixture-corrected/` 保留 452 例/41 失败；最后补缺源即时恢复入口成为 453 例/42 失败。两项中间 Adapter 失败来自夹具使用非 IOException 和普通节点残留 `id/skip`，已修正输入并重跑通过，没有放宽政策预期。

## 本包测试组织

| 测试类 | 用例 | 通过 / 失败 | 层级 |
| --- | --- | --- | --- |
| [RuleCaptureClockTest](../../app/src/test/kotlin/com/zhenghuaizhi/flashskip/application/rulecapture/RuleCaptureClockTest.kt) | 7 | 7 / 0 | JVM / 组件 |
| [RuleCaptureDeadlineTest](../../app/src/test/kotlin/com/zhenghuaizhi/flashskip/application/rulecapture/RuleCaptureDeadlineTest.kt) | 48 | 16 / 32 | JVM / 组件 |
| [RuleCaptureLifecycleTest](../../app/src/test/kotlin/com/zhenghuaizhi/flashskip/application/rulecapture/RuleCaptureLifecycleTest.kt) | 72 | 72 / 0 | JVM / 组件 |
| [RuleCaptureServiceUpdateTest](../../app/src/test/kotlin/com/zhenghuaizhi/flashskip/application/rulecapture/RuleCaptureServiceUpdateTest.kt) | 19 | 15 / 4 | JVM / 组件 |
| [CaptureObservationLoopLifecycleTest](../../app/src/test/kotlin/com/zhenghuaizhi/flashskip/platform/accessibility/CaptureObservationLoopLifecycleTest.kt) | 9 | 9 / 0 | JVM / 组件 |
| [CaptureWindowResolutionLifecycleTest](../../app/src/test/kotlin/com/zhenghuaizhi/flashskip/platform/accessibility/CaptureWindowResolutionLifecycleTest.kt) | 8 | 8 / 0 | Robolectric SDK 36 组件 |
| [HomeViewModelLifecycleTest](../../app/src/test/kotlin/com/zhenghuaizhi/flashskip/ui/home/HomeViewModelLifecycleTest.kt) | 9 | 9 / 0 | JVM / 组件 |

## AC、任务与用例追踪

| AC / 任务 | 实际用例入口（完整身份见清单） | 结果与层级 |
| --- | --- | --- |
| AC-01 / T-02、T-03 | `RuleCaptureLifecycleTest.startRejectsBlockingSessionAndReplacesOnlyFailedSession`、`configurationAdvancesOnlyConfiguringPhase`、`awaitConfiguredRecognizesConfiguredStagesWithoutMutatingThem`；`RuleCaptureServiceUpdateTest.pendingConfigurationTimeoutFailsOriginalSessionAndOldWaitCannotFailReplacement`；`HomeViewModelLifecycleTest.startWaitsForServiceConfigurationBeforeRequestingHomeNavigation` | 已列基线场景通过，六阶段参数化；期限拒绝仍失败 |
| AC-02 / T-03 | `RuleCaptureLifecycleTest.targetObservationAdvancesWaitingOrUpdatesSameObservingTarget`、`capturedEvidenceIsAcceptedOnlyInWaitingOrObserving`、`candidateEvidenceIsAcceptedOnlyInWaitingOrObserving`；`RuleCaptureServiceUpdateTest.targetMismatchCannotReplaceCurrentObservedEvidence` | 正常/非法阶段与目标隔离通过；快速点击期限拒绝失败 |
| AC-03 / T-03、T-04 | Lifecycle 的 `departureIsIdempotentAndDoesNotRenewStageBudget`、`persistedDepartureReturnSettlesPhaseAndPreservesPendingEvidence`、`observedDepartureFallbackSettlesEvenWhenDepartureWriteWasLost`；Home 的 `configurationChangeStopDoesNotMarkDepartureOrSettleWaitingCapture`、`observedDepartureStillSettlesReturnAfterDepartureWriteFailed`、`leaveAndReturnWhileStartCommitIsPendingNeverNavigatesHomeLater` | JVM/VM＋协调器通过；真实 Activity/Compose 未验证 |
| AC-04 / T-02、T-03 | `RuleCaptureDeadlineTest` 六方法×八入口；`RuleCaptureClockTest` 七方法，包括 `wallClockJumpsCannotRenewOrExpireAnEstablishedMonotonicPlan`、`oldExpiryKeyCannotTerminateAStageThatAlreadyAdvanced`、`saveKeepsExistingLockOrderingWithoutAddingHardDeadline` | 期限组 48 例中 32 失败；时钟 7 例通过，finish 未加硬截止 |
| AC-05 / T-02、T-04 | `RuleCaptureServiceUpdateTest.disconnectRetainsValidCapturedPayloadAndContinuesBlockingAutomation`、`disconnectRetainsValidCandidatePayloadAndStopsClickListening`、两个 `disconnectSettlesExpired…AsCompletionTimeout`；`disconnectFailsWaitingAndObservingWithServiceUnavailable`、`genericFailStillClearsCapturedAndCandidatePayloads` | 4 个保留/完成超时政策反例失败；等待/观察和通用 fail 通过；服务首次发射有静态差异，未作为动态通过 |
| AC-06 / T-03 | Lifecycle 的 `oldSessionRequestsNeverWriteOrDamageCurrentSession`；ServiceUpdate 的 `serviceWriteIOExceptionPreservesStateAndRetryClearsMatchingFailure`、`cancellingServiceWritePropagatesCancellationPreservesStateAndReleasesLock`、`firstUnrecoveredEvidenceErrorSurvivesOtherFailuresAndWindowUpdates`；ObservationLoop 的旧准备/旧扫描与取消用例；ResolutionLifecycle 的旧 ID/取消/重试用例 | 已列组件通过；真实服务回调链阻塞 |
| AC-07 / T-04 | `CaptureObservationLoopLifecycleTest` 9 例、`CaptureWindowResolutionLifecycleTest` 8 例，包含总预算 107 秒、结束后 200ms 间隔、40/60/80 重试、慢调用、取消与不可用 | 组件通过；真实服务事件配置、首次状态、换引擎暂停同步、interrupt/destroy 尚无可重复集成证据 |

## 已复现偏差与最小修复建议

| 编号 | 证据 | 当前结果与最小改动边界 |
| --- | --- | --- |
| CL-01 / AC-04 | `RuleCaptureDeadlineTest` 的 `exactlyAtDeadlineRejectsTransitionAndLeavesExpirySettlementToTimer`、`oneMillisecondAfterDeadlineRejectsTransitionAndPreservesEvidence`、`waitingForOperationLockRechecksDeadlineAtEquality`、`waitingForOperationLockRechecksDeadlineAfterExpiry` × CONFIGURE/TARGET/TARGET_UPDATE/CLICK/FAST_CLICK/CANDIDATE/FAST_CANDIDATE/UPGRADE，共 32 例 | 返回 Success 并推进/更新，预期 Stale 且不提交新证据。在协调器各入口取得锁后使用当前阶段单调计划检查，覆盖同阶段目标更新与等待目标快速路径；不要给 finish 加硬截止或依赖墙钟 |
| CL-02 / AC-05 | 4 个断连用例（见上表） | 当前 generic fail 清待保存载荷；过期断连记 SERVICE_UNAVAILABLE。增加专门断连/恢复分类，未过期保留有效载荷并停止监听，过期用 COMPLETION_TIMEOUT；服务首次发射复用同政策，通用 fail 保持 SDD-001 |

上述修复仅为建议，本轮没有生产修复授权，也没有实施。失败保持必需门禁可见。

## 真实服务集成阻塞与剩余限制

当前 `FlashSkipApplication` 固定创建 `AppContainer`，容器固定 Main scope 与 Android clocks；两个顶层 private Context `preferencesDataStore` delegate 使用 AndroidX 1.2.1 默认 IO scope 和私有 singleton。已核对本地依赖源码：首次 Context 决定文件，当前入口没有关闭或重置 delegate 的方法。取消 AppContainer scope 或换临时 Context 不足以同时隔离文件、后台 DataStore Job 和时钟。本轮未建立可重复、可清理的真实服务集成夹具；没有反射替换业务对象来宣称通过。

最小后续接缝建议：为服务提供内部 runtime/container factory，容器内部构造可注入应用 scope、两个真实 store、wall/monotonic clocks 与 observer dispatcher，公开生产默认保持不变。测试可通过现有 internal Preferences store 构造＋PreferenceDataStoreFactory 独立文件和可关闭 Job，真正调用 onServiceConnected/onInterrupt/onDestroy。该生产接缝也须另行授权，不在本轮偷偷添加。

`setAutomationPaused` 缓存布尔值可能在新引擎建立后跳过同步，仅为静态风险，未计为第 43 个失败。首次状态恢复清载荷有静态路径，与协调器断连失败不能混为同一运行证据。完整 expiry key 的同阶段 start/deadline 单字段替换未单列反例；组件释放调用不能证明 Binder 资源或实际设备行为。P3 契约验收未通过。
