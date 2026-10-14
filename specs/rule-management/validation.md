# 验证记录：SDD-007

状态：P4 最小生产修复、本地及 PR 远端契约验收通过。本包 47 例全部通过，修复前失败记录保留。

## 修复后完整本地验收

用户后续授权按已确认 P4 政策修复最小生产问题，保留全部 574 个测试身份和预期并补必要回归。基线 main `40e6080` 加本轮未提交改动，执行日期 2026-10-14。该本地执行时点尚未提交或运行远端 CI；后续 PR 验收见下节，没有独立评审或独立 QA。

完整 49 个测试类、594 例全部通过，0 失败／错误／跳过。修复前 574 个完整身份逐项保留并通过，包括 28 个原红例；其余旧测试源码不变，既有服务类扩展了夹具和 5 个方法，原 14 个方法体逐字保留。新增回归 20 例：5 JVM、1 Robolectric Codec、9 Robolectric 真实 DataStore（含首页组合）、5 Robolectric 真实服务／AppContainer／DataStore 集成。

相对主干 P4 共增加 115 例：38 JVM、24 Robolectric Codec、48 Robolectric 真实 DataStore、5 Robolectric 真实服务集成。既有 P3 的 14 个服务集成身份是基线回归，不能再次计入这 115 例。服务新增用例由真实引擎及 ACTION_CLICK 正控制证明恢复可用，仍不是设备或系统事件投递证据。

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --rerun-tasks --no-build-cache --continue --console=plain
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts/ci/tests -v
PYTHONDONTWRITEBYTECODE=1 python3 scripts/ci/check_specs.py
PYTHONDONTWRITEBYTECODE=1 python3 scripts/ci/check_test_results.py --require-artifacts
```

Gradle 返回 0，56 个任务实际执行；debug 构建、28 个检查器测试、规格及必需身份／产物门禁全部通过。Lint 0 错误、12 个既有警告，未顺带升级依赖或修改构建。两个 Codec 的编码端保持原样，磁盘键名、v2 格式和历史缺省保持不变。

证据在本机忽略目录 `app/build/reports/ci/p4/repaired-final/`：Gradle、JUnit XML/HTML、Lint、检查器／规格／产物日志、summary、574 修复前清单、594 完整清单及源码／配置指纹。`full/` 保留 574 例／28 失败的修复前证据；`repaired-initial/` 保留原 P4 95 例通过，`repaired-regression/` 保留 574 例通过，`repaired-added-compile/` 保留新增测试导入错误日志（已修正），`repaired-added/` 保留新增回归及既有服务专项通过。新检出不含忽略产物，须重跑。

## 实施与兼容边界

新增只在内存传递的 `StorageRead`，两个 store 端口新增带默认实现的 `reads` 属性，原命令读取／更新签名兼容。可展示的局部恢复值与可执行／可写状态分开；未知原文、坏会话和读取 IO 不再冒充健康空库。长期观察遇 IO 先发不可用，再每 1 秒重订阅；取消和程序错误不重试。这是观察恢复间隔，不改变采集或动作业务期限。

命令读取显式失败；写入在 DataStore 事务内确认可用之后才运行变换。Codec 独立解析条目和可选诊断，容量应用于合法记录。坏活动会话保留有效样本供展示，但不发布可执行规则；完成阶段必需载荷缺失或类型错误也按坏会话处理。目录、首页、诊断观察和服务使用可用性信息，服务在两库均有效前暂停引擎并拒绝事件。

删除确认使用内部 `RuleDeletionRequest`，绑定目标范围快照及全删除开关集合；重试在协调器同一锁内验证后才停用。自身已提交或返回不确定的停用可重试，外部新数据／重新启用使旧请求过期；无关目标和历史记录不影响单目标删除。最终采集写入再次校验范围，保护不持有操作锁的存储侧变化。没有跨库事务、回滚、新存储格式或恢复界面。

## 本包当前追踪

本包 47 例全部通过：原 42 例及 RuleDeletionRetryRegressionTest 的 5 例。下方历史 AC／方法映射继续有效，原 8 个失败现已全部关闭。

| AC | 修复／回归入口与证据 |
| --- | --- |
| AC-01、AC-02 | 原启停、目录滞后、单目标隔离、失败、幂等和真实重开全部通过 |
| AC-03 | 原首／中／末停用失败、最终删除失败和跨库重开正例全部通过；原读取 IO／未知版本仍继续删除的三个反例关闭 |
| AC-04、AC-05 | 新增 `waitingRetryChecksChangedDataInsideOperationLockBeforeAnyDisable`、`storageSideMutationBetweenDisableAndDeleteCannotLoseNewSample`，验证等待锁后和最终写入内的重新校验 |
| AC-05 | 原五个数据变化／残留开关变化反例通过；新增 `successfulPartialDisableThenExternalReenableInvalidatesAllRetry`、`lostDisableReturnMayRetryItsOwnCommittedSwitchWithoutRollback`、`lostFinalDeleteReturnCannotDeleteLaterReplacementData` |
| AC-06 | 原 STORAGE／STALE、持续失败、新操作替换、重复输入及作用域取消全部通过；补充首页不可用订阅和生命周期回调见 SDD-006 当前追踪 |

新增入口：[RuleDeletionRetryRegressionTest](../../app/src/test/kotlin/com/zhenghuaizhi/flashskip/application/rulecapture/RuleDeletionRetryRegressionTest.kt)。完整身份见 [必需清单](../../.ci/required-tests.json)。

## 当前剩余限制

没有设备、Compose 控件、物理磁盘损坏、系统杀进程或断电证据；真实存储与 Robolectric 服务不替代这些层级。没有新增恢复界面或跨进程删除重试。SDD-001/T-12 未完成且停止，P5 未启动；本轮服务／首页改动仅为 P4 不可用状态的必要消费链修复。

## PR 与远端 Linux 验收

2026-10-14 用户续授权新分支、提交、推送、创建 PR、等待 CI、下载核验报告与回写证据；保持 PR 未合并。此前“没有提交／远端 CI”仅记录本地验收时点。[PR #6](https://github.com/Mr414Zheng/flash_skip_android/pull/6) 的产品 head 为 `da412ed8ed15fb1af489174f519bbb5099f2b1c8`，分支 `codex/p4-persistence-rule-management`。

[CI 38503118760](https://github.com/Mr414Zheng/flash_skip_android/actions/runs/38503118760) 的 Specs、Android 均成功，49 类 594 例全部通过，0 失败／错误／跳过；已逐项核对全部必需身份、原 574 身份及 28 原红例。P4 的 115 例仍分为 38 JVM、24 Robolectric Codec、48 实际 DataStore、5 真实服务／AppContainer／DataStore 集成，不扩大为设备证据。28 个检查器、34 份规格／9 编号与 PR 检查、debug 构建及产物门禁通过；Lint 0 错误／15 警告。

两组报告已下载，ZIP 大小及 SHA-256 与 GitHub artifact digest 一致，JUnit／HTML、Lint、环境、门禁及构建日志已核验。environment 的测试合并提交 `bc4b5ee6583aaa6cad2ac0150ec876603c8124f7` 父提交为 main `40e6080` 与上述产品 head。完整元数据、摘要、文件哈希及下载边界见 [P4 CI 记录](../ci.md#p4-pr-与远端-linux-ci-验收)，本机原始证据在忽略目录 `app/build/reports/ci/p4/remote-38503118760/`。

本轮证据回写仅改文档，其后 head 需要单独核对最新 CI；上述实测数据只归属产品 head。工作流没有上传 APK，下载内容为报告；无设备、Compose 控件、独立评审或独立 QA 结论。T-12 继续停止，P5 未启动，PR 保持未合并。

## 修复前历史记录（以下不代表当前状态）

状态：规格、离线测试和本地证据交付完成，契约验收未通过。本包 42 例，34 通过、8 失败，零错误／跳过。见 [规格](spec.md)、[计划](plan.md)、[任务](tasks.md)。

## 基线与完整执行

2026-10-14 本地 main `40e6080889cc4a137b1472a5f185d5fcfff38b9a` 加未提交 P4 文档、测试及必需身份清单。生产代码、公开 API、持久化格式、依赖、构建与 CI 工作流无修改；没有提交、推送、PR 或远端 CI。本次自检不是独立评审或 QA。

全量 47 个类、574 例：546 通过、28 失败、0 错误／跳过。逐项核对原 479 个完整身份全部存在且通过，未改原测试。新增 6 个类、95 例（67 通过、28 失败）：33 JVM（5 失败），23 Robolectric SDK 36 Codec 组件（10 失败），39 Robolectric SDK 36 真实 DataStore（13 失败）；后二者合计 62 例。只有最后 39 例采用真实文件存储，不把 JVM 替身称为存储证据。

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --rerun-tasks --no-build-cache --continue --console=plain
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts/ci/tests -v
PYTHONDONTWRITEBYTECODE=1 python3 scripts/ci/check_specs.py
PYTHONDONTWRITEBYTECODE=1 python3 scripts/ci/check_test_results.py --require-artifacts
```

