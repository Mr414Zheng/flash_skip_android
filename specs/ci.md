# P1 持续检查与验收记录

状态：本地验证与远端 Linux CI 验收均已通过，[PR #1](https://github.com/Mr414Zheng/flash_skip_android/pull/1) 已合入 main，合并提交 `2818232` 的主干 CI 通过。实施基线为 `97d3010`，首次远端验收对应分支提交 `85fcd27`，日期为 2026-10-13。以上为 P1 历史验收。P2 以 main `ad01dc7` 为基线，修复前三项政策偏差导致 197 例中 22 例失败；获得政策批准和生产修复授权后，完整重跑 201 例全部通过。本轮没有远端 CI 结果，详见下方 P2 记录。不修改远端设置或依赖，不恢复 SDD-001/T-12。

## 工作流与边界

[CI 工作流](../.github/workflows/ci.yml) 面向 GitHub，包含两个独立任务 `Specs`、`Android`。触发条件为面向 `main` 的 PR（含描述修改）、推送到 `main`、手动运行；没有路径过滤或按变更跳过任务的条件。相同 PR/分支的新运行会取消旧运行，取消不视为通过。

- 使用 `ubuntu-24.04`，Specs 超时 10 分钟，Android 超时 20 分钟。工作流显式使用 Bash 的失败退出和管道失败语义，`tee` 不掩盖检查失败。
- 使用普通 `pull_request`，只授予 `contents: read`，checkout 不保存凭据。第三方 Action 固定完整提交 SHA；版本注释及 SHA 来自 2026-10-13 对各维护仓库发布标签的只读核对。
- Android 使用 Temurin JDK 21、SDK `platforms;android-36.1`、Build Tools `36.0.0`，沿用仓库 Gradle 9.4.1。Java 源码兼容级别仍为 17，未修改 AGP、Kotlin 或应用依赖。
- Gradle Action 使用 basic 缓存，仅保存依赖目录 `caches/modules-2`，非 main 运行只读缓存。没有恢复 `app/build`，不上传依赖图或发布 Build Scan。
- 测试、debug 构建、Lint 使用 `--rerun-tasks --no-build-cache --continue`。强制生成本次证据；`--continue` 收集其他可运行检查，仍保留失败退出状态。没有 `continue-on-error`。
- 构建失败后仍尝试检查与上传已有报告；取消或超时可能无法上传，不宣称此类运行有完整报告。

工具依据：[GitHub 工作流语法](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax)、[AGP 9.2 兼容要求](https://developer.android.com/build/releases/agp-9-2-0-release-notes)。Action 输入以固定 SHA 处的 `action.yml` 为准。

## 检查规则

| 入口 | 阻断条件与职责 |
| --- | --- |
| [check_specs.py](../scripts/ci/check_specs.py) | 检查 `AGENTS.md` 和 `specs/` 内 Markdown：重复/未知规格编号、未定义的完整 AC/任务引用、失效的仓库文件链接和 Markdown 标题锚点。PR 事件另外检查验收说明 |
| [PR 模板](../.github/pull_request_template.md) | 填写已定义的完整 SDD/AC 引用，或至少 8 字的“无契约变化原因”；填写至少 8 字的“验证结果”。注释中的示例和待填写占位词不算输入；错误引用不能用无契约变化原因豁免 |
| [check_test_results.py](../scripts/ci/check_test_results.py) | 拒绝缺失/损坏/空 JUnit XML、用例计数不符、重复身份、任何失败/错误/跳过、关键用例缺失。`--require-artifacts` 还检查 debug APK、测试 HTML、Lint HTML/XML，拒绝 Lint Error/Fatal |
| [必需用例清单](../.ci/required-tests.json) | 原清单从 `e354a99` 登记 5 个类、75 个完整身份；P2 按 `ad01dc7` 加本轮新增测试的真实 XML 扩展为 21 个类、201 个完整身份，保留原 75 例及参数化阶段；允许新增测试，删除/改名/参数变更必须明确评审后同步清单。数量相同不能抵消必需用例缺失 |
| [本地证据例外](../.ci/local-evidence-links.json) | 仅豁免已有验证文档中逐条登记的 `app/build` 历史链接，不要求新检出具有本机产物。未使用的例外也报错；新建错误链接不会自动豁免 |

规格包从 [登记表](README.md) 读取；预留目录是普通代码文本，不当作已存在的规格链接。当前检查支持行内 Markdown 链接、ATX 标题和完整 `SDD/AC`、`SDD/T` 引用，不验证远端 URL 可达性，也不展开裸写的编号区间。PR 字数只是防空白的最低门槛，引用与变更的语义关联、验证声明真实性仍由评审负责。推送/手动事件没有 PR 正文，仅执行仓库检查。

测试报告脚本校验输入证据，不单独保证报告来自本次运行；工作流的新检出、强制执行与禁用构建缓存共同保证本次生成。直接本地调用报告脚本时，须先执行下面的 Gradle 命令。

## Lint 与报告保留

本地与远端警告清单如下，均为 0 错误。Lint 错误阻断，警告完整报告；首版不启用 `warningsAsErrors`、不添加 Lint baseline、不自动升级或修改生产代码。新增警告需要评审，目前不按警告差量自动阻断。

| Lint ID | 本地 | 远端 Linux |
| --- | --- | --- |
| OldTargetApi | 1 | 1 |
| AndroidGradlePluginVersion | 1 | 2 |
| GradleDependency | 5 | 6 |
| NewerVersionAvailable | 1 | 3 |
| ObsoleteSdkInt | 1 | 1 |
| MonochromeLauncherIcon | 2 | 2 |
| 合计 | 11 | 15 |

远端比本地多出的 4 项均为版本更新提示：Gradle 9.4.1 → 9.8.1、Core KTX 1.18.0 → 1.19.1、Compose 编译器插件 2.3.21 → 2.4.21、coroutines 1.10.2 → 1.11.0。这是两次报告中观察到的差异，不代表依赖已变更，也不在本轮顺带升级。

每次运行按 run ID 和 attempt 分别保存 Specs 与 Android artifact，保留 14 天。包含检查器输出、提交与工具链信息、JUnit XML、测试 HTML、Lint HTML/XML。缺少必需报告会失败；不会上传 APK、设备截图、DataStore 导出或设备诊断日志。成功运行和受控失败运行的上传、下载均已实际验证；产物过期后不能仅凭链接认定仍可下载。

## 本地执行与验证记录

在仓库根目录执行；需要 Python 3.9+、JDK 21 和现有 Android SDK：

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts/ci/tests -v
PYTHONDONTWRITEBYTECODE=1 python3 scripts/ci/check_specs.py
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --rerun-tasks --no-build-cache --continue --console=plain
PYTHONDONTWRITEBYTECODE=1 python3 scripts/ci/check_test_results.py --require-artifacts
```

macOS 本轮使用 Android Studio 自带 JBR：`JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home`。本地模拟 PR 检查时传入 `--event-name pull_request --event-path /path/to/event.json`，JSON 中填写 `pull_request.body`；内容作为数据读取，不插入 shell。

2026-10-13 本地实施阶段的实际验证结果如下；生产及业务测试代码均与 `97d3010` 相同。以下保留历史执行范围，后续远端验收独立记录。

| 检查 | 实际结果 |
| --- | --- |
| 检查器测试 | 28 例通过，0 失败/错误/跳过；包含正例及负例，不计入 Android 的 75 例 |
| 规格检查 | 10 份文档、9 个规格编号通过；复制版本控制范围及本轮新增文件到无构建产物的临时本地仓库，再执行也通过 |
| PR 事件与管道 | 有效正文经实际 CLI 和 `bash -eo pipefail` / `tee` 返回 0；空正文返回 1，失败未被管道吞掉 |
| 工作流静态检查 | 官方 actionlint 1.7.12 检查通过；本机没有 ShellCheck，使用 `-shellcheck=`，不声明已完成 ShellCheck 分析 |
| Gradle | 全量命令成功，36 秒，56 个 actionable task 全部执行；没有复用测试或 Lint 结果 |
| JVM / Robolectric | 75 例通过，0 失败/错误/跳过；JUnit 报告时间为 12:06:44～12:06:52（Asia/Shanghai） |
| Debug APK 与报告 | APK、测试 HTML、Lint HTML/XML 均生成并通过产物检查 |
| Lint | 12:07:07 重新生成，0 错误、11 警告，分布与上表一致 |
| 改动边界 | 本地实施阶段的 `app/src`、应用/根构建脚本、`gradle/`、Gradle 属性和 settings 均无差异；该阶段尚未执行推送或远端设置变更 |

检查器测试覆盖缺少报告、XML 损坏、零用例、必需参数化用例消失、同数量替换、重复用例、失败/错误/跳过、Lint 错误、错误链接/锚点/引用及空 PR 模板。Gradle 输出中的 `preDebugAndroidTestBuild SKIPPED` 是未配置设备测试的准备任务，不是 JUnit 用例跳过，也不提供设备验证证据。

本地原始报告位于 `app/build/test-results/testDebugUnitTest/`、`app/build/reports/tests/testDebugUnitTest/`、`app/build/reports/lint-results-debug.*`；P1 检查输出位于 `app/build/reports/ci/`。这些产物被 Git 忽略，全新检出不包含它们。

## 远端 CI 验收记录

2026-10-13 将保存契约测试 `e354a99`、P0 文档 `97d3010`、P1 配置 `85fcd27` 推送至 `codex/p1-ci-acceptance`，当时创建面向 main 的草稿 PR #1。本节保留该分支提交的历史验收快照；后续文档提交、转为 Ready、合并及主干运行独立记录在下方，不把旧运行当作新提交的执行证据。

| 运行 | 目的与输入 | 实际结果 |
| --- | --- | --- |
| [38357267880](https://github.com/Mr414Zheng/flash_skip_android/actions/runs/38357267880) | 初次 Linux 新检出，完整 PR 验收说明 | Specs、Android 均成功；28 个检查器测试和 75 个 JVM/Robolectric 测试通过；56 个 Gradle 任务全部执行，debug 构建成功，Lint 0 错误 / 15 警告；两组产物均下载核对 |
| [38357804291](https://github.com/Mr414Zheng/flash_skip_android/actions/runs/38357804291) | 仅把 PR 的“验证结果”改为“待填写”，代码不变 | 整体按预期失败：Specs 指出缺少验证说明并退出 1，28 个检查器测试通过，Android 成功；失败 artifact 上传及下载正常，没有被报告步骤掩盖 |
| [38358229099](https://github.com/Mr414Zheng/flash_skip_android/actions/runs/38358229099) | 恢复完整正文并补充首轮及负例证据，代码不变 | Specs、Android 均成功；28 + 75 例通过且无失败/错误/跳过，debug 构建与报告检查通过，Lint 0 错误 / 15 警告；最新 PR 检查恢复通过 |

代码与工具链对应关系：

- 分支 head：`85fcd279f318612a46f04ee9bd5223c8c18a46a5`。
- 实际检出并测试的 PR 合并提交：`e7d9da8b785943bb76f8ab125cb1dd22f7ab4722`；父提交为远端 main `3fa4dc02af7496dbf4e32be9043b038930ad8d89` 和上述 head，已核对 GitHub 提交信息与 artifact 内 `environment.txt`。
- Runner 为 `ubuntu-24.04` / Linux amd64，Temurin JDK 21.0.12.1，Gradle 9.4.1；所需 SDK 36.1 和 Build Tools 36.0.0 已安装。应用、测试及依赖版本与本地验收相同。
- 首轮作业开始于 14:32:23，Android 于 14:36:12 完成；恢复后的最终运行于 14:42:30 开始，14:47:11 全部完成（Asia/Shanghai）。状态监听曾发生一次网络 EOF，随后直接查询确认运行成功，不将监听连接错误归为构建失败。

该阶段最终成功运行的产物：

| Artifact | 下载核对内容 | 保留情况 |
| --- | --- | --- |
| `specs-38358229099-1` | 28 例检查器结果、10 份规格文档 / 9 个编号以及 PR 正文检查通过 | 已上传并下载，登记过期日为 2026-10-27 |
| `android-38358229099-1` | 5 份 JUnit XML 共 75 例，按完整用例身份核对；测试 HTML、Lint XML/HTML、工具链和报告校验输出齐全 | 已上传并下载，登记过期日为 2026-10-27 |

此外，`specs-38357804291-1` 已下载，确认包含受控负例的明确失败原因。原始下载文件位于本机 `app/build/reports/ci/remote/<run-id>/`，不提交到仓库；验收摘要在文档中保留，不能把“曾下载成功”扩展为未来永久可访问。

验收前只读确认 Actions 已启用并允许当前使用的 Actions，默认工作流令牌权限为 read；本工作流的只读声明与上传产物已实际运行成功。该验收快照时 PR 为 OPEN / draft，远端 main 为 `3fa4dc0`；这只是合并前状态，当前结果见下节。全过程未修改 Actions 策略或分支保护。

## 合并与主干 CI

按用户授权将 PR #1 转为 Ready for review，确认新一轮检查成功后，于 2026-10-13 15:23:07（Asia/Shanghai）合并到 main。采用合并提交保留原有 4 个提交；合并 SHA 为 `2818232319bdd0f6dcd0c9dfe0851a36ce065931`，父提交为原 main `3fa4dc02af7496dbf4e32be9043b038930ad8d89` 与 PR head `3c426ca42c1ddf75a655efbde681de6fc922b06a`。合并文件树与审核通过的 PR head 相同，生产代码、应用构建配置及依赖均未改变。

| 运行 | 对应版本与事件 | 实际结果 |
| --- | --- | --- |
| [38360031219](https://github.com/Mr414Zheng/flash_skip_android/actions/runs/38360031219) | 首次文档回写 `3c426ca`，PR 同步事件；实际测试合并提交 `648cfc2` | Specs、Android 成功；28 + 75 例通过，debug 构建成功，Lint 0 错误 / 15 警告；报告已下载核对 |
| [38361748800](https://github.com/Mr414Zheng/flash_skip_android/actions/runs/38361748800) | 同一 head `3c426ca`，转为 Ready for review 触发 | Specs、Android 均成功；合并前再次确认两项最新检查通过、head 与 base 未变化 |
| [38362313424](https://github.com/Mr414Zheng/flash_skip_android/actions/runs/38362313424) | main 的 push 事件，实际检出 `2818232319bdd0f6dcd0c9dfe0851a36ce065931` | Specs、Android 成功；28 个检查器测试、10 份文档 / 9 个编号检查通过；75 个必需 JVM/Robolectric 用例无失败、错误或跳过；debug 构建及报告检查通过；Lint 0 错误 / 15 警告 |

主干两组报告均已上传、下载并核对，按工作流的 14 天策略保留：

- `specs-38362313424-1`：28 个检查器测试及仓库规格检查输出。main 的 push 事件没有 PR 正文，报告中的 `PR 检查：False` 符合工作流定义。
- `android-38362313424-1`：5 份 JUnit XML 共 75 例，按完整必需用例身份核对；测试 HTML、Lint XML/HTML 和报告校验输出齐全；`environment.txt` 中提交 SHA 与 main 合并提交一致。Lint 分布与上方远端列相同。
- 原始下载位于本机 `app/build/reports/ci/remote/38362313424/`，不提交到仓库。这些证据仅对应上述已完成运行，本次新文档 PR 的 CI 须独立核对。

主干工作流已实际运行。只读核对时 main 未启用分支保护，也没有必需检查或强制审批规则；本次未调整这些设置，不将 CI 通过写成强制合并门禁已配置。

## 阶段结论与后续条件

- P1 本地、远端验收及主干落地均已完成，证据涵盖配置、检查脚本正负例、PR 验收说明、Linux 构建/测试/Lint、报告上传下载及历史警告差异。
- 当前 P2 授权包括规格落盘、离线测试、证据、必需用例清单，以及用户批准的三项政策最小生产修复；应用构建配置、依赖、远端设置及 CI 工作流保持不变；本轮没有提交、推送或合并。
- PR #1 已合并，main 工作流运行成功；必需检查和分支保护未配置，后续设置变更须按实际授权处理。
- SDD-001/T-12 未完成并继续停止。本轮不产生任何新增设备验证证据，不以 CI 通过替代设备结论。

## P2 修复前本地契约测试记录

基线 main `ad01dc7`（文档 PR #2 已合并），2026-10-13 16:37（Asia/Shanghai）执行修复前完整套件，当时生产代码和应用构建配置无差异。此节保留失败历史；当时 P2 尚未通过，也没有本轮远端 CI 结果。

| 项目 | 本轮结果 |
| --- | --- |
| 领域/集成新增测试 | 86 例：64 通过、22 失败 |
| 平台 Robolectric SDK 36 | 36 例全部通过，不能替代设备证据 |
| 原 SDD-001 回归 | 75 个完整用例身份全部通过且保留 |
| 全部 JUnit | 21 个类、197 例：175 通过、22 失败、0 错误/跳过 |
| 检查器 / 规格 | 28 例通过；18 份文档、9 个编号通过 |
| 构建 / Lint | debug 构建成功；Lint 0 错误、12 警告；56 个 Gradle 任务全部实际执行 |
| 证据阻断 | `check_test_results.py --require-artifacts` 退出 1，正确报告 22 个失败用例；无必需身份或产物缺失 |

新增用例纳入既有必需清单，没有使用忽略测试、放宽预期或移除失败阻断。失败归为稳定结构政策 10 例、目录失效政策 9 例、最近接受动作事件政策 3 例；分别见 [SDD-002 验证](rule-readiness/validation.md) 和 [SDD-003 验证](automation-execution/validation.md)。当时生产修复未实施；后续修复结果见下节。

本机完整证据保存在忽略目录 `app/build/reports/ci/p2/final/`（JUnit、HTML、Gradle 输出、Lint、检查器及报告检查输出）；全新检出需重新执行各验证记录中的命令。T-12 继续保持停止。

## P2 政策批准与生产修复验收

用户于 2026-10-13 批准三项政策并授权按最小清单修改生产代码。基线为 `ad01dc7` 加未提交的 P2 规格、测试、必需清单及 6 个生产文件修改；没有改动依赖、应用构建配置、CI 工作流或远端设置，没有提交或推送。结构门槛统一覆盖分析器及所有目录入口；规则执行字段变化使旧会话失效，保留已接受但未确认的动作；确认事件必须严格晚于最近接受动作，观察绝对期限保持不变。

完整运行命令与此前一致，显式使用 Android Studio JBR 和 `--rerun-tasks --no-build-cache`。JUnit 时间为 2026-10-13 16:57:08～16:57:17（Asia/Shanghai），Gradle 34 秒完成，56 个任务全部实际执行。

| 项目 | 修复后结果 |
| --- | --- |
| 全部 JVM/Robolectric | 21 个类、201 例全部通过，0 失败/错误/跳过 |
| 原 SDD-001 / P2 新增 | 原 75 例保留且通过；P2 126 例通过，含 90 例领域/集成与 36 例 SDK 36 Robolectric |
| 专项验收 | SDD-002 32 例、SDD-003 94 例通过；原 22 个失败身份全部通过 |
| 新增交错边界 | 4 个方法：挂起首次动作、补点、观察期间撤销，以及已观察撤销后恢复目录不能复活旧查询 |
| 检查器 / 规格 | 28 个检查器测试通过；规格及完整用例/产物检查结果见专项验证记录 |
| 构建 / Lint | debug 构建成功；Lint 0 错误、12 警告，分布与修复前本地报告一致 |
| 独立代码评审 | 对 6 个生产文件及相关测试、调用链静态评审，no_findings；未另行执行独立 QA |

必需清单从 197 扩展至 201 个完整身份，保留原 197 个身份及失败/错误/跳过/缺失阻断。当前证据保存于忽略目录 `app/build/reports/ci/p2/repaired-final/`，包含 JUnit、测试 HTML、Gradle/Lint、检查器、规格与产物检查、环境及文件指纹；修复前的 `app/build/reports/ci/p2/final/` 保留不覆盖。新检出须重新生成这些本机产物。

P2 本地离线验收通过。没有本轮远端 CI、设备或 Compose UI 组件证据；SDD-001/T-12 继续未完成并停止，P3 及后续阶段未启动。
