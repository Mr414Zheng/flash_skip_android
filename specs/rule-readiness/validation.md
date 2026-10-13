# 验证记录：规则就绪与目录

规格：[spec.md](spec.md)；任务：[tasks.md](tasks.md)。状态：**P2 本地及远端 CI 验收通过，RR-01 已修复**。用户已批准三项政策并明确授权对应生产修复，其他默认参数沿用基线。本包专项 32 例全部通过，0 失败/错误/跳过；保留下方修复前 10 个失败反例的历史记录。

## 合并与主干 CI 验收（当前）

PR #3 最新 head `b23ac9d` 的 Specs、Android 检查再次确认通过后，于 2026-10-13 17:27:02（Asia/Shanghai）按用户授权合入 main，合并提交为 `c2a286b2c0e02515810bd8ebb8b87b40d474a820`。合并文件树与已通过检查的 PR head 相同。

[主干 CI 38376388018](https://github.com/Mr414Zheng/flash_skip_android/actions/runs/38376388018) 的 Specs、Android 均通过；两组报告已下载，environment.txt 与合并 SHA 一致。201 个测试和 28 个检查器测试全部通过，0 失败/错误/跳过；201 个身份与必需清单逐项一致；18 份文档、9 个编号检查、debug 构建与产物检查通过，Lint 0 错误、15 警告。完整运行、产物和限制见 [主干验收记录](../ci.md#p2-合并与主干-ci-验收)。

当前结论为 P2 已合入 main 且主干 CI 通过。此轮验收回写仅修改文档，不修改依赖、远端设置或应用构建配置。SDD-001/T-12 继续未完成且停止，离线平台证据不代表新增设备验收。

## PR 远端 CI 验收（合并前）

已按用户续授权提交并推送，创建 [PR #3](https://github.com/Mr414Zheng/flash_skip_android/pull/3)，该验收时点保持 OPEN 且不合并。生产及测试提交为 `3a4b360253a70c82f526e734ce81426571863968`；[CI 38374303555](https://github.com/Mr414Zheng/flash_skip_android/actions/runs/38374303555) 的 Specs、Android 均成功，时间为 2026-10-13 17:09:30～17:12:42（Asia/Shanghai）。

已下载核验两组 artifact：完整 201 例及 28 个检查器测试全部通过，0 失败/错误/跳过；201 个用例身份与必需清单逐项一致，18 份文档、9 个编号及真实 PR 正文检查通过；debug 构建成功，56 个 Gradle 任务全部执行，Lint 0 错误、15 警告。SDK 36 Robolectric 证据仍不等于设备验证。

实际所测 PR 合并提交为 `df16b90426a7e738fa52da922073d39fba3fa03f`；工具链、父提交、警告分布、下载路径和保留期限见 [CI 远端记录](../ci.md#p2-pr-远端验收与证据回写)。本次仅回写文档，后续提交的结果以 PR 最新 head 检查为准。下方未提交、无远端证据等说明保留为本地实施历史；当前 P2 本地及远端 CI 均已通过，未改依赖或远端设置，SDD-001/T-12 保持未完成且停止。

## 修复后本地执行与证据

2026-10-13 16:57:08～16:57:17（Asia/Shanghai）执行完整 JVM/Robolectric 套件；代码状态为 `ad01dc7` 加未提交的 P2 规格、测试、必需用例清单及六个生产文件修复。使用下方相同 Gradle 命令，强制重跑且禁用构建缓存；Gradle 全程 34 秒，56 个任务全部执行。

| 检查 | 实际结果 |
| --- | --- |
| 完整 JVM/Robolectric | 201 例全部通过，0 失败、0 错误、0 跳过 |
| SDD-002 规则就绪与目录 | 32/32 通过，原 RR-01 的 10 个反例全部通过 |
| SDD-003 自动执行 | 94/94 通过，详见其验证记录 |
| SDD-001 回归 | 原 75 个完整用例身份全部保留并通过 |
| P2 新增确定性领域/集成 | 90 例全部通过，包括真实目录与引擎集成 |
| P2 新增 Robolectric | SDK 36，36 例全部通过；候选查找 10、动作证据 11、PageGateway 15 |
| CI 检查器 | 28 例通过，无失败/错误/跳过 |
| 规格检查 | 18 份文档、9 个规格编号通过 |
| debug 构建 | `assembleDebug` 成功 |
| Android Lint | 0 错误、12 警告，与修复前分布相同 |
| 报告检查 | `--require-artifacts` 通过；201 个必需身份全部通过，无失败/错误/跳过，Lint 12 警告 |
| 独立静态代码评审 | GPT-6 Astra high 审阅六个生产修复文件，`no_findings`；不是独立 QA |

必需用例清单当前为 21 个类、201 个完整身份，原有 75 例保留。新增四个自动执行边界方法，本包测试身份未变，仅补充空白/框架 ID、祖先距离等矩阵。修复后本机证据已归档至忽略目录 `app/build/reports/ci/p2/repaired-final/`，包含 `junit/`、`html/`、`gradle.log`、Lint 和检查器/规格/报告检查记录；工具链与最终文件指纹见同目录 `environment.txt` 和 `file-sha256.json`。原 `final/` 保留为修复前记录。不存在本轮设备、Compose UI 组件或远端 CI 证据；没有创建提交、推送、PR、升级依赖或修改构建配置/远端设置。

## 修复前执行与证据（历史记录）

2026-10-13 16:37:07～16:37:14（Asia/Shanghai）执行最终完整套件；代码状态为 `ad01dc7` 加本轮规格、测试与必需用例清单，生产代码、依赖及应用构建配置无差异。命令：

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

修复前必需用例清单从原 75 例扩展至 21 个类、197 个完整身份，逐项对照真实 XML 后登记，原有 75 例保留。未创建 PR、未推送，不存在本轮 P2 远端 CI 通过结论。

本机证据存放在忽略目录 `app/build/reports/ci/p2/final/`：`junit/`、`html/`、`gradle.log`、`lint.xml`、`lint.html`、`checker-tests.log`、`report-check.txt`；最终文件指纹与工具链见同目录 `environment.txt` 和 `file-sha256.json`。全新检出不包含这些产物，可用上述命令重新生成。早期分阶段报告保留在其相邻子目录，其中 final 对应修复前代码状态；当前修复后状态以 repaired-final 为依据。

Lint 警告分布：OldTargetApi 1、AndroidGradlePluginVersion 1、GradleDependency 6、NewerVersionAvailable 1、ObsoleteSdkInt 1、MonochromeLauncherIcon 2。本轮本地 12 条与 P1 远端历史 15 条是不同环境/时点的报告；不将差额当作生产修复或回归结论。

## 本包逐用例追踪

下表为修复后实际结果，修复前失败历史见上文及 RR-01 记录。表中 `acXX` 对应本包同号 AC；复合前缀同时覆盖多个条件。`SelectorMatcherTest` 的 AC-03 属 SDD-002，其余自动执行及平台类属于 SDD-003。通过表示已列场景通过，不是穷尽状态空间或全项目覆盖声明。

### SelectorMatcherTest

源码：[SelectorMatcherTest](../../app/src/test/kotlin/com/zhenghuaizhi/flashskip/domain/automation/SelectorMatcherTest.kt)。

| 方法 | 结果 |
| --- | --- |
| `ac03_countdownsDoNotMatchOrdinaryTextOrOversizedNumbers` | 通过 |
| `ac03_ancestorMustMatchExactDistanceAndRemainVisibleWithBounds` | 通过 |
| `ac03_allSpecifiedConditionsVisibilityAndBoundsMustMatch` | 通过 |

### RuleCandidateAnalyzerTest

源码：[RuleCandidateAnalyzerTest](../../app/src/test/kotlin/com/zhenghuaizhi/flashskip/domain/rulecapture/RuleCandidateAnalyzerTest.kt)。

| 方法 | 结果 |
| --- | --- |
| `ac02_textAndDescriptionWithoutStructureRemainCandidate` | 通过（修复前失败） |
| `ac01_groupsKeepActivitiesSeparateAndNewestEvidenceFirst` | 通过 |
| `ac02_genericDismissRequiresAppResourceOrStableAncestor` | 通过 |
| `ac03_numericHintOnActionNodeProducesConstrainedCountdown` | 通过 |
| `ac02_plainDescriptionRemainsCandidate` | 通过（修复前失败） |
| `ac01_wrongTargetNegativeTimeBlankActivityAndEmptyNodesProduceNoRules` | 通过 |
| `ac02_frameworkResourceDoesNotProvideStableStructure` | 通过（修复前失败） |
| `ac01_singleEligibleSampleNeedsNoPreClickUniqueEvidence` | 通过 |
| `ac03_numericNeedsDismissSemanticsWithinActualActionChain` | 通过 |
| `ac03_countdownConversionKeepsOrdinaryTextExactAndRequiresStructure` | 通过 |
| `ac02_plainTextRemainsCandidateEvenWithManualConfirmation` | 通过（修复前失败） |
| `ac02_stablePrimaryCannotCarryUnconstrainedFallbackSelectors` | 通过（修复前失败） |

### CompositeRuleCatalogTest

源码：[CompositeRuleCatalogTest](../../app/src/test/kotlin/com/zhenghuaizhi/flashskip/domain/rules/CompositeRuleCatalogTest.kt)。

| 方法 | 结果 |
| --- | --- |
| `ac04_localRulesPrecedeBuiltinAndSnapshotContainsSameSamplesAndAnalysis` | 通过 |
| `ac05_sessionOnlyChangeDoesNotPublishAnotherRuleSnapshot` | 通过 |
| `ac04_duplicateAcrossTargetsDropsAllLocalRulesButKeepsReadyAnalysis` | 通过 |
| `ac04_invalidLocalRuleIsFilteredWithoutDroppingUnrelatedValidRule` | 通过 |
| `ac05_newSamplesCancelInFlightComputationAndDeletionNeverResurrectsOldRules` | 通过 |

### RuleCatalogSnapshotTest

源码：[RuleCatalogSnapshotTest](../../app/src/test/kotlin/com/zhenghuaizhi/flashskip/domain/rules/RuleCatalogSnapshotTest.kt)。

| 方法 | 结果 |
| --- | --- |
| `ac06_boundRulesRequireExactNameAndCodeWhileUnboundRulesRemainApplicable` | 通过 |
| `ac06_copyRebuildsIndexesAndPreservesPriorityAndTargetIsolation` | 通过 |

### RuleValidatorTest

源码：[RuleValidatorTest](../../app/src/test/kotlin/com/zhenghuaizhi/flashskip/domain/rules/RuleValidatorTest.kt)。

| 方法 | 结果 |
| --- | --- |
| `ac02_frameworkIdCannotBypassStableStructurePolicy` | 通过（修复前失败） |
| `ac02_combinedTextAndDescriptionCannotEnterBuiltinCatalog` | 通过（修复前失败） |
| `ac02_plainDescriptionCannotEnterBuiltinCatalog` | 通过（修复前失败） |
| `ac04_rejectsEachMalformedRuleField` | 通过 |
| `ac02_stablePrimaryCannotAuthorizeUnconstrainedFallbackSelector` | 通过（修复前失败） |
| `ac03_dynamicTextNeedsAppSpecificStructure` | 通过 |
| `ac02_stableResourceAndStableAncestorAreAccepted` | 通过 |
| `ac03_ac04_ancestorAndActionDistancesAreOneThroughFour` | 通过 |
| `ac04_duplicateIdsInvalidateEntireBuiltinSet` | 通过 |
| `ac02_plainTextCannotEnterBuiltinCatalog` | 通过（修复前失败） |

## 已复现偏差与修复闭环

### RR-01：无结构的选择器进入可执行目录【已修复】

关联 SDD-002/AC-02、T-05；修复前 10 个失败反例（分析器 5、统一校验 5）。输入为无资源 ID 的普通文本、描述、文本加描述、仅框架 ID，或稳定主选择器加无结构备用选择器。预期：无结构输入只保留候选，所有可执行选择器均满足稳定结构；修复前实际：分析器生成 READY/含无结构备用选择器，统一校验返回空问题集合，无法阻止内置目录绕过。内置断言位于校验断言之后，修复前失败在校验入口，当时不能额外声称其后断言已经执行通过；修复后完整方法通过，包括后续内置目录断言。

已授权并实施最小修复：`RuleCandidateAnalyzer.isStrongEnough` 对全部候选选择器统一要求稳定资源或稳定祖先；`RuntimeSelectorPolicy` 的稳定资源及祖先判断排除空白 ID；`RuleValidator` 对每个可执行选择器新增 `MISSING_STABLE_STRUCTURE` 门禁，保留其他字段、动态文本和距离校验。`BuiltinRuleCatalog` 和本机组合目录沿用同一统一校验。普通纯文本样本仍保留为候选，不删除数据、不迁移、不自动修改存储开关。本包 32 例、目录/引擎跨层测试和原 75 例均随完整套件重跑通过。

**生产修复已获用户明确授权并完成；AC-02 及其余 AC 的已列用例通过。** 组合冲突回退已验证跨目标影响，READY 分析与最终规则可能分离；显式启用继续复用本轮实际重跑通过的 SDD-001/AC-06 交错用例。

## 测试实施修正与剩余限制

- 首次运行使用系统默认 Java 时未找到运行时；随后使用仓库已记录的 Android Studio JBR。Gradle 缓存需要沙箱外访问，经工具审批后执行；最终不是环境阻塞。
- 平台测试初版出现 Shadow 状态跨用例残留及缓存窗口被正常 recycle 清空。已在每个夹具开始清理注入状态，并让测试服务返回独立窗口副本；仅修正测试夹具。最终 36 例全部通过，未把夹具问题计入产品偏差。
- 修复前同一方法中的输入矩阵在首个失败处停止，历史失败不代表每个矩阵项当时均已执行；修复后完整方法和所含矩阵均通过。未使用 `@Ignore`、真实休眠或预期异常掩盖三项政策失败。
- 单次确定性用例不能证明所有调度交错；目录会话更新用例验证没有额外发布，不宣称 CPU 重算次数已经被测量。Robolectric 及故障 Shadow 只验证适配器对受控输入的处理，不代表真实系统服务或 OEM 行为。
- 已执行六个生产修复文件的独立静态代码评审，结果为 `no_findings`；未执行独立 QA，运行结果为实施者本地验证。SDD-001/T-12 未完成且按用户要求继续停止，没有新增设备或 Compose UI 组件证据。
- 三项政策已审定且对应生产偏差闭环，P2 本地离线验收通过；规格及报告检查已通过，原 22 个失败身份逐项对照当前 XML 均通过。不修改远端设置，不将本地通过声称为远端 CI 或设备验收成功。