Gradle 返回 1，原因是 28 个契约反例；56 个任务实际执行。debug 构建成功，Lint 0 错误、12 警告。28 个 CI 检查器测试与规格检查通过；必需身份／产物门禁返回 1，准确阻断这 28 个失败，无缺失身份或构建／Lint 产物错误。门禁未降低，不把测试失败隐藏为整体通过。

原始证据在本机忽略目录 `app/build/reports/ci/p4/full/`：完整 Gradle 日志、JUnit XML/HTML、Lint、summary、479 基线与 574 必需身份清单及源文件指纹。`preliminary/` 保留第一批 46 例／20 失败，`management/` 保留第二批 40 例／8 失败。新检出不含这些忽略产物，须重跑；不提交原始报告或合成原始通过结果。

## 本包测试组织

| 测试类 | 用例 | 通过 / 失败 | 层级 |
| --- | --- | --- | --- |
| [RuleManagementContractTest](../../app/src/test/kotlin/com/zhenghuaizhi/flashskip/application/rulecapture/RuleManagementContractTest.kt) | 18 | 18 / 0 | JVM / 真实协调器＋替身存储 |
| [HomeViewModelManagementTest](../../app/src/test/kotlin/com/zhenghuaizhi/flashskip/ui/home/HomeViewModelManagementTest.kt) | 15 | 10 / 5 | JVM / ViewModel＋真实协调器＋替身存储 |
| [RuleManagementPersistenceTest](../../app/src/test/kotlin/com/zhenghuaizhi/flashskip/data/preferences/RuleManagementPersistenceTest.kt) | 9 | 6 / 3 | Robolectric SDK 36 / 真实协调器＋目录＋DataStore |

