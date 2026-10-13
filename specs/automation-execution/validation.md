# 验证记录：自动执行与动作确认

规格：[spec.md](spec.md)；任务：[tasks.md](tasks.md)。状态：**三项跨包新增政策已审定并获明确生产修复授权，P2 本地及远端 CI 验收通过**。本包专项当前 94 例全部通过、0 失败/错误/跳过。其余条款沿用基线行为与默认参数，本轮不扩大审定范围；不代表设备或发布验收。

## 远端 CI 验收（当前）

已按用户续授权提交并推送，创建 [PR #3](https://github.com/Mr414Zheng/flash_skip_android/pull/3)，保持 OPEN 且不合并。生产及测试提交为 `3a4b360253a70c82f526e734ce81426571863968`；[CI 38374303555](https://github.com/Mr414Zheng/flash_skip_android/actions/runs/38374303555) 的 Specs、Android 均成功，时间为 2026-10-13 17:09:30～17:12:42（Asia/Shanghai）。

已下载核验两组 artifact：完整 201 例及 28 个检查器测试全部通过，0 失败/错误/跳过；201 个用例身份与必需清单逐项一致，18 份文档、9 个编号及真实 PR 正文检查通过；debug 构建成功，56 个 Gradle 任务全部执行，Lint 0 错误、15 警告。SDK 36 Robolectric 证据仍不等于设备验证。

实际所测 PR 合并提交为 `df16b90426a7e738fa52da922073d39fba3fa03f`；工具链、父提交、警告分布、下载路径和保留期限见 [CI 远端记录](../ci.md#p2-pr-远端验收与证据回写)。本次仅回写文档，后续提交的结果以 PR 最新 head 检查为准。下方未提交、无远端证据等说明保留为本地实施历史；当前 P2 本地及远端 CI 均已通过，未改依赖或远端设置，SDD-001/T-12 保持未完成且停止。

## 修复后本地执行与证据

2026-10-13 16:57:08～16:57:17（Asia/Shanghai）执行修复后完整测试；代码状态为 main `ad01dc7` 加未提交 P2 文档、测试、必需用例清单和 6 个生产文件的最小修复。完整 Gradle 命令与修复前记录一致，使用 `--rerun-tasks --no-build-cache`，耗时 34 秒、全部 56 个任务实际执行。

| 检查 | 当前实际结果 |
| --- | --- |
| 完整 JVM/Robolectric | 201 例全部通过、0 失败、0 错误、0 跳过 |
| SDD-001 回归 | 原 75 个完整用例身份全部保留并通过，原 6 个测试/支撑文件未改动 |
| SDD-002 专项 | 32/32 通过 |
| SDD-003 专项 | 94/94 通过，包括新增 4 个目录挂起边界用例 |
| P2 新增确定性领域/集成 | 90/90 通过，包括真实目录与引擎集成 |
| P2 新增 Robolectric | SDK 36，36/36 通过；候选查找 10、动作证据 11、PageGateway 15 |
| CI 检查器 | 28 例通过、0 失败/错误/跳过 |
| 规格检查 | 18 份文档、9 个规格编号通过 |
| debug 构建 | `assembleDebug` 成功，APK 已生成 |
| Android Lint | 0 错误、12 警告；HTML/XML 已生成；未修改依赖或构建配置 |
| 报告检查 | `--require-artifacts` 通过；201 个必需身份无缺失、失败、错误或跳过，Lint 12 条警告 |
| 独立静态代码评审 | gpt-6-astra，high，对 6 个生产修复文件审阅，`no_findings`；不是独立运行 QA |

本轮本机证据已归档至忽略目录 `app/build/reports/ci/p2/repaired-final/`，包含 `junit/`、`html/`、Gradle、Lint、检查器、规格及报告检查证据；环境记录和文件指纹由本轮交付最后生成。修复前 `app/build/reports/ci/p2/final/` 保留，不能把旧失败报告当作修复后结果。必需清单当前为 21 个类、201 个完整身份，原 75 个必需身份逐项核对保持，原 22 个失败身份逐项对照本轮 XML 全部通过；失败、错误、跳过、身份缺失及产物缺失阻断未关闭。无新增提交、推送、PR、依赖或远端设置改动，SDD-001/T-12 继续停止。

## 修复前执行与证据（保留历史）

2026-10-13 16:37:07～16:37:14（Asia/Shanghai）执行修复前完整套件；代码状态为 `ad01dc7` 加本轮规格、测试与必需用例清单，生产代码、依赖及应用构建配置无差异。命令：

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --continue --rerun-tasks --no-build-cache --console=plain
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts/ci/tests -v
PYTHONDONTWRITEBYTECODE=1 python3 scripts/ci/check_specs.py
PYTHONDONTWRITEBYTECODE=1 python3 scripts/ci/check_test_results.py --require-artifacts
```

| 检查 | 实际结果 |
| --- | --- |
| 完整 JVM/Robolectric | 197 例：175 通过、22 失败、0 错误、0 跳过；Gradle 因测试失败退出 1 |
| SDD-001 回归 | 原清单 75 个完整用例身份全部存在并通过，没有改动这 6 个测试/支撑文件 |
| P2 新增确定性领域/集成 | 86 例：64 通过、22 失败；包括真实目录与引擎集成，不含 Android 设备 |
| P2 新增 Robolectric | SDK 36，36 例全部通过；候选查找 10、动作证据 11、PageGateway 15 |
| CI 检查器 | 28 例通过，无失败/错误/跳过 |
| 规格检查 | 18 份文档、9 个规格编号通过 |
| debug 构建 | `assembleDebug` 成功，APK 已生成；全部 56 个 Gradle 任务实际执行 |
| Android Lint | 0 错误、12 警告；HTML/XML 已生成；未修改或升级依赖 |
| 报告检查 | 正确失败：197 个必需身份无缺失，22 个失败用例使检查退出 1；没有关闭失败/跳过/缺失阻断 |

必需用例清单从原 75 例扩展至 21 个类、197 个完整身份，逐项对照真实 XML 后登记，原有 75 例保留。未创建 PR、未推送，不存在本轮 P2 远端 CI 通过结论。

本机证据存放在忽略目录 `app/build/reports/ci/p2/final/`：`junit/`、`html/`、`gradle.log`、`lint.xml`、`lint.html`、`checker-tests.log`、`report-check.txt`；最终文件指纹与工具链见同目录 `environment.txt` 和 `file-sha256.json`。全新检出不包含这些产物，可用上述命令重新生成。早期分阶段报告保留在其相邻子目录，作为修复前代码状态的依据。

Lint 警告分布：OldTargetApi 1、AndroidGradlePluginVersion 1、GradleDependency 6、NewerVersionAvailable 1、ObsoleteSdkInt 1、MonochromeLauncherIcon 2。本轮本地 12 条与 P1 远端历史 15 条是不同环境/时点的报告；不将差额当作生产修复或回归结论。

## 本包逐用例追踪

下列结果均为修复后本轮执行结果；修复前失败场景和计数保留于历史记录及 AE-01/AE-02。表中 `acXX` 对应本包同号 AC；复合前缀同时覆盖多个条件。`SelectorMatcherTest` 的 AC-03 属 SDD-002，其余自动执行及平台类属于 SDD-003。通过表示已列场景通过，不是穷尽状态空间或全项目覆盖声明。

### ActionRetryPolicyTest

源码：[ActionRetryPolicyTest](../../app/src/test/kotlin/com/zhenghuaizhi/flashskip/domain/automation/ActionRetryPolicyTest.kt)。

| 方法 | 结果 |
| --- | --- |
| `ac06_retryRequiresNodeMethodEvidenceUniquenessRevisionAndRemainingAttempt` | 通过 |
| `ac06_retryWaitsForResponseAndStableTargetWithExactThresholds` | 通过 |
| `ac06_observationDeadlineIsExclusiveAndLastAcceptedActionControlsWait` | 通过 |

### AutomationCatalogIntegrationTest

源码：[AutomationCatalogIntegrationTest](../../app/src/test/kotlin/com/zhenghuaizhi/flashskip/domain/automation/AutomationCatalogIntegrationTest.kt)。

| 方法 | 结果 |
| --- | --- |
| `ac03_realCatalogDeletionWhileQuerySuspendedPreventsClick` | 通过 |
| `ac03_ac08_realCatalogDowngradePreservesAcceptedButUnconfirmedRecord` | 通过 |

### AutomationEngineActionTest

源码：[AutomationEngineActionTest](../../app/src/test/kotlin/com/zhenghuaizhi/flashskip/domain/automation/AutomationEngineActionTest.kt)。

| 方法 | 结果 |
| --- | --- |
| `ac02_unreadableAndTraversalLimitedSelectorsAllowOtherSelectorsAndRetry` | 通过 |
| `ac01_queryReturningAtAbsoluteDeadlineCannotSubmit` | 通过 |
| `ac07_disableWhileQuerySuspendedPreventsSubmission` | 通过 |
| `ac04_onlyUnsupportedClickFallsBackAndAcceptedOrCompletedGestureIsNotClosed` | 通过 |
| `ac04_suspendedGestureTimesOutAndLateCompletionCannotRestoreSession` | 通过 |
| `ac02_ambiguityOrPageChangeStopsWithoutActionAndDoesNotRearm` | 通过 |
| `ac04_rejectedCancelledAndTimedOutGesturesAreNeverAcceptedRecords` | 通过 |
| `ac04_notActionableNeverFallsBackToGestureButCanReactToLaterEvent` | 通过 |

### AutomationEngineCatalogTest

源码：[AutomationEngineCatalogTest](../../app/src/test/kotlin/com/zhenghuaizhi/flashskip/domain/automation/AutomationEngineCatalogTest.kt)。

| 方法 | 结果 |
| --- | --- |
| `ac03_removalBetweenUnsupportedClickAndGesturePreventsFallback` | 通过 |
| `ac03_displayMetadataAndOtherTargetChangesDoNotInvalidate` | 通过 |
| `ac03_removalAfterAcceptedActionStopsRetryAndRecordsUnconfirmed` | 通过 |
| `ac03_removedRuleCannotTurnStableAbsenceIntoClosedRecord` | 通过 |
| `ac03_removalWhileQuerySuspendedPreventsFirstClick` | 通过 |
| `ac03_invalidatedSessionDoesNotRearmWhenRulesReturnInSameForeground` | 通过 |
| `ac03_executionFieldChangeInvalidatesBeforeClick` | 通过 |
| `ac03_reorderedRulesInvalidateOriginalPriorityBeforeClick` | 通过 |
| `ac03_ac08_removalDuringSuspendedFirstActionPreservesReturnedAcceptance` | 通过 |
| `ac03_ac08_removalDuringSuspendedRetryPreservesAcceptedActionWithoutFurtherWork` | 通过 |
| `ac03_removalDuringSuspendedObservationPreventsCloseOrRetry` | 通过 |
| `ac03_observedRemovalThenRestorationCannotReviveSuspendedQuery` | 通过 |

### AutomationEngineInvalidationTest

源码：[AutomationEngineInvalidationTest](../../app/src/test/kotlin/com/zhenghuaizhi/flashskip/domain/automation/AutomationEngineInvalidationTest.kt)。

| 方法 | 结果 |
| --- | --- |
| `ac08_recordFailureDoesNotRearmTerminalSession` | 通过 |
| `ac08_recordCancellationPropagatesAndEngineRejectsFurtherBarriers` | 通过 |
| `ac07_engineCloseBoundsUnconfirmedRecordCleanup` | 通过 |
| `ac07_versionChangeInvalidatesPendingQueryWithoutRearm` | 通过 |
| `ac07_closeCancelsSuspendedQueryAndLateResultCannotAct` | 通过 |
| `ac07_ac08_acceptedActionIsPreservedAsUnconfirmedAcrossEachInvalidation` | 通过 |
| `ac07_pauseAcknowledgementStopsPendingActionsAndResumeAloneDoesNotAct` | 通过 |
| `ac07_leavingTargetCancelsScheduledQueryAndOldTimerCannotAct` | 通过 |
| `ac07_mailboxOverflowDuringSuspendedQueryInvalidatesQueuedAction` | 通过 |

### AutomationEngineObservationTest

源码：[AutomationEngineObservationTest](../../app/src/test/kotlin/com/zhenghuaizhi/flashskip/domain/automation/AutomationEngineObservationTest.kt)。

| 方法 | 结果 |
| --- | --- |
| `ac05_ac08_acceptedClickNeedsStableAbsenceAndLaterEventBeforeClosedRecord` | 通过 |
| `ac08_unconfirmedActionDoesNotStartCooldown` | 通过 |
| `ac08_onlyConfirmedCloseStartsRuleCooldownAndOtherRulesRemainIndependent` | 通过 |
| `ac06_newEventAfterRetryCanSupportCloseButDeadlineStillStartsAtFirstClick` | 通过 |
| `ac06_eventBeforeAcceptedRetryCannotCloseAfterRetry` | 通过 |
| `ac06_changedPageIdentityPreventsRetryEvenIfTargetReportedPresent` | 通过 |
| `ac05_pageFallbackIsDelayedAndLimitedToTwoReads` | 通过 |
| `ac01_ac05_observationCannotExtendOriginalForegroundDeadline` | 通过 |
| `ac06_presentTargetCanBeRetriedOnlyOnceAndFailedRetryConsumesAttempt` | 通过 |
| `ac05_withoutLaterEventStableAbsenceRemainsUnconfirmedUntilDeadline` | 通过 |

### AutomationEngineSessionTest

源码：[AutomationEngineSessionTest](../../app/src/test/kotlin/com/zhenghuaizhi/flashskip/domain/automation/AutomationEngineSessionTest.kt)。

| 方法 | 结果 |
| --- | --- |
| `ac01_startupDelayUsesForegroundTimeAndExecutesAtThreshold` | 通过 |
| `ac01_disabledTargetAndMissingRulesDoNotQueryOrAct` | 通过 |
| `ac02_contentBurstUpdatesIdentityWithoutPostponingFirstQuery` | 通过 |
| `ac01_unknownOrDifferentVersionCannotStartBoundRule` | 通过 |
| `ac01_activityMustBeAllowedBeforeAnyQuery` | 通过 |
| `ac01_samePackageWindowChangeDoesNotExtendOrRearmExpiredSession` | 通过 |
| `ac01_expiredEntryOrDelayAtDeadlineCannotAct` | 通过 |

### PostActionObserverTest

源码：[PostActionObserverTest](../../app/src/test/kotlin/com/zhenghuaizhi/flashskip/domain/automation/PostActionObserverTest.kt)。

| 方法 | 结果 |
| --- | --- |
| `ac06_eventAfterRetryAcceptanceConfirmsWithoutExtendingOriginalDeadline` | 通过 |
| `ac05_evidenceChangeResetsStabilityAndDeadlineWinsAtEquality` | 通过 |
| `ac05_absenceNeedsTwoStableObservationsAndLaterEvent` | 通过 |
| `ac06_eventAtRetryAcceptanceCannotConfirm` | 通过 |
| `ac05_originalWindowRemovalRequiresWindowEventInsteadOfContentEvent` | 通过 |
| `ac05_presentAndInsufficientNeverConfirmClosed` | 通过 |
| `ac06_eventBeforeRetryAcceptanceCannotConfirmEvenAfterStableAbsence` | 通过 |

### AccessibilityActionEvidenceTest

源码：[AccessibilityActionEvidenceTest](../../app/src/test/kotlin/com/zhenghuaizhi/flashskip/platform/accessibility/AccessibilityActionEvidenceTest.kt)。

| 方法 | 结果 |
| --- | --- |
| `ac05_fullPageScanNeedsPriorContextAndCompleteAbsence` | 通过 |
| `ac05_removingOnlyButtonWithoutChangingContextDoesNotProveDismissal` | 通过 |
| `ac05_renamedTargetIsNotProofOfRemoval` | 通过 |
| `ac05_pageNodeDepthAndTimeLimitsRemainInsufficient` | 通过 |
| `ac05_incompleteLocalScanDoesNotAuthorizeRetryAsUnique` | 通过 |
| `ac05_freshOriginalTargetIsPresentAndUnique` | 通过 |
| `ac05_changedCompleteLocalRegionCanProveContainerRemoval` | 通过 |
| `ac05_hiddenTargetIsNotProofOfRemoval` | 通过 |
| `ac05_emptyIndexWithoutIndependentScanCannotProveAbsence` | 通过 |
| `ac05_wrongWindowOrRefreshFailureCannotProveAbsence` | 通过 |
| `ac05_unreadableSubtreeCannotProveAbsenceOrUniqueness` | 通过 |

### AccessibilityCandidateFinderTest

源码：[AccessibilityCandidateFinderTest](../../app/src/test/kotlin/com/zhenghuaizhi/flashskip/platform/accessibility/AccessibilityCandidateFinderTest.kt)。

| 方法 | 结果 |
| --- | --- |
| `ac02_depthBudgetPreventsUnboundedTraversal` | 通过 |
| `ac02_unreadableChildMakesFallbackUnavailableEvenWithKnownMatch` | 通过 |
| `ac02_ancestorConstraintIsRecheckedAfterRefresh` | 通过 |
| `ac02_refreshChangingTextCannotUseCachedSelectorMatch` | 通过 |
| `ac02_emptyIndexFallsBackToTreeAndFullSelectorControlsUniqueness` | 通过 |
| `ac02_multipleMatchingIndexedCandidatesAreAmbiguousAndCallbackNeverRuns` | 通过 |
| `ac02_refreshChangingPackageRejectsCachedMatch` | 通过 |
| `ac02_nodeBudgetPreventsUnboundedTraversal` | 通过 |
| `ac02_unreadableCandidateCannotProveUniqueOrAbsent` | 通过 |
| `ac02_elapsedScanBudgetProducesTraversalLimitNotAbsence` | 通过 |

### AccessibilityPageGatewayTest

源码：[AccessibilityPageGatewayTest](../../app/src/test/kotlin/com/zhenghuaizhi/flashskip/platform/accessibility/AccessibilityPageGatewayTest.kt)。

| 方法 | 结果 |
| --- | --- |
| `ac02_duplicateWindowMetadataAndUnreadableRootAreRejected` | 通过 |
| `ac04_clickSubmitsActionAndReturnsEvidenceWithoutClaimingClosed` | 通过 |
| `ac04_cancelledWaitIgnoresLateGestureCompletion` | 通过 |
| `ac04_gestureAcceptanceReturnsImmediatelyAndCallbackCannotChangeItsResult` | 通过 |
| `ac04_cancellationDuringScanPropagatesBeforeSubmission` | 通过 |
| `ac04_pageChangeDuringRefreshCannotSubmitAction` | 通过 |
| `ac04_waitingGestureDistinguishesCompletedAndCancelledCallbacks` | 通过 |
| `ac04_notActionableAndUnsupportedAreDistinct` | 通过 |
| `ac04_deadlineBeforeAndDuringScanPreventsClickAndGesture` | 通过 |
| `ac05_replacementWindowStillContainingTargetCannotProveClose` | 通过 |
| `ac04_selectedChildActsOnlyOnCapturedActionAncestor` | 通过 |
| `ac04_expectedOriginalEvidenceRejectsChangedTargetBeforeRetry` | 通过 |
| `ac02_ac04_wrongIdentityPackageOrWindowCannotQueryOrClick` | 通过 |
| `ac04_rejectedGestureHasNoAcceptedEvidence` | 通过 |
| `ac05_windowChangeAloneIsInsufficientButVerifiedRemovalHasSpecificProof` | 通过 |

## 已修复偏差及历史反例

### AE-01：目录失效未阻止旧会话动作或成功记录（已修复）

关联 SDD-003/AC-03、AC-07、AC-08、T-07；修复前 9 个失败反例（目录引擎 7、真实目录集成 2）。修复前已确认：查询挂起期间删除规则仍提交点击；优先级或执行字段变化后仍执行原规则；点击不支持后撤销规则仍提交手势；已接受动作后删除/降级目录仍从 1 次点击增加至 2 次；稳定消失观察期间撤销规则仍写 CLOSED（预期 ABANDONED）。修复前同前台恢复规则的用例在“失效后不点击”断言失败，后续离开再进入断言尚未到达；字段矩阵在 ID 变化首项失败。修复后同一方法完整执行通过，包括恢复后不复活、离开再进入和已列字段矩阵。

已实施最小修复：在引擎内比较当前目标/版本适用的有序可执行规则字段，排除展示元数据及其他目标；将目录变化纳入会话失效，并在首次点击、手势、补点及观察确认边界复核最新目录。挂起查询返回后仍须复核；失效不重新武装，已接受动作结算 ABANDONED，保留事实且不继续确认成功。复用当前目录端口和 actor 职责，不修改持久化模型或公共 API；处理返回结果时先保留已经发生的动作事实再结算失效。新增 4 个测试通过：挂起首次节点点击、手势 Accepted/Completed、接受补点保留 ABANDONED/actionAccepted/动作方式；挂起稳定消失或可补点观察返回后不写 CLOSED、不继续点击；观察到撤销后目录恢复也不能复活旧挂起查询，但离开再进入可建立新会话。独立 StateFlow 监听保存已观察到的失效；该证据不证明未投递给订阅者的瞬态撤销均可捕获。

### AE-02：补点前事件仍被用于补点后的关闭确认（已修复）

关联 SDD-003/AC-05、AC-06、T-07；修复前 3 个失败反例（观察策略 2、真实引擎 1）。策略输入首次接受 100ms、最近接受 900ms，支持事件为 899ms 或 900ms，消失证据在 1100～1300ms 稳定；修复前预期 Wait，实际 Confirmed。修复前引擎中首次点击 120ms、支持事件 121ms、补点 1170ms，之后没有新事件，实际仍于稳定消失后写 CLOSED。

已实施最小修复：`PostActionObserver` 以支持事件严格晚于 `lastActionAt` 为条件，原窗口移除仍要求窗口事件；保留首次接受确定的绝对观察期限，不因补点延长。旧事件、同刻事件、新事件三组当前通过；原窗口移除补充补点后窗口事件的相同时间边界，独立的新内容事件不能代替旧窗口事件。最多一次补点、失败消耗次数和绝对期限用例仍通过。

两项生产修复已按用户明确授权完成；本包 AC-03、AC-05/AC-06 及目录失效下 AC-07/AC-08 的已列反例均闭环。全套 201 例通过支持本地离线验收，不概括为全部设备或调度空间均已验证。

## 测试实施修正与剩余限制

- 首次运行使用系统默认 Java 时未找到运行时；随后使用仓库已记录的 Android Studio JBR。Gradle 缓存需要沙箱外访问，经工具审批后执行；最终不是环境阻塞。
- 平台测试初版出现 Shadow 状态跨用例残留及缓存窗口被正常 recycle 清空。已在每个夹具开始清理注入状态，并让测试服务返回独立窗口副本；仅修正测试夹具。最终 36 例全部通过，未把夹具问题计入产品偏差。
- 修复前同一方法中的输入矩阵在首个失败处停止，历史“失败”不代表后续每项均已运行；本轮修复后方法完整执行通过。未使用 `@Ignore`、真实休眠或预期异常掩盖三项政策失败。
- 单次确定性用例不能证明所有调度交错；目录会话更新用例验证没有额外发布，不宣称 CPU 重算次数已经被测量。Robolectric 及故障 Shadow 只验证适配器对受控输入的处理，不代表真实系统服务或 OEM 行为。
- 本轮完成 gpt-6-astra high 独立静态代码评审，6 个生产文件 `no_findings`；运行证据为实施者验证，未执行独立 QA。SDD-001/T-12 未完成且按用户要求继续停止，没有新增设备或 Compose UI 组件证据。
- 三项新增政策已审定并明确授权修复，其余沿用基线参数与行为。本轮本地离线验收通过，规格/报告检查通过且修复后证据已归档；未修改远端设置，未新增提交或推送，未产生本轮远端 CI 证据。
