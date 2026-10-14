# 验证记录：SDD-005：采集证据与目标识别

状态：五类政策最小修复及内部接缝已完成，本地契约验收通过。本包 82 例全部通过。

## 修复后本地验收

基线 main `34dc102` 加未提交的 P3 规格、测试与最小生产修复，2026-10-13 本地执行。用户后续授权五项政策修复及必要内部测试接缝。本轮修改 7 个既有生产文件并新增内部 AppContainerRuntime；公共构造默认装配、存储格式、依赖、应用构建配置、CI 工作流及远端设置保持不变。未提交、推送或创建 PR。

完整重跑 41 个测试类、479 例，全部通过，0 失败／错误／跳过。修复前 453 个完整身份全部保留并通过，包括原 201 例和原 42 个失败反例；本次增加 26 例。相对 main 共新增 20 个测试类、278 例：207 JVM 例、57 Robolectric SDK 36 组件例、14 Robolectric SDK 36 真实服务生命周期集成例。层级分别登记，后者不是设备证据。

完整执行 `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug --rerun-tasks --no-build-cache --continue --console=plain` 成功；28 个检查器测试、规格检查及必需身份／产物门禁通过，Lint 0 错误、12 警告。独立静态代码评审最初发现恢复写失败后后续发射可绕过门禁，已修复并补充成功重试／持续写失败两例，复审关闭且无新增 finding；这不代表独立 QA。

当前报告归档于本机忽略目录 `app/build/reports/ci/p3/repaired-final/`（Gradle、JUnit XML/HTML、Lint、检查器、规格、产物门禁、summary、完整清单及文件指纹）。`final/` 仍保留修复前 453 例／42 失败，未覆盖历史；`repaired-initial/` 保存测试夹具编译问题日志，`repaired-check/` 保存 479 例／1 个服务正控制失败的中间证据。新检出须重跑，不能依赖本机忽略产物。

### 本次修复与追踪

| 政策 / AC | 实施与运行证据 |
| --- | --- |
| CE-01 / AC-02 | 缺源点击的即时恢复与快照恢复要求明确且匹配的事件语义；原 3 个反例通过，新增明确提示即时恢复正例。独立被动候选发现不受此归因门槛替代 |
| CE-02 / AC-02、AC-04 | Probe 接受真实恢复及 PreClick 快照形成均要求单轮完整唯一扫描；原 2 个反例通过，新增有损重复结构不能伪造完整快照。定向阳性仍可保留候选线索 |
| CE-03 / AC-01 | 缺失 source.window 元数据保持未知／有限重试；原反例通过，完整元数据及显式非点击回退正例保留 |

本包组织 82 例全部通过：CaptureRuleSafetyPolicyTest 3、ClickSourceRecoveryMatcherTest 4、ObservedCandidateClickMatcherTest 6、AccessibilityCandidateRecheckTest 8、AccessibilityCaptureAdapterTest 11、AccessibilityCaptureProbeTest 7、AccessibilityCaptureWindowResolverLifecycleTest 11、AccessibilityPreClickRecoveryTest 7、AccessibilitySemanticDiffTrackerTest 10、CaptureCoverageCursorTest 5、CaptureRoundBudgetTest 4、CaptureWindowBoundaryPolicyTest 6。原 AC-01～AC-08 追踪身份继续有效；下方表中的失败仅描述修复前运行。

### 证据层级与剩余限制