## AC 与任务追踪

| AC / 任务 | 代表用例与实际入口 | 结果 |
| --- | --- | --- |
| AC-01 / T-02 | `disablingIsAllowedDuringBlockingCaptureWithoutRemovingEvidence`、`enableQueuedBehindDeleteRejectsStaleCatalogWithoutReenabling`；既有启用门禁回归 | 通过；不重复复制 P2 和保存门禁矩阵 |
| AC-02 / T-02、T-03 | `targetDeleteDisablesBeforeRemovingOnlyTargetPayload`、`otherTargetDeletePreservesActiveSessionAndUnrelatedEvidence`、`targetDisableFailureLeavesAllCaptureAndSwitchesUntouched`、`captureDeleteFailureKeepsTargetDisabledAndOriginalPayload`、`deletingMissingTargetIsIdempotentAndPreservesOthers`、`targetDeleteSurvivesReopenAndPreservesOtherTargetAndRecords` | 通过 |
| AC-03 / T-02、T-03 | `allDeleteIncludesOrphanSwitchAndSessionOnlyTarget`、`allDeleteFirst/Second/LastDisableFailure*`、`allDeleteFinalWriteFailureLeavesEveryTargetDisabledAndAllPayload`、`secondDisableFailurePersistsPartialStateAndRetryCompletesAfterReopen`、`finalDeleteWriteFailureRetainsPayloadAfterReopen` | 正常和故障提交顺序通过；读取 IO／未知版本三个管理入口反例失败 |
| AC-04 / T-02、T-03 | `queuedSaveAfterDeleteCannotRestoreDeletedSession`、`queuedDeleteAfterSaveRemovesJustSavedSample`、`enableQueuedBehindDeleteRejectsStaleCatalogWithoutReenabling`、三个 JVM 取消入口及两个真实删除取消／重开入口 | 通过；验证单库提交与操作锁，不承诺跨库事务 |
| AC-05 / T-02 | `changedTargetSamples/Session/EvidenceInvalidate*`、`newTargetInvalidatesOldAllDeleteRetry`、`newOrphanSwitchInvalidatesOldAllDeleteRetry` | 五个变化范围反例失败，当前旧重试删除了新数据；未变、自身部分停用、其他目标／记录变化以及新确认正控制通过 |
| AC-06 / T-02 | `newerSuccessfulOperationClearsOldDeleteRetry`、`newerFailedOperationReplacesOldDeleteRetry`、`duplicateAndConflictingInputsDuringDeleteAreNotQueued`、`viewModelCancellationDoesNotTurnPartialDeleteIntoSuccess`、`repeatedStorageFailureRetainsRetryAndNeverReportsSuccess` | 通过；仅 ViewModel，不代表 Compose |

