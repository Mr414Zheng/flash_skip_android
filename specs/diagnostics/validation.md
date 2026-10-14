# 验证记录：SDD-009

日期：2026-10-14。基线 main `0c9a53ffde08a6933fdb945ed187643589896462`。本文分列首轮测试与后续生产修复证据。规格见 [spec](spec.md)，任务见 [tasks](tasks.md)。

**当前：最小生产修复、本地、PR 及合并后主干 CI 自动化验收通过。** 全量 735 例通过，原 701 身份和预期保留，19 个原失败已关闭。Release／Compose／设备限制保留；详见[修复后本地验收](#修复后本地验收)。

## 首轮结果（修复前历史）

**首轮交付完成，契约验收未通过。** 本包新增 59 例：53 通过、6 失败，零错误／跳过；另有 Release 单测任务缺失阻塞。全量 701 例中 682 通过、19 失败，原 594 身份全部通过；命令和完整门禁见 [SDD-008 全量验证](../service-and-home/validation.md#全量命令与门禁)。不改生产、格式、依赖、构建、工作流或远端设置，无提交／推送／PR。T-12 未完成且停止，P6 未启动。


> 上述首轮结论及以下修复前结果为历史证据；用户随后授权修复。当前结论见[修复后本地验收](#修复后本地验收)：735 例全部通过，19 个原失败关闭。

## 方法与分层结果

| 测试类 | 新增 | 通过 / 失败 | 证据层级 |
| --- | --- | --- | --- |
| [CaptureDiagnosticTrailContractTest](../../app/src/test/kotlin/com/zhenghuaizhi/flashskip/platform/accessibility/CaptureDiagnosticTrailContractTest.kt) | 5 | 5 / 0 | JVM，真实轨迹聚合器及故障回调 |
| [CaptureDiagnosticJournalContractTest](../../app/src/test/kotlin/com/zhenghuaizhi/flashskip/platform/diagnostics/CaptureDiagnosticJournalContractTest.kt) | 28 | 28 / 0 | JVM 真实临时文件，手动异步 Executor；真实创建、写入、轮转、索引和重开 |
| [DiagnosticOutputContractTest](../../app/src/test/kotlin/com/zhenghuaizhi/flashskip/platform/accessibility/DiagnosticOutputContractTest.kt) | 23 | 17 / 6 | Robolectric SDK 36；全新 logger、ShadowLog、真实动作证据读取、受控提供者故障 |
| [DiagnosticBuildBoundaryTest](../../app/src/test/kotlin/com/zhenghuaizhi/flashskip/platform/accessibility/DiagnosticBuildBoundaryTest.kt) | 3 | 3 / 0 | Debug 的 Robolectric SDK 36；生产 runtime 开关、全局 CaptureTraceLog 到真实文件、打包 Manifest |

合计 5 JVM、28 JVM 真实文件、26 Robolectric 组件。本包无新增真实 DataStore 或真实服务集成例；全量回归中的相关结果归原能力和 SDD-008，不重算为诊断证据。缩小合法容量的边界测试之外，也实测默认队列 256、会话 10、会话 1 MiB、运行文件 128 KiB、单条 4096 字节；容量策略不只由构造参数或静态常量推断。

## AC、任务和结果映射

每个新增方法的完整身份、结果和主责 AC 归档于 `cases.json`／`ac-map.json`，源码与必需身份清单可在新检出中查看。T-01 为规格，T-02／T-03 为专项和边界，T-04 为全量证据，T-05 仍未通过。

| AC / 任务 | 测试入口及代表方法 | 实际结论 |
| --- | --- | --- |
| SDD-009/AC-01 / T-02、T-03 | DiagnosticBuildBoundaryTest 前两例；DiagnosticOutputContractTest：`disabledLoggerDoesNotInvokeSuppliersOrSinks`、`disabledScanDoesNotReadClockOrCreateDiagnosticCollectors`、`automationDisabledProducesNoLogs`、`managementDisabledNeverConstructsDiagnosticEvent` | Debug 和关闭模式测试通过；Release 无可运行单测任务，未通过整项构建边界验收 |
| SDD-009/AC-02 / T-02 | DiagnosticOutputContractTest：`metadataFilterNeverStringifiesArbitraryObjects`、`accessibilityEventNeverOutputsTextDescriptionPackageOrClass`；四个 `*MustNotContainStable*` 方法 | 类型／事件元数据过滤通过；四个出口稳定散列失败，未满足仅进程内关联 |
| SDD-009/AC-03 / T-02 | DiagnosticOutputContractTest：sink／注册失败隔离、`errorClassificationDoesNotContainExceptionMessages`、`managementSinkFailureIncludingCancellationCannotChangeCallerResult`、两个 `enabled*SupplierFailureCannotEscapeBusinessBoundary`；journal 通知异常、trail 回调失败 | 可注入 sink／通知错误隔离通过；两个惰性快照提供者异常外抛；Android Log 后端本身的异常未独立注入 |
| SDD-009/AC-04 / T-02 | DiagnosticOutputContractTest：重复 stage、最大延迟、24 stage、64 自动重复键、32 管理摘要、2 会话时序、迟到事件测试；CaptureDiagnosticTrailContractTest 全五例 | 所列全部通过；48 记录与丢失计数、会话清理、旧会话隔离通过 |
| SDD-009/AC-05 / T-02、T-03 | CaptureDiagnosticJournalContractTest：`appendIsAsynchronousAndUsesOneDrainTask`、`defaultQueueRetains256AndReportsOrdinaryOverflow`、`importantEventEvictsOldestOrdinaryAtCapacity`、`allImportantQueueMayDropAndCountsImportantLoss`、`ordinaryCannotEvictImportantAtCapacity`、`rejectedExecutorDoesNotThrowAndNextEventRecoversCounters` | 全通过，重要日志仍可能丢失；不证明退出前所有事件都已落盘 |
| SDD-009/AC-06 / T-02、T-03 | CaptureDiagnosticJournalContractTest：UTF-8／换行、普通预留／总量、默认 1 MiB／128 KiB、轮转、截断重开、10 会话登记顺序、迟到拒绝和回拨身份 | 全通过；真实进程内文件操作，不是断电证明 |
| SDD-009/AC-07 / T-02、T-03 | CaptureDiagnosticJournalContractTest：损坏索引／多种非法索引、`repairedIndexAllowsLaterEventsWithoutReplayingFailedEvents`、`failedIndexReplacementKeepsPreviousLedgerAndSession`、目录冲突／通知周期／分类恢复、登记日志丢失／重复／无关文件 | 全通过；坏索引及旧证据保留，外部修复后新事件恢复，失败事件不重放 |
| SDD-009/AC-08 / T-03 | DiagnosticBuildBoundaryTest：`packagedManifestDisablesBackupAndHasNoNetworkPermission`；两个变体最终 Manifest、BuildConfig、备份排除 XML 与 no_backup 装配检查 | Debug 组件与两变体静态检查通过；未执行 OEM 云备份／设备迁移或设备 Release |

## 六个产品失败

类均为 DiagnosticOutputContractTest：

| 方法 | 触发与观察结果 | 最小修复方向 |
| --- | --- | --- |
| `automationOutputMustNotContainStableContentHashes` | 记录包含已知 targetHash 的 AutomationTraceEvent，Logcat 原样输出稳定十六进制散列 | 自动执行诊断出口改为进程内关联；不更改选择器匹配或业务 ID |
| `scanOutputMustNotContainStableContentHashes` | 真实 PageIdentity 与 NodeSelector 完成扫描后，输出包名 hashCode | 扫描使用同一进程内映射 |
| `actionEvidenceOutputMustNotContainStableContentHashes` | 真实 AccessibilityActionEvidence 读取生成 ACTION_EVIDENCE_READ，包含稳定目标散列 | 动作证据日志接入相同策略 |
| `managementOutputMustNotContainStableSessionHash` | 管理事件将 sessionHash 作为整数原样写出 | 管理日志关联不得依赖可跨进程复现的散列 |
| `enabledDiagnosticSupplierFailureCannotEscapeBusinessBoundary` | diagnostics 提供者抛 IOException，summary 向调用者抛出 | 仅在诊断快照获取边界隔离异常，保留后续业务执行 |
| `enabledTargetWindowSupplierFailureCannotEscapeBusinessBoundary` | targetWindow 提供者抛 IllegalStateException，summary 向调用者抛出 | 同上，错误只记固定分类，避免异常消息进入诊断 |

稳定散列红例直接调用各真实输出路径，未替换编码或生产接口。单一断言失败只证明该可观察违规；不能据此声称所有可能字段均已穷尽。静态盘点还发现 ruleHash、selectorHash、resourceIdHash、ancestorIdHash、classHash，后续修复应统一检查这些输出及文件关联标识。现有数值过滤只能过滤类型，无法判断整数是不是稳定内容散列。测试没有引入或证明新的进程 token 方案；跨进程关联失效需后续方案与接缝验证。

两个提供者红例证明 logger 边界异常可外抛，未另行宣称设备上已发生服务崩溃。原有业务取消测试在全量回归中通过；诊断输出隔离不授权吞掉业务 scope 取消。

## 构建模式与静态配置

尝试运行：

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :app:testReleaseUnitTest --tests '*DiagnosticBuildBoundaryTest' --rerun-tasks --no-build-cache --console=plain
```

退出 1，错误为 `testReleaseUnitTest` 不存在；没有 Release JUnit XML，不记跳过或通过，也不把 Debug 中显式 false logger 的结果冒充实际 Release。恢复该证据需要另行授权现有构建的 Release 单测配置。本轮不改构建。

继续运行现有生产任务：

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :app:generateReleaseBuildConfig :app:processReleaseManifestForPackage --rerun-tasks --no-build-cache --console=plain
```

成功，任务重新生成 Release BuildConfig 及最终打包 Manifest。静态脚本解析两变体最终 XML 与生成常量，核对：

- Debug 为 `DEBUG = Boolean.parseBoolean("true")`，Release 为 `DEBUG = false`；生产 runtime 使用该常量。
- 无 INTERNET 权限；allowBackup／fullBackupContent 均 false；无障碍服务要求 BIND_ACCESSIBILITY_SERVICE。
- 两份最终 Manifest 引用 data_extraction_rules；源码 XML 在 cloud-backup 与 device-transfer 下排除 root、file、database、sharedpref、external 的 `.`。
- AppContainer 使用应用私有 `no_backup/capture-diagnostics`。未新增导出、上传或修复入口；业务 DataStore 编码及内容政策未改。

静态证据归档为 `debug-AndroidManifest.xml`、`release-AndroidManifest.xml`、两份 BuildConfig、`data_extraction_rules.xml` 和 `static-boundaries.json`。这不证明 OEM 执行排除、所有系统机制均无外传路径或实际安装版 Release 不写文件。

## 证据位置与验收限制

本机忽略目录 `app/build/reports/p5-contracts/full/` 保存最终日志、JUnit／HTML、Lint、静态边界、基线／现行必需清单、逐身份结果及 AC 映射、源码和产物 SHA-256。全量退出 1 来自 19 个产品失败；独立产物结构检查无错误，全部 701 个必需身份存在，Lint 为 0 错误／12 既有警告，28 检查器及规格检查通过。新检出不含本机归档，须按命令复建。早期定向报告位于 `preflight/`，不作为最终全量数量。

本轮没有独立 review／QA、远端 CI、真实 Compose 或设备证据；没有断电、文件系统崩溃、进程真实重启或 OEM 备份测试。Android Log 后端失败没有独立注入接缝；已验证的是现有本地 sink、通知、管理包装和提供者边界。真实服务中各调用点的诊断故障注入、全出口 Release 路径仍需后续最小接缝和配置授权。单条记录／队列“尽力”政策不允许将这些缺口记为零风险。

修复建议仅限统一诊断关联和提供者异常隔离；保持现有日志容量、业务数据格式和业务取消语义。损坏索引已符合当前政策，无需为了本轮通过而自动覆盖或迁移旧证据。生产修复尚未授权，六个红例保持必需。

## 修复后本地验收

2026-10-14 用户续授权生产修复及必要内部接缝。**本包六个原失败全部关闭，累计 74 例通过：10 JVM、28 JVM 真实文件、36 Robolectric SDK 36 组件。** 全量 61 类、735 例通过，原 701 个身份与预期保留；构建、Lint、检查器和证据门禁结果见 [SDD-008 修复后验收](../service-and-home/validation.md#修复后本地验收)。Release 单测任务缺失继续保留，因此 T-05 完整分层验收仍未完成。

### 修复及新增证据

| AC | 实施与结果 | 新增证据 |
| --- | --- | --- |
| AC-02 | 新增内部 ProcessDiagnosticIdentifiers：32 字节进程随机密钥、不持久化、HMAC-SHA256 命名空间派生；内容 token 截取 128 bit，journal 会话文件 ID 为非负 63 bit 数值。默认出口共享进程实例，不直接输出业务内容散列或业务会话 ID；管理移除多余 sessionHash。四个原散列红例通过 | ProcessDiagnosticIdentifiersTest 5 JVM 例：相同作用域关联、不同密钥隔离、命名空间、无原文／文件 ID 范围、密钥复制；DiagnosticPrivacyRegressionTest 前六例：全自动字段、两组密钥、扫描／自动关联、候选资源／类字段、真实 gateway 重验／手势回调、会话日志及文件 ID |
| AC-03 | 两个 summary 提供者分别隔离并记固定 `diagnostic_provider_failed` 分类，另一提供者及后续记录继续。日志后端与本地 sink 分别隔离，原两个提供者红例通过 | DiagnosticPrivacyRegressionTest 三例注入采集／自动日志后端失败及提供者失败；采集 Logcat 失败不阻止本地 sink 或下一条记录 |
| AC-01 | 关闭诊断不调用注入日志后端；原 Debug 构建开关及真实文件测试通过 | DiagnosticPrivacyRegressionTest 最后一例；实际 Release 运行仍阻塞 |
| AC-04～AC-08 | 原聚合、容量、真实文件保留与坏索引测试全部重跑通过；journal 实现与 schema 未修改；两个构建变体的配置源指纹未变 | 复用首轮静态配置证据并明确来源；不增加设备／备份执行结论 |

新增 [ProcessDiagnosticIdentifiersTest](../../app/src/test/kotlin/com/zhenghuaizhi/flashskip/platform/diagnostics/ProcessDiagnosticIdentifiersTest.kt) 5 例及 [DiagnosticPrivacyRegressionTest](../../app/src/test/kotlin/com/zhenghuaizhi/flashskip/platform/accessibility/DiagnosticPrivacyRegressionTest.kt) 10 例。不同密钥模拟独立进程作用域；未执行真实操作系统进程重启，不将其称为独立 QA。日志后端失败接缝现在可注入并执行，原“无接缝”表述仅描述首轮状态；未验证每个真实服务调用点的诊断故障。

采集日志用进程 scope 与有界会话序号关联；文件仍用原数值 ID／原索引结构，但 ID 派生自当前进程密钥。内容、规则和选择器的业务值仍用于原业务流程及合法 DataStore，不因诊断政策修改。没有密钥落盘、迁移、上传或恢复界面；token 不是匿名化承诺，截断标识仍具有理论碰撞可能。原坏索引政策、诊断丢失与容量限制不变。

原 `full/` 失败证据保留且指纹复核通过，当前证据位于 `app/build/reports/p5-contracts/repaired-final/`。完整 AC 映射覆盖 P5 累计 141 例：54 JVM、28 JVM 真实文件、48 Robolectric 组件、11 真实服务／实际 DataStore 集成。SDD-008 的真实服务例不重复计入本包。Release 构建配置未改，沿用首轮任务缺失记录及经源指纹验证的静态配置；没有修复后 Release 运行通过声明。

本轮无提交／推送／PR，不改持久化格式、依赖、构建、工作流或远端设置。无远端 CI、独立评审／QA、真实 Compose 或设备证据。SDD-001/T-12 未完成且继续停止，P6 未启动。

## P5 PR 交付续授权

2026-10-14 用户续授权新建分支、提交、推送、创建 PR、等待远端 CI、下载核验报告并回写证据，PR 保持未合并。此前“无提交／推送／PR”描述本地修复阶段。持久化格式、依赖、构建配置、工作流及远端设置不得更改；Release 单测阻塞保留，T-12 未完成且继续停止。远端结果须另行核验，不复用本地通过结论。

## PR 与远端验收

[PR #8](https://github.com/Mr414Zheng/flash_skip_android/pull/8) 位于 `codex/p5-service-home-diagnostics`，产品 head `a81b712900cf93599e6b80eb02837dda4c263efd` 已通过 [Linux CI 38526504633](https://github.com/Mr414Zheng/flash_skip_android/actions/runs/38526504633) 的 Specs 和 Android。下载两组报告后核验 61 类／735 例全部通过，完整身份与必需清单及本地报告一致，包括全部原 701 身份和 19 个原失败；28 个检查器、规格／PR 正文、Debug 构建及产物门禁通过，Lint 0 错误／15 警告。

API 报告大小及摘要与下载 ZIP 匹配，environment 检出提交与产品 head 的合并父提交关系已核对。分层结果、artifact ID／digest、路径与限制见 [P5 远端 CI](../ci.md#p5-pr-与远端-linux-ci-验收)。工作流未上传 APK 二进制，不声称下载或安装了远端 APK。证据文档 head 须再验其最新 CI；PR 保持未合并供用户审阅。

Release 任务缺失继续阻塞，真实 Compose／设备及独立评审／QA 未执行；完整分层契约验收仍未完成。持久化格式、依赖、构建、工作流及远端设置不变，T-12 未完成且继续停止。

## 合并与主干验收

[PR #8](https://github.com/Mr414Zheng/flash_skip_android/pull/8) 已在重新确认最新 head `799f267` 的 Specs、Android 全部成功后合入 main（`5aafcdf40aaf4d56e5d4336f33e48c495590983f`）。[主干 CI 38530285304](https://github.com/Mr414Zheng/flash_skip_android/actions/runs/38530285304) 的 61 类／735 例全部通过，零失败／错误／跳过；原 701 身份及原 19 个失败均通过。28 个检查器、规格、Debug 构建及产物门禁通过，Lint 0 错误／15 警告。

两组报告已下载，核验 ZIP 大小／SHA-256、完整测试身份、报告内容及 environment 中的 main 合并 SHA。artifact ID、摘要及保存路径见 [P5 主干 CI](../ci.md#p5-合并与主干-ci-验收)。此前 PR 未合并表述属于历史阶段；本次证据回写使用独立文档 PR，须验证其自身 CI 并保持未合并供用户审阅。

没有远端 APK 二进制下载或新增设备／Compose／独立 QA 证据。Release 单测任务缺失仍阻塞，完整分层验收未完成；不改持久化格式、依赖、构建、工作流或远端设置。T-12 未完成且继续停止，P6 未启动。
