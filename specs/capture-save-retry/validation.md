# 保存契约验证记录

2026-10-13，用户审定规格后完成测试支撑及协调器契约测试，随后按续授权完成 T-09 UI 反馈与重试绑定及 T-10 真实存储与目录集成测试。生产基线为 `3fa4dc02af7496dbf4e32be9043b038930ad8d89`，本次提交新增测试支撑、5 个测试类及配套规格文档。未修改运行代码、端口、依赖或构建配置。随后按用户续授权新建 Android 模拟器并安装京东，解除无设备阻塞；实际检查后仍缺少可重复的开屏关闭页面，用户随后要求停止排查京东广告复现原因及 T-12 剩余验证；T-12 保留未完成状态，T-13 已记录停止决定。

## 收尾决定与提交范围

2026-10-13，用户明确要求“不再继续排查京东开屏广告无法复现的原因，不再推进 T-12 剩余验证，保持生产代码不变”。据此停止 T-12，D-01～D-05 均未完成，不再推进；已取得的环境和页面探测事实仅作历史记录，不代表设备验收通过。

本次只提交 `app/src/test/` 下的 6 个 Kotlin 文件和本目录的 4 份文档。生产代码、构建配置、仓库说明 `AGENTS.md`、APK、测试报告、截图、DataStore 导出及诊断日志均不纳入本次提交。文中的 `app/build/` 链接仅用于定位本机历史证据，全新检出不包含这些文件。

提交前复核 5 份测试 XML：75 例、0 失败/错误/跳过；测试源码在该次运行后未修改，本轮复用既有结果，不重跑测试或设备验证。

## 执行结果

在仓库根目录执行：

```sh
env JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :app:testDebugUnitTest --console=plain
```

最新完整执行时间：2026-10-13 10:51:20～10:51:26（Asia/Shanghai）。Gradle 返回 `BUILD SUCCESSFUL`，`:app:compileDebugUnitTestKotlin` 和 `:app:testDebugUnitTest` 均实际执行，测试任务未命中缓存。XML 结果如下：

| 测试类 | 执行数 | 失败 / 错误 / 跳过 |
| --- | --- | --- |
| `RuleCaptureSaveTest` | 26 | 0 / 0 / 0 |
| `RuleCaptureEnableInterleavingTest` | 16 | 0 / 0 / 0 |
| `RuleCaptureCancellationTest` | 10 | 0 / 0 / 0 |
| `HomeViewModelSaveTest` | 18 | 0 / 0 / 0 |
| `CaptureSavePersistenceTest` | 5 | 0 / 0 / 0 |
| 合计 | 75 | 0 / 0 / 0 |

协调器及 ViewModel 方法均参数化执行 `CAPTURED` 与 `AWAITING_CONFIRMATION` 两种输入。真实存储类使用 Robolectric SDK 36，每个方法内部循环两种阶段，共 10 个场景，JUnit 计为 5 例。其他方法内的原开关值或失效操作循环也未额外计数。

本地原始证据：

- [HTML 报告](../../app/build/reports/tests/testDebugUnitTest/index.html)
- [保存 XML](../../app/build/test-results/testDebugUnitTest/TEST-com.zhenghuaizhi.flashskip.application.rulecapture.RuleCaptureSaveTest.xml)
- [开启交错 XML](../../app/build/test-results/testDebugUnitTest/TEST-com.zhenghuaizhi.flashskip.application.rulecapture.RuleCaptureEnableInterleavingTest.xml)
- [取消 XML](../../app/build/test-results/testDebugUnitTest/TEST-com.zhenghuaizhi.flashskip.application.rulecapture.RuleCaptureCancellationTest.xml)
- [UI XML](../../app/build/test-results/testDebugUnitTest/TEST-com.zhenghuaizhi.flashskip.ui.home.HomeViewModelSaveTest.xml)
- [真实存储 XML](../../app/build/test-results/testDebugUnitTest/TEST-com.zhenghuaizhi.flashskip.data.preferences.CaptureSavePersistenceTest.xml)

上述报告为本机构建产物，清理构建目录后需重跑生成。首次执行曾有 18 个保存测试失败，原因均为测试将开关写入尝试的记录列表与整数比较；改为比较 `.size` 后，前一阶段 52 例通过；本轮完整回归仍全部通过。该失败属于测试断言错误，没有修改产品行为或放宽规格。