完整方法身份见 [必需清单](../../.ci/required-tests.json)，本轮实测状态见原始 XML；表内方法前缀仅用于归组，不替代完整身份。

## 本包失败身份

以下全部为断言失败，已核对为当前实现与确认政策的差距；没有编译、测试夹具或运行环境错误。每个反例均通过真实生产业务／Codec／store 入口，不在替身里实现目标政策。

- `RuleManagementPersistenceTest.captureReadFailurePreventsAllDeleteInsteadOfTreatingDataAsEmpty`
- `RuleManagementPersistenceTest.unknownCaptureVersionCannotBeDeletedThroughManagementEntry`
- `RuleManagementPersistenceTest.preferencesReadFailurePreventsDeletingCaptureWhileOrphanRemainsEnabled`
- `HomeViewModelManagementTest.changedTargetSamplesInvalidateOldDeleteRetry`
- `HomeViewModelManagementTest.changedTargetEvidenceInvalidatesOldDeleteRetry`
- `HomeViewModelManagementTest.newOrphanSwitchInvalidatesOldAllDeleteRetry`
- `HomeViewModelManagementTest.newTargetInvalidatesOldAllDeleteRetry`
- `HomeViewModelManagementTest.changedTargetSessionInvalidatesOldDeleteRetry`

## 最小修复建议及限制

当前反例汇总为四类：业务条目／记录可选诊断的隔离；不可用原文防覆盖与未知版本自动执行门禁；读取 IO／坏会话的显式不可用状态及管理阻断；删除重试范围失效。按 [计划](plan.md) 分层修复，需后续生产修复授权，不改测试预期来消除红例。

真实 DataStore 测试使用现有内部构造、两个独立文件及自有 Job，关闭并等待完成后重开。故障注入在真实 updateData 前／后或订阅读取边界，不替代编码、过滤、文件存储或协调器决策。注入读取 IO 不是文件系统物理损坏；坏 JSON 是 Preferences 中的值损坏，不等同于 protobuf 文件损坏。

既有 14 例 Robolectric 真实服务／AppContainer／DataStore 集成随基线全部重跑通过，但不构成新增 P4 不可用状态的服务门禁证据。本轮新增管理集成不启动真实服务；未知版本自动执行只验证 store 动作安全查询边界，未新增真实点击事件验证。没有 Compose 控件、真实 Android 系统事件、断电／杀进程、设备或远端 CI 证据。SDD-001/T-12 未完成且继续停止，P5 未启动。