Adapter、Probe、Resolver、Recheck 和协调器运行真实实现，Robolectric shadow 只控制 Android 输入与故障。新增真实 service/container/DataStore 生命周期集成的边界和 14 例结果见 [生命周期验证](../capture-lifecycle/validation.md#真实服务和实际存储证据)；它不等于本包全部点击分支都经过系统服务投递。Resolver recycle 调用也不能证明实际设备 Binder 生命周期。

本地 P3 约定范围内的契约验收通过。仍未专项覆盖 SemanticSnapshotter 全文清洗／截断、完整节点释放计数、真实系统触摸时序或诊断全局 sink 的全部异常路径。没有新增设备、Compose 控件或远端 CI 证据；SDD-001/T-12 未完成且继续停止。

## 修复前历史记录（以下不代表当前状态）

状态：规格与离线测试工作完成，契约验收未通过。本包测试组织 80 例，74 通过、6 失败；0 错误／跳过。规格见 [spec](spec.md)，任务见 [tasks](tasks.md)。

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
| [CaptureRuleSafetyPolicyTest](../../app/src/test/kotlin/com/zhenghuaizhi/flashskip/domain/rulecapture/CaptureRuleSafetyPolicyTest.kt) | 3 | 3 / 0 | JVM / 组件 |
| [ClickSourceRecoveryMatcherTest](../../app/src/test/kotlin/com/zhenghuaizhi/flashskip/domain/rulecapture/ClickSourceRecoveryMatcherTest.kt) | 4 | 4 / 0 | JVM / 组件 |
| [ObservedCandidateClickMatcherTest](../../app/src/test/kotlin/com/zhenghuaizhi/flashskip/domain/rulecapture/ObservedCandidateClickMatcherTest.kt) | 6 | 6 / 0 | JVM / 组件 |
| [AccessibilityCandidateRecheckTest](../../app/src/test/kotlin/com/zhenghuaizhi/flashskip/platform/accessibility/AccessibilityCandidateRecheckTest.kt) | 8 | 8 / 0 | Robolectric SDK 36 组件 |
| [AccessibilityCaptureAdapterTest](../../app/src/test/kotlin/com/zhenghuaizhi/flashskip/platform/accessibility/AccessibilityCaptureAdapterTest.kt) | 10 | 9 / 1 | Robolectric SDK 36 组件 |
| [AccessibilityCaptureProbeTest](../../app/src/test/kotlin/com/zhenghuaizhi/flashskip/platform/accessibility/AccessibilityCaptureProbeTest.kt) | 7 | 6 / 1 | Robolectric SDK 36 组件 |
| [AccessibilityCaptureWindowResolverLifecycleTest](../../app/src/test/kotlin/com/zhenghuaizhi/flashskip/platform/accessibility/AccessibilityCaptureWindowResolverLifecycleTest.kt) | 11 | 11 / 0 | Robolectric SDK 36 组件 |
| [AccessibilityPreClickRecoveryTest](../../app/src/test/kotlin/com/zhenghuaizhi/flashskip/platform/accessibility/AccessibilityPreClickRecoveryTest.kt) | 6 | 3 / 3 | Robolectric SDK 36 组件 |
| [AccessibilitySemanticDiffTrackerTest](../../app/src/test/kotlin/com/zhenghuaizhi/flashskip/platform/accessibility/AccessibilitySemanticDiffTrackerTest.kt) | 10 | 10 / 0 | JVM / 组件 |
| [CaptureCoverageCursorTest](../../app/src/test/kotlin/com/zhenghuaizhi/flashskip/platform/accessibility/CaptureCoverageCursorTest.kt) | 5 | 5 / 0 | Robolectric SDK 36 组件 |
| [CaptureRoundBudgetTest](../../app/src/test/kotlin/com/zhenghuaizhi/flashskip/platform/accessibility/CaptureRoundBudgetTest.kt) | 4 | 4 / 0 | JVM / 组件 |
| [CaptureWindowBoundaryPolicyTest](../../app/src/test/kotlin/com/zhenghuaizhi/flashskip/platform/accessibility/CaptureWindowBoundaryPolicyTest.kt) | 6 | 5 / 1 | JVM / 组件 |

## AC、任务与用例追踪

| AC / 任务 | 实际用例入口（完整身份见清单） | 结果与层级 |
| --- | --- | --- |
| AC-01 / T-02、T-03 | `CaptureWindowBoundaryPolicyTest` 6 例；`AccessibilityCaptureWindowResolverLifecycleTest` 11 例，包括 `duplicateWindowIdsAreRejectedEvenWhenActiveRootMatchesHost`、`samePackageEventSourceResolvesOwnedChildThroughVerifiedHostForeground`、`consumerExceptionStillReleasesRootAndWindowCopies` | 纯政策 1 失败；真实 resolver Robolectric 全通过；不含真实服务 |
| AC-02 / T-02、T-03、T-04 | `ClickSourceRecoveryMatcherTest`；`AccessibilityCaptureProbeTest`；`AccessibilityPreClickRecoveryTest`；Adapter 的 `ac02_directSourceFastClickBeforeTargetStateCommitIsCapturedWithoutCandidate` 与 `policy01_missingSourceAndEventSemanticsCannotAttributeImmediateRecoveryAsTrueClick` | 直接源/匹配/年龄/身份基线通过；缺源归因与覆盖共 5 失败，见下表 |
| AC-03 / T-03 | `CaptureRuleSafetyPolicyTest`；`AccessibilityCaptureAdapterTest.ac03_primaryCandidateWindowExpiresButVerifiedOtherApplicationWindowIsAllowed`；引用 SDD-002 稳定结构原测试 | JVM 与组件已列场景通过；被动 matcher 可在无事件提示时发现候选，不表示允许真实点击归因 |
| AC-04 / T-02、T-03 | `CaptureRoundBudgetTest` 4 例；`CaptureCoverageCursorTest` 5 例；Probe 的 `policy03_partialScanCannotRecoverARealClickAsUnique`、`ac04_unreadableChildPreventsUniquenessAndAbsentProof`；ResolutionLifecycle 的重试预算用例 | 共享预算、续扫/深度/不可读基线通过；部分扫描真实点击恢复政策失败 |
| AC-05 / T-03 | `AccessibilityCandidateRecheckTest` 8 例：存在/歧义/已验证缺失/未知、anchor 范围、空索引、节点改变/查询异常/身份变化 | Robolectric 通过；定向唯一仍不能替代快照形成时完整覆盖 |
| AC-06 / T-03 | `AccessibilitySemanticDiffTrackerTest` 10 例，包括 `ac06_directedCompleteScopeAbsenceNeedsStablePositiveHistory`、未知竞争与 65 条历史溢出；`RuleCaptureServiceUpdateTest.semanticDiffDeduplicatesCaps64AndReplacesOnlySameTargetActivityScope`、`semanticDiffKeepsOnlyLatest10TargetActivityScopes`、`semanticDiffSurvivesSessionReplacementButOldIdCannotOverwriteNewEvidence` | 组件及真实协调器＋替身存储通过；末 3 例计入 SDD-004 测试组织，不重复计数 |
| AC-07 / T-03、T-04 | `ObservedCandidateClickMatcherTest` 6 例；Adapter 的 `ac07_clickBeforeCandidateCommitWaitsThenUpgradesSameFrozenEvidence`、写失败、取消和重复事件用例 | 完整身份/稳定关联/语义与提交顺序通过；截止拒绝由 SDD-004 红例覆盖 |
| AC-08 / T-04 | Adapter 的 `ac08_observedCandidateIsPendingAndNeverSavesOrChangesTargetSwitch` 及点击集成中的样本/开关断言；SDD-004 返回诊断提供者异常/取消测试 | 所列业务副作用通过；没有自动动作后关闭确认或真实设备结论 |

## 已复现偏差与最小修复建议

| 编号 | 真实失败方法 | 当前结果与最小改动边界 |
| --- | --- | --- |
| CE-01 / AC-02 | PreClickRecovery 的 `policy01_missingEventSemanticsCannotRecoverTrueClickFromSnapshot`、`policy02_conflictingEventSemanticsCannotRecoverDismissSnapshot`；Adapter 的 `policy01_missingSourceAndEventSemanticsCannotAttributeImmediateRecoveryAsTrueClick`，共 3 例 | 缺源恢复仍可从已知关闭语义兜底；Adapter 即时扫描无提示也生成 ACCESSIBILITY_CLICK。真实点击归因入口要求明确匹配语义，快照不得绕过冲突；共享被动候选 matcher 仍可服务独立发现 |
| CE-02 / AC-02、AC-04 | Probe 的 `policy03_partialScanCannotRecoverARealClickAsUnique`；PreClickRecovery 的 `policy04_directedPositiveCannotFormSnapshotFromPartialDiscoveryScan`，共 2 例 | 单次局部唯一被作为真实恢复成功，定向阳性可形成不完整快照。即时接受条件增加本轮完整唯一证明，快照形成也要求完整唯一；定向复查保留其明确范围内候选价值 |
| CE-03 / AC-01 | Boundary 的 `policy05_missingSourceWindowMetadataCannotBeAccepted`，1 例 | source.window 不可用仍接受其他同包窗口。缺失元数据保持未知/有限重试，保留完整元数据正例及显式非点击宿主回退 |

上述三组共 6 个失败，保留到必需清单。修复仅是后续建议，本轮没有修改生产实现。局部扫描反例使用 5 次读取预算：先得到唯一节点链，再在后续分支耗尽，确实验证“已找到但不完整”，不是节点链本身复制失败。

## 证据限制

Robolectric SDK 36 的 shadow 控制节点、查询故障、窗口副本和调度，不复制业务判断；Adapter、Probe、Resolver、Recheck 与协调器是真实实现。Resolver 释放测试只观察 recycle 调用，不能证明设备 Binder 生命周期。测试没有完整真实 service/container/DataStore 链，阻塞与最低接缝建议见 [生命周期验证](../capture-lifecycle/validation.md#真实服务集成阻塞与剩余限制)。

未专项验证 AccessibilitySemanticSnapshotter 的全文清洗/截断与完整节点释放计数，也没有真实系统事件投递、触摸时序或 Binder 故障证据；诊断全局 sink 的全部异常路径属于后续诊断能力，不据当前返回诊断测试宣称全覆盖。无设备或 Compose 证据，T-12 继续停止；P3 契约验收未通过。