## 协调器分项证据

测试路径：[`application/rulecapture/`](../../app/src/test/kotlin/com/zhenghuaizhi/flashskip/application/rulecapture/)。下表方法名省略共同的 `acXX_` 前缀；跨 AC 方法按源码前缀定位。

| AC | 协调器自动化证据 | 结果 |
| --- | --- | --- |
| AC-01 | Save：`saveClosesTargetBeforeAppendingOneSampleAndPreservesOtherData`；核对关闭前、关闭提交、保存提交快照及完整无关数据 | 通过 |
| AC-02 | Save：`closeFailurePreservesEnabledOrDisabledStateWithoutCaptureWrite`；原开关开启/关闭分别注入提交前 IOException，保存零尝试、会话仍阻塞 | 通过 |
| AC-03 | Save：`captureFailureKeepsTargetDisabledAndPendingEvidenceIntact`；Cancellation：`cancellationBetweenCommitsPreservesEvidenceAndReleasesLockForRetry` | 通过 |
| AC-04 | Save：`retryAfterCloseFailureSavesOriginalSessionExactlyOnce`、`retryAfterCaptureFailureAllowsIdempotentCloseAndSavesExactlyOnce`；Cancellation 中间取消后由新协程重试 | 通过 |
| AC-05 | Save：顺序/并发重复、缺载荷、替换会话旧请求、同目标后续合法会话；`invalidationCommitsFirstAndQueuedSaveIsStale` / `saveCommitsFirstAndQueuedOldInvalidationCannotRemoveSample` 对取消、失败、既有超时转换分别覆盖双顺序 | 通过 |
| AC-06 | EnableInterleaving 全部 8 个方法：采集阻塞、锁等待、目录在锁内校验前/后发布、同步但 CANDIDATE、READY 但最终规则为空、合法显式开启、无请求不自启及拒绝不重放 | 通过 |
| AC-07 | Save：`payloadAndCatalogPublicationDoNotSaveWithoutExplicitConfirmation`；两类输入参数化覆盖 AC-01～AC-06，保存证据内容分别核对真实点击与人工确认类型 | 通过 |
| AC-08 | Cancellation：提交后取消、忽略成功返回、后续新会话及显式开启；旧 ID 均 Stale 且零写入，重读端口状态按样本 ID 与完整内容确认提交 | 通过 |

## T-09 UI 反馈与重试绑定

[`HomeViewModelSaveTest.kt`](../../app/src/test/kotlin/com/zhenghuaizhi/flashskip/ui/home/HomeViewModelSaveTest.kt) 使用真实 HomeViewModel、ServiceStatusCoordinator 和采集协调器，复用可控存储/目录。实际订阅 `uiState`，将 Main 绑定到测试调度器，并在每例结束取消订阅及 ViewModel 作用域、还原 Main。18 例全部通过。

| 方法（省略 AC 前缀） | 验收关联与证据 |
| --- | --- |
| `closeFailureShowsStorageRetryThenSavesOriginalSession` | AC-02/04：关闭未提交时显示 STORAGE、保留重试，恢复后保存原 ID 并清理失败反馈 |
| `captureFailureShowsStorageRetryWithoutReenablingTarget` | AC-03/04：部分失败时目标关闭，原证据保留；重试恰好新增一次 |
| `savedFeedbackTracksCatalogLagAndReadyWithoutAutomaticEnable` | AC-01/06：目录滞后不可开启，同步后显示 SAVED，仍保持关闭 |
| `savedWithoutExecutableRulesShowsNotReadyAndRejectsEnable` | AC-01/06：显示 SAVED_NOT_READY，显式开启返回 RULE_NOT_READY 且不写开关 |
| `inputsDuringSaveAreIgnoredWithoutQueueAndLaterExplicitEnableWorks` | AC-06：保存中触发开启、关闭、再次保存、取消、删除、开始及重试，均不排队；保存后新的显式开启才生效 |
| `oldViewModelRetryKeepsOriginalIdAfterCoordinatorCreatesNewSession` | AC-05：保留旧 ViewModel 重试，经协调器取消并建立新可保存会话后旧重试显示 STALE，零写入，新会话不变 |
| `successfulNewUiOperationClearsOldSaveRetry` / `newFailedUiOperationReplacesOldSaveRetryWithItsOwnAction` | AC-05：同一 UI 成功取消清除旧重试；新关闭操作失败后重试只重做该关闭，不再保存旧操作 |
| `pendingPayloadAndCatalogPublicationWaitForExplicitUiSave` | AC-07：候选及目录发布不触发保存，用户调用保存入口后证据类型正确 |

