# 验证记录：SDD-008

日期：2026-10-14。基线 main `0c9a53ffde08a6933fdb945ed187643589896462`。本文分列首轮测试与后续生产修复证据。规格见 [spec](spec.md)，任务见 [tasks](tasks.md)。

**当前：最小生产修复、本地及 PR 远端自动化验收通过。** 全量 735 例通过，原 701 身份和预期保留，19 个原失败已关闭。Release／Compose／设备限制保留；详见[修复后本地验收](#修复后本地验收)。

## 首轮结果（修复前历史）

**首轮交付完成，契约验收未通过。** 本包新增 48 例：35 通过、13 失败，零错误／跳过。全量 57 类、701 例：682 通过、19 失败，零错误／跳过；原 594 个身份全部执行并通过，既有断言未改。未改生产代码、公开 API、格式、依赖、构建、工作流或远端设置，无提交／推送／PR。SDD-001/T-12 仍未完成且停止，P6 未启动。


> 上述首轮结论及以下修复前结果为历史证据；用户随后授权修复。当前结论见[修复后本地验收](#修复后本地验收)：735 例全部通过，19 个原失败关闭。

## 方法与证据层级

| 测试类 | 新增 | 通过 / 失败 | 层级与方法 |
| --- | --- | --- | --- |
| [ServiceStatusContractTest](../../app/src/test/kotlin/com/zhenghuaizhi/flashskip/application/ServiceStatusContractTest.kt) | 12 | 9 / 3 | JVM；真实协调器、受控系统输入和协程虚拟时钟 |
| [HomeInteractionContractTest](../../app/src/test/kotlin/com/zhenghuaizhi/flashskip/ui/home/HomeInteractionContractTest.kt) | 16 | 11 / 5 | JVM；真实 ViewModel／采集协调器，提交屏障、流订阅和调度受控 |
| [AndroidNavigationContractTest](../../app/src/test/kotlin/com/zhenghuaizhi/flashskip/platform/settings/AndroidNavigationContractTest.kt) | 7 | 7 / 0 | Robolectric SDK 36；真实 Intent／导航适配器，启动函数返回或抛错 |
| [AndroidServiceReaderContractTest](../../app/src/test/kotlin/com/zhenghuaizhi/flashskip/platform/settings/AndroidServiceReaderContractTest.kt) | 5 | 5 / 0 | Robolectric SDK 36；真实读取器，系统服务列表由 Shadow 提供 |
| [FlashSkipAccessibilityServiceLifecycleTest](../../app/src/test/kotlin/com/zhenghuaizhi/flashskip/platform/accessibility/FlashSkipAccessibilityServiceLifecycleTest.kt) 的 `p5*` | 8 | 3 / 5 | Robolectric SDK 36；真实服务／AppContainer／实际 DataStore，连接真实 ServiceStatusCoordinator，系统读口注入“已启用” |

本包为 28 JVM、12 Robolectric 组件、8 真实服务集成。服务类原 19 个测试体保持逐字相同，作为基线回归，不计入新增八例。没有新增生产测试接缝；已有夹具私有可复用，因此只在既有类追加测试与测试侧辅助函数。诊断包另新增 59 例，见 [SDD-009 验证](../diagnostics/validation.md)。

## AC、任务和结果映射

下列方法名为精确测试身份的一部分，完整 `class + method`、结果、失败消息及每个新增方法的 AC 映射保存在本机归档 `cases.json`、`ac-map.json`；源码和必需身份清单在新检出中可用。T-01 为规格，T-02／T-03 为专项及集成，T-04 为全量证据，T-05 仍未通过。

| AC / 任务 | 测试入口及代表方法 | 实际结论 |
| --- | --- | --- |
| SDD-008/AC-01 / T-02、T-03 | ServiceStatusContractTest：`disabledAlwaysTakesPrecedence`、`systemReadFailureDoesNotThrowAndCannotKeepRunning`；服务类全部八个 `p5*` | 六个失败：读取异常外抛，五个服务／首页未就绪场景仍视为 RUNNING 或可开始。正常待保存、销毁、恢复后就绪通过 |
| SDD-008/AC-02 / T-02 | ServiceStatusContractTest：`confirmationLastsExactlyTwoSeconds`、`repeatedRefreshDoesNotExtendCurrentDeadline`、`expiredCycleCannotBeReopenedByRefresh`、`reenableStartsFreshDeadlineAndOldTimerCannotFinishIt` | 首轮边界、再启用、迟到连接及旧任务取消通过；刷新延期与过期重开两个失败 |
| SDD-008/AC-03 / T-02、T-03 | AndroidNavigationContractTest 全七例；AndroidServiceReaderContractTest 全五例 | 全通过；启动被接受不证明系统页面到达 |
| SDD-008/AC-04 / T-02 | HomeInteractionContractTest：`absentConsumerReceivesPendingNavigationWhenItReturns`、`configurationStopWithMissingConsumerRetainsPendingNavigation`、`backgroundConfigurationCompletionDoesNotEmitNavigation`，及取消／替换／超时／新 ViewModel／已消费不重放五例 | 三个失败；其他负例通过不能掩盖待消费正例失败，也不能证明真实进程重启 |
| SDD-008/AC-05 / T-02、T-03 | HomeInteractionContractTest：`homeLaunchFailureCancelsOnlyOriginalSessionAndReportsFailure`、`lateHomeFailureCannotCancelReplacement`、`navigationCancellationStorageFailureRetriesOriginalAction`、`settingsFailureHasNoBusinessWritesOrAutomaticRetry`；原 HomeViewModelLifecycleTest 九例回归 | 全部所列通过；实际 Activity 系统调度未验证 |
| SDD-008/AC-06 / T-02 | HomeInteractionContractTest：`olderDeleteCompletionCannotEraseNewSettingsFailure`、`olderDeleteFailureCannotReplaceNewSettingsFeedbackOrRetryOwner`；原保存／管理／生命周期回归 | 两个失败；不能宣称全部异步反馈已有归属保护 |
| SDD-008/AC-07 / T-02、T-03 | HomeInteractionContractTest：`disconnectedServiceKeepsUserSwitchButBlocksCapture`、`serviceUnavailableDoesNotPreventExplicitDisable`；原 StorageAvailabilityRegressionTest、HomeViewModelSaveTest、HomeViewModelManagementTest 回归；HomeScreen 静态核对 | 所列消费测试通过；服务实际中断传播由 AC-01 红例证明仍有缺口；Compose 控件未验证 |

## 产品失败身份与触发

| 类 | 方法 | 预期 / 实际 |
| --- | --- | --- |
| ServiceStatusContractTest | `repeatedRefreshDoesNotExtendCurrentDeadline` | 首次确认 2000ms 后 ABNORMAL；实际因 1500ms 刷新仍 CONFIRMING |
| ServiceStatusContractTest | `expiredCycleCannotBeReopenedByRefresh` | 同轮过期刷新保持 ABNORMAL；实际重新 CONFIRMING |
| ServiceStatusContractTest | `systemReadFailureDoesNotThrowAndCannotKeepRunning` | 读取失败不外抛且不 RUNNING；实际 SecurityException 外抛 |
| FlashSkipAccessibilityServiceLifecycleTest | `p5WaitingForFirstStorageReadMustNotReportReady` | 首次读取被门闩阻塞时不能 RUNNING；实际 RUNNING |
| FlashSkipAccessibilityServiceLifecycleTest | `p5FailedRecoveryMustNotReportReady` | 恢复写失败时不能 RUNNING；实际 RUNNING |
| FlashSkipAccessibilityServiceLifecycleTest | `p5UnavailablePreferencesMustNotReportReady` | 偏好读取失败时不能 RUNNING；实际 RUNNING |
| FlashSkipAccessibilityServiceLifecycleTest | `p5InterruptedServiceMustNotReportReady` | 中断后不能 RUNNING；实际 RUNNING |
| FlashSkipAccessibilityServiceLifecycleTest | `p5InterruptedServiceBlocksHomeStartEvenWhenCaptureIsEmpty` | 中断且无采集时开始门禁应关闭；实际 canStart=true，首断言失败，后续 startCapture 断言未执行 |
| HomeInteractionContractTest | `absentConsumerReceivesPendingNavigationWhenItReturns` | 接收者返回取得 GoHome 原会话；实际空列表 |
| HomeInteractionContractTest | `configurationStopWithMissingConsumerRetainsPendingNavigation` | 配置重建后取得原请求；实际空列表 |
| HomeInteractionContractTest | `backgroundConfigurationCompletionDoesNotEmitNavigation` | 真实离开后配置完成不发跳转；实际收到了 GoHome |
| HomeInteractionContractTest | `olderDeleteCompletionCannotEraseNewSettingsFailure` | 较新 SETTINGS_FAILED 保留；实际被旧删除成功清为 null |
| HomeInteractionContractTest | `olderDeleteFailureCannotReplaceNewSettingsFeedbackOrRetryOwner` | 较新 SETTINGS_FAILED 保留；实际被旧删除失败覆盖为 STORAGE，重试断言尚未执行 |

上述均为产品契约失败，没有调整断言匹配基线行为。异常外抛在 Gradle JUnit XML 中计入 failures，errors 仍为零。首断言失败后的断言没有执行，不能当作额外已观察结论。

## 全量命令与门禁

在 JDK 21（Android Studio bundled JBR）、现有 SDK／Gradle 配置下运行：

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --rerun-tasks --no-build-cache --continue --console=plain
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts/ci/tests -v
PYTHONDONTWRITEBYTECODE=1 python3 scripts/ci/check_specs.py
PYTHONDONTWRITEBYTECODE=1 python3 scripts/ci/check_test_results.py --require-artifacts
```

- 最终 Gradle 56 项任务全部执行，701 例／19 失败导致整体退出 1；`--continue` 下 `assembleDebug` 和 `lintDebug` 完成。APK 非空，JUnit／HTML／Lint 报告存在。
- Lint 0 错误、12 警告：OldTargetApi 1、AndroidGradlePluginVersion 1、GradleDependency 6、NewerVersionAvailable 1、ObsoleteSdkInt 1、MonochromeLauncherIcon 2；均为既有类型。
- 28 个检查器测试通过，规格检查通过。必需身份／产物门禁按 19 个红例退出 1；没有缺失身份、产物错误、额外跳过或基线失败。
- `.ci/required-tests.json` 登记全部 701 身份；与 HEAD 清单集合比较证明原 594 身份保留，报告逐身份证明全部通过；已有测试源码的 Git 差异仅服务类插入，无删除行。生产、依赖、构建、工作流、检查器实现差异为空。

原始证据位于忽略目录 `app/build/reports/p5-contracts/full/`：`full.log`、`junit/`、`tests-html/`、Lint XML／HTML、各检查日志、`summary.json`、`cases.json`、`ac-map.json`、基线及现行必需清单、`source-fingerprints.json`、`artifact-fingerprints.json`。构建 APK 留在 `app/build/outputs/apk/debug/app-debug.apk`，仅指纹纳入归档。`preflight/` 是较早的定向验证，不混入最终计数。新检出不含这些本机产物，须运行上述命令重建证据。

## 静态消费检查与剩余限制

MainActivity 转发 onResume／onStop 和配置变化；HomeScreen 状态使用 collectAsStateWithLifecycle，但 effects 使用 LaunchedEffect 内直接 collect，缺少前台消费门禁；与后台导航红例相符。开始按钮使用 capture.canStart，删除提交受 operationInProgress／ruleActionsEnabled 限制，目标消失会关闭对话框；确认与重试反馈由 deleteSubmitted 选择。文案 RUNNING 为“已准备好”。这里只证明代码接线，未执行真实 Compose 点击、旋转恢复、焦点或无障碍交互。

当前未配置 Compose 测试依赖；不在本轮添加。真实 Android 授权撤销／重连、系统页面到达、进程死亡、事件投递及 OEM 行为均未验证；本轮没有独立 review／QA、设备或远端 CI。T-12 停止决定不被这些缺口重新打开。

## 首轮最小修复建议（当时未授权、未实施）

1. 服务向 ServiceStatusCoordinator 传递连接周期、恢复就绪和中断的内部可用性事实；存储失败时同步降级，正常采集暂停保持服务可用。系统读取异常隔离并转为非运行状态，保留业务取消。
2. 将 2 秒截止绑定首次确认轮次；刷新只更新观察，不延期或复活旧轮。保持已有轮次取消和迟到连接行为。
3. 用同会话待消费请求表达 GoHome；前台消费时重新核对会话、期限与实际离开状态，消费后清除；配置重建保留，进程重启不恢复。
4. 为异步反馈与重试绑定操作／会话身份，新反馈后旧回调只完成自身事务，不覆盖新展示与重试。补旧生命周期回调与新操作组合；真实 Compose 验证需要另行批准依赖／测试配置。

以上修复应保持既有 701 身份与预期，再补必要回归；当前仅交付可复现失败与影响面。

## 修复后本地验收

2026-10-14 用户续授权最小生产修复、必要内部接缝、回归及本地证据更新。**19 个原失败全部关闭，全量 61 类、735 例通过，零失败／错误／跳过。** 原 701 身份及预期保留；新补 34 例。SDD-008 累计新增 67 例：44 JVM、12 Robolectric SDK 36 组件、11 Robolectric 真实服务／AppContainer／实际 DataStore 集成；均通过。完整分层契约验收仍未完成，真实 Compose 和设备证据限制保留。

### 修复及 AC 追踪

| AC | 修复及原失败结果 | 新增回归 |
| --- | --- | --- |
| AC-01 | 内部可用性快照区分连接与恢复就绪；服务中断、恢复失败、存储不可用同步发布非就绪，旧周期不能污染新连接；系统读取异常转 ABNORMAL，业务取消继续传播。原读取异常及五个服务红例通过 | ServiceAvailabilityRegressionTest 的恢复门禁、收集前即时门禁、旧周期、读取恢复与取消；服务类追加三例：未知存储降级／修复恢复、阻塞读取时重连不能复用旧就绪、中断后存储更新不能自动重新就绪 |
| AC-02 | 2 秒确认绑定同一连接周期的首次观察，刷新不延期或复活；原两个红例通过 | ServiceAvailabilityRegressionTest 的重复就绪失败不重开期限、新连接无断开观察间隙仍有独立期限 |
| AC-03 | 系统读取／导航适配器行为不变，原 12 例通过 | 本次无新增；STARTED 仍不代表页面到达 |
| AC-04 | 待消费导航留在 ViewModel，接收者返回可取得；仅前台有效原会话消费一次，消费时再次检查期限和真实离开，取消／替换失效。原三个红例通过 | HomeInteractionRegressionTest 前四例：并发收集者只消费一次、配置变化后台延后、实际离开清除、无显式刷新仍校验过期 |
| AC-05、AC-06 | 操作结果、重试与生命周期回调保留归属；新设置失败清除旧重试和保存展示，旧完成不得覆盖。原两个反馈红例与已有导航失败／存储重试通过 | HomeInteractionRegressionTest 后五例：配置重建保留失败重试、旧离开失败／旧返回成功／旧桌面失败不覆盖新结果、设置失败取代已保存展示 |
| AC-07 | HomeScreen 在 RESUMED 收集 effects；开始入口直接核对当前就绪，正常待保存暂停不视为服务故障；原开关／管理／存储回归通过 | 仅静态消费接线核对；无真实 Compose 控件执行 |

新增 [ServiceAvailabilityRegressionTest](../../app/src/test/kotlin/com/zhenghuaizhi/flashskip/application/ServiceAvailabilityRegressionTest.kt) 7 例、[HomeInteractionRegressionTest](../../app/src/test/kotlin/com/zhenghuaizhi/flashskip/ui/home/HomeInteractionRegressionTest.kt) 9 例及既有服务类追加 3 例，共 19 例。诊断包新增 15 例，见 [SDD-009](../diagnostics/validation.md#修复后本地验收)。`ServiceAvailability` 与诊断构造参数为内部接缝；既有 `connected`／`setConnected` 兼容入口保留，默认服务装配使用分阶段就绪入口。

### 全量验证与保存性

重新执行上文全部 Debug 命令，56 个 Gradle 任务全部执行，关闭构建缓存，退出 0；735 例通过，Debug APK 非空，Lint 0 错误／12 个既有警告。28 个 CI 检查器测试、规格检查、735 必需身份及产物门禁均通过。没有放宽检查器或新增忽略条件。

在修复前保存全部测试／夹具及必需清单 SHA-256。除服务类插入三个回归外，全部原测试与夹具字节不变；删除该新增块后，服务文件完整还原到修复前版本，原 27 个服务测试体及预期全部保留。逐身份比对修复前 701 例均存在且通过，包括原 19 个失败；P5 之前的 594 例也全部通过。数据编码、领域端口、journal 文件索引 schema、依赖、构建、工作流与检查器实现未改。

本机忽略目录 `app/build/reports/p5-contracts/repaired-final/` 保存 `full.log`、JUnit／HTML、Lint、检查日志、`summary.json`、`cases.json`、`closed-failures.json`、141 个 P5 新增身份的 `ac-map.json`、修复前及当前必需清单、源码／产物指纹、复核脚本。APK 指纹纳入归档，APK 位于 Debug 输出目录。`full/` 的 701 例／19 失败报告及指纹保持不变，`pre-repair-source/` 保留测试源码快照；`preflight/` 仍是早期定向证据。新检出需重新执行命令，不会带上忽略产物。

Release 任务不存在的阻塞沿用首轮日志；核对构建、Manifest、备份 XML 和 AppContainer runtime 装配源指纹未变后复用首轮生成的静态配置，不把它记为修复后 Release 运行测试。没有独立评审／QA、远端 CI、设备、实际进程死亡或真实 Compose 交互证据。无提交／推送／PR，T-12 未完成且继续停止，P6 未启动。

## P5 PR 交付续授权

2026-10-14 用户续授权新建分支、提交、推送、创建 PR、等待远端 CI、下载核验报告并回写证据，PR 保持未合并。此前“无提交／推送／PR”描述本地修复阶段。持久化格式、依赖、构建配置、工作流及远端设置不得更改；Release 单测阻塞保留，T-12 未完成且继续停止。远端结果须另行核验，不复用本地通过结论。

## PR 与远端验收

[PR #8](https://github.com/Mr414Zheng/flash_skip_android/pull/8) 位于 `codex/p5-service-home-diagnostics`，产品 head `a81b712900cf93599e6b80eb02837dda4c263efd` 已通过 [Linux CI 38526504633](https://github.com/Mr414Zheng/flash_skip_android/actions/runs/38526504633) 的 Specs 和 Android。下载两组报告后核验 61 类／735 例全部通过，完整身份与必需清单及本地报告一致，包括全部原 701 身份和 19 个原失败；28 个检查器、规格／PR 正文、Debug 构建及产物门禁通过，Lint 0 错误／15 警告。

API 报告大小及摘要与下载 ZIP 匹配，environment 检出提交与产品 head 的合并父提交关系已核对。分层结果、artifact ID／digest、路径与限制见 [P5 远端 CI](../ci.md#p5-pr-与远端-linux-ci-验收)。工作流未上传 APK 二进制，不声称下载或安装了远端 APK。证据文档 head 须再验其最新 CI；PR 保持未合并供用户审阅。

Release 任务缺失继续阻塞，真实 Compose／设备及独立评审／QA 未执行；完整分层契约验收仍未完成。持久化格式、依赖、构建、工作流及远端设置不变，T-12 未完成且继续停止。