UI 证据针对 ViewModel 的真实入口与状态流，不包含 Compose 控件渲染、截图或设备点击。

## T-10 真实 DataStore 与目录集成

[`CaptureSavePersistenceTest.kt`](../../app/src/test/kotlin/com/zhenghuaizhi/flashskip/data/preferences/CaptureSavePersistenceTest.kt) 使用 `PreferenceDataStoreFactory`、生产存储适配器、生产 Codec 和 `CompositeRuleCatalog`。每个方法、每种阶段使用独立临时目录。真实文件写入后，取消并等待存储/清理/目录的共同作用域结束，再以相同文件创建新实例重读，不存在同文件活跃实例重叠。

| 方法 | 验收关联与证据 |
| --- | --- |
| `ac01_ac06_ac07_saveSurvivesReopenAndRealCatalogRequiresExplicitEnable` | 保存后重开核对完整状态和证据；真实目录同步到 READY 不自启；随后显式开启，再重开验证开启值仍在 |
| `ac02_ac04_closeFailureRetriesOriginalPayloadAndPersistsOnce` | 关闭前 IOException，采集存储零尝试；恢复后原 ID 重试并重开核对单份样本 |
| `ac03_ac04_ac07_partialFailurePreservesPayloadThenRetrySurvivesReopen` | 保存前 IOException，目标关闭且会话和载荷完整；原 ID 重试后重开验证样本、证据与目录 |
| `ac08_cancelAfterRealCommitSurvivesReopenAndReplayWritesNothing` | 真实 updateData 返回后、协调器返回前取消，异常传播；当前及重开实例的旧 ID 重试均 Stale、零写入，已提交样本内容不变 |
| `ac08_ignoredSuccessfulReturnSurvivesReopenAndReplayWritesNothing` | 忽略首次成功返回后重开，具体样本仍在，旧 ID 重试零写入 |

5 个方法、10 个阶段场景全部通过。故障包装器在真实 `updateData` 之前抛出 IOException，或在其完成后暂停返回，不替代编码、文件存储或目录计算；未模拟磁盘耗尽、进程崩溃或断电。启动清理完成后才设置故障及记录旧请求计数基线。真实适配器不在开关映射中保存 false 项，关闭以 `isAppEnabled == false` 和仅保留其他已开启目标验证。

## 测试边界与限制

[`CaptureSaveFixtures.kt`](../../app/src/test/kotlin/com/zhenghuaizhi/flashskip/testing/CaptureSaveFixtures.kt) 使用手写存储端口替身模拟单次原子提交，记录写入尝试、提交顺序和提交瞬间的两份状态。测试调度器、虚拟时钟和 `CompletableDeferred` 屏障控制顺序，没有真实休眠或生产故障注入入口。目录由真实 `RuleCandidateAnalyzer` 与 `RuleValidator` 生成，发布时刻由测试控制。

- 中间取消点：关闭已提交，采集存储进入提交前屏障；取消传播、状态不丢且锁释放，新的调用协程能完成原 ID 重试。
- 结果丢失取消点：采集替身已发布状态并记录保存提交快照，返回前停在屏障；取消不会回滚，旧请求不会追加或修改数据。
- 开启交错：保存提交快照必须关闭；后续合法开启后最终快照允许开启。旧请求不得改变后续新会话或显式开启状态。
- 协调器替身的状态重读不单独证明真实磁盘持久化；该证据现由 T-10 的真实 DataStore/Codec 与关闭重建读取提供。
- T-09/T-10/T-11 已完成；T-12 已建好模拟器并执行目标页面探测，但 D-01～D-05 未完成。用户已决定不再推进；T-13 已汇总证据、限制及停止决定。

在已执行范围内未发现产品偏差，无最小运行代码修复建议。AC-01～AC-08 的必需自动化任务已通过，当前结论是“保存契约自动化验证通过，T-12 未完成且按用户要求不再推进”；不代表设备验收或发布就绪。


## T-12 现有设备与目标环境检查

2026-10-13 10:54～10:56（Asia/Shanghai）初查时，ADB/mDNS 列表为空、没有已配置 AVD。用户随后明确要求准备模拟器，并指定京东 App；11:23 又明确授权在新测试模拟器中接受京东隐私政策、但不登录账号。当时已解除无设备阻塞，但 D-01～D-05 未完成；随后按用户要求停止推进。以下为停止前的执行记录。

### 已完成的环境准备

| 项目 | 结果 |
| --- | --- |
| AVD | `FlashSkip_T12_API35`，Pixel 7 配置；Android 15 / API 35，Google APIs ARM64 镜像 r9，1080×2400 |
| 在线设备 | `emulator-5554`，ADB 状态 `device`；取证时以无窗口模式运行 |
| FlashSkip | `com.zhenghuaizhi.flashskip.debug`，0.1.0-debug / versionCode 1；生产源码基线 `3fa4dc02af7496dbf4e32be9043b038930ad8d89` |
| 构建与安装 | `:app:assembleDebug` 成功（全部任务 UP-TO-DATE）；debug APK 安装成功，无障碍服务已授权并绑定，首页显示“已准备好” |
| 京东 | `com.jingdong.app.mall`，16.0.70 / versionCode 102834，ARM64；未登录，通知权限拒绝 |
| APK 来源与校验 | 京东移动官网指向的应用宝页；APK SHA-256：`ef5a305cb31c4844772f68f3d7ce5ef278bc2bf41b5e4bc0b96c8e4624163e29`；签名证书与京东官方 CDN 旧包一致，APK 签名验证通过 |
| 初始及最终业务状态 | 全新安装，无已添加应用，样本数 0；最终活动会话已清除，未保存规则或开启目标自动执行 |
| 取证 | ADB 截图、FlashSkip 现有诊断日志、`run-as` 只读导出自身 DataStore；没有生产故障注入或测试框架改造 |

本地证据位于 [`app/build/reports/device-validation/2026-10-13/`](../../app/build/reports/device-validation/2026-10-13/)：[`environment.txt`](../../app/build/reports/device-validation/2026-10-13/environment.txt)、[`accessibility-final.txt`](../../app/build/reports/device-validation/2026-10-13/accessibility-final.txt)、[`jd-apk-provenance.md`](../../app/build/reports/device-validation/2026-10-13/jd-apk-provenance.md)。这些是忽略的本地构建产物，清理目录后不保留。SDK/AVD 安装在本机 Android 默认目录，不属于提交内容。

环境恢复过程中，系统镜像通过 Android Studio 自带 SDK Manager 及主机代理下载。新建 AVD 的镜像相对路径已校正，未修改其他设备。最初使用 UIAutomator 读取界面会暂时中断无障碍服务，因此正式采集阶段改用截图和现有日志，不再使用 UIAutomator；此干扰不归类为产品缺陷。

### 实际页面探测与限制

| 时间 / 场景 | 实际结果与证据 |
| --- | --- |
| 11:21～11:22，京东基础浏览模式冷启动 | 会话 `1791861716575` 正确识别京东，进入 `OBSERVING_CLICK`；所取截图仅见启动标识和首页，没有可手动关闭的开屏广告。最终 `CLICK_TIMEOUT`，样本 0、无待保存载荷。诊断共 62 次扫描、57 次完整扫描，关闭语义及有效关闭候选均为 0。见 `13-jd-cold-*.png`、`15-capture-return-capture-state.json` |
| 11:23，完整模式准备 | 初次同意隐私政策操作曾被自动审批以缺少明确同意为由拒绝；收到用户明确授权后执行成功。进入登录活动后按返回退出，没有登录；随后进入完整首页并拒绝通知权限 |
| 11:25～11:26，完整模式冷启动并采集 | 会话 `1791861926282` 正确识别京东，进入 `OBSERVING_CLICK`；截图仍为启动标识和首页，最终 `CLICK_TIMEOUT`，样本 0、无候选。诊断 56 次扫描均未完整完成，不能用诊断中候选为 0 证明整页不存在候选；结合截图只能确认本次未获得可重复开屏关闭路径。见 `22-jd-full-cold-*.png`、`24-fullmode-return-capture-state.json` |
| 11:26，尝试取消入口 | 新会话 `1791861979796` 在进入目标前返回 FlashSkip，按现有行为变为 `RETURNED_BEFORE_TARGET`。这是返回过早失败，不是可保存会话的取消证据，不计 D-04 通过。见 `25-before-cancel-capture-state.json` |
| 11:27，完整模式追加冷启动探测 | 8 张逐秒截图仍未见开屏关闭按钮。该次未成功新建活动采集会话，仅计页面探测；首页出现的营销浮层不作为启动广告替代。见 `26-jd-full-repeat-*.png`、`27-full-repeat-state-capture-state.json` |
| 11:28，结束本轮检查 | 点击“关闭提示”，确认 `activeSession == null`、`samples == []`；FlashSkip 返回已准备好、未添加应用状态。见 `29-final-ready.png`、`29-final-ready-capture-state.json` |

[冷启动截图索引](../../app/build/reports/device-validation/2026-10-13/cold-start-contact-sheet.jpg) 包含上述三组页面探测。离散截图不能证明从未出现短暂广告；本轮结论只限于未获得足以执行验收的可重复页面。未通过修改数据库制造规则、修改京东页面或更改 FlashSkip 采集算法来替代真实场景。

### 设备分项结果（均未完成，已按用户要求停止）

| 步骤 / 对应 AC | 当前结果 | 具体剩余阻塞 |
| --- | --- | --- |
| D-01 / AC-01 | Blocked / E2 Attempted | 设备已可采集京东，但未获得真实关闭点击样本；不能执行保存、核对新增及默认关闭 |
| D-02 / AC-07 | Blocked / E2 Attempted | 未形成 `AWAITING_CONFIRMATION` 候选；缺少确认前不保存、确认后保存的设备证据 |
| D-03 / AC-06 | Blocked / E2 Attempted | 无可保存样本，不能验证真实目录发布及保存后显式开启 |
| D-04 / AC-05 | Blocked / E2 Attempted | 已验证失败提示可清除且样本保持 0，但未进入可保存会话；连续保存及放弃保存均未验证 |
| D-05 / AC-02、AC-03 暂停集成 | Blocked / E2 Attempted；暂停效果不可判定 | 无已启用可执行规则及相同页面上的自动点击正向对照；不得把未观察到点击当作暂停成功 |

停止时缺少的证据为可重复启动广告关闭路径、D-02 的实际候选路径及 D-05 的非采集自动点击正向对照。用户已明确要求不再排查或推进，这些仅为未覆盖范围，不是后续任务。本轮未发现可证实的保存契约产品偏差；完整模式扫描不完整保留为诊断限制，不在本任务中调整算法。

## T-13 分项汇总

| 验证层 | 结论 | 证据与来源 | 剩余限制 |
| --- | --- | --- | --- |
| AC-01～AC-08 必需自动化 | Pass / E4 Broad | 复用 10:51 完整 JVM 运行：75 例、0 失败/错误/跳过；测试源码及生产源码未改 | 不代表设备操作已通过 |
| 设备环境准备 | 已完成 | Android 15 模拟器在线、两款应用已安装、无障碍已准备好、实际识别到京东 | 本轮为模拟器，未覆盖实体手机 |
| D-01～D-05 设备集成 | 未完成；按用户要求停止（历史证据 E2 Attempted） | 已执行京东启动页面探测及采集入口检查；采集超时、零样本有截图与持久化状态 | 缺少可重复广告、候选及自动点击正向对照；设备结论风险 Medium，不再安排后续验证 |
| 运行代码与修复 | 未修改；无已证实保存契约偏差 | `git diff HEAD --exit-code -- app/src/main app/build.gradle.kts gradle` 返回 0 | 不提出推测性代码修复 |

本轮构建并安装 debug APK，未重跑未改变的 75 例自动化测试。T-13 已完成收尾汇总；T-12 未完成且按用户要求不再推进，不据此宣称设备验收或发布就绪。
