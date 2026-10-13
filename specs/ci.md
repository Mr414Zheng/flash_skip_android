# P1 持续检查与本地验证

状态：本地配置与验证已落实，远端 CI 尚未运行；P1 的 Linux 新检出验收仍待后续授权。实施基线为 `97d3010`，日期为 2026-10-13。用户授权本地 CI 配置、检查脚本、负例验证和文档更新；不推送、不修改远端设置，不修改生产代码，不恢复 SDD-001/T-12。

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
| [必需用例清单](../.ci/required-tests.json) | 从 `e354a99` 的现有套件登记 5 个类、75 个完整用例身份，包括参数化阶段；允许新增测试，删除/改名/参数变更必须明确评审后同步清单。数量相同不能抵消必需用例缺失 |
| [本地证据例外](../.ci/local-evidence-links.json) | 仅豁免已有验证文档中逐条登记的 `app/build` 历史链接，不要求新检出具有本机产物。未使用的例外也报错；新建错误链接不会自动豁免 |

规格包从 [登记表](README.md) 读取；预留目录是普通代码文本，不当作已存在的规格链接。当前检查支持行内 Markdown 链接、ATX 标题和完整 `SDD/AC`、`SDD/T` 引用，不验证远端 URL 可达性，也不展开裸写的编号区间。PR 字数只是防空白的最低门槛，引用与变更的语义关联、验证声明真实性仍由评审负责。推送/手动事件没有 PR 正文，仅执行仓库检查。

测试报告脚本校验输入证据，不单独保证报告来自本次运行；工作流的新检出、强制执行与禁用构建缓存共同保证本次生成。直接本地调用报告脚本时，须先执行下面的 Gradle 命令。

## Lint 与报告保留

本轮历史警告清单如下。Lint 错误阻断，警告完整报告；首版不启用 `warningsAsErrors`、不添加 Lint baseline、不自动升级或修改生产代码。新增警告需要评审，目前不按警告差量自动阻断。

| Lint ID | 数量 |
| --- | --- |
| OldTargetApi | 1 |
| AndroidGradlePluginVersion | 1 |
| GradleDependency | 5 |
| NewerVersionAvailable | 1 |
| ObsoleteSdkInt | 1 |
| MonochromeLauncherIcon | 2 |
| 合计 | 11 |

每次运行按 run ID 和 attempt 分别保存 Specs 与 Android artifact，保留 14 天。包含检查器输出、提交与工具链信息、JUnit XML、测试 HTML、Lint HTML/XML。缺少必需报告会失败；不会上传 APK、设备截图、DataStore 导出或设备诊断日志。Artifact 上传的实际权限和下载可用性须由首次远端运行验证。

## 本地执行与验证记录

在仓库根目录执行；需要 Python 3.9+、JDK 21 和现有 Android SDK：

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts/ci/tests -v
PYTHONDONTWRITEBYTECODE=1 python3 scripts/ci/check_specs.py
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --rerun-tasks --no-build-cache --continue --console=plain
PYTHONDONTWRITEBYTECODE=1 python3 scripts/ci/check_test_results.py --require-artifacts
```

macOS 本轮使用 Android Studio 自带 JBR：`JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home`。本地模拟 PR 检查时传入 `--event-name pull_request --event-path /path/to/event.json`，JSON 中填写 `pull_request.body`；内容作为数据读取，不插入 shell。

2026-10-13 本轮实际验证结果如下；生产及业务测试代码均与 `97d3010` 相同。

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
| 改动边界 | `app/src`、应用/根构建脚本、`gradle/`、Gradle 属性和 settings 均无差异；没有执行推送或远端设置变更 |

检查器测试覆盖缺少报告、XML 损坏、零用例、必需参数化用例消失、同数量替换、重复用例、失败/错误/跳过、Lint 错误、错误链接/锚点/引用及空 PR 模板。Gradle 输出中的 `preDebugAndroidTestBuild SKIPPED` 是未配置设备测试的准备任务，不是 JUnit 用例跳过，也不提供设备验证证据。

本地原始报告位于 `app/build/test-results/testDebugUnitTest/`、`app/build/reports/tests/testDebugUnitTest/`、`app/build/reports/lint-results-debug.*`；P1 检查输出位于 `app/build/reports/ci/`。这些产物被 Git 忽略，全新检出不包含它们。远端 artifact 仍未生成。

## 阶段结论与后续条件

- 本地交付包括 CI 配置、检查脚本、负例测试、PR 模板、历史警告登记及项目说明更新。
- 未推送、未创建 PR、未修改 Actions 策略或分支保护；本地两次既有提交仍未推送。
- 本地 macOS 的执行与工作流静态检查不能替代 Linux 全新检出。首次远端运行、artifact 下载和 Actions 启用策略仍待验证，因此 P1 整体不标记全部完成。
- 后续获授权后推送包含既有测试/P0 文档的变更，验证 Linux 全新检出运行；再按管理授权把 `Specs`、`Android` 配为 main 必需检查。
- SDD-001/T-12 未完成并继续停止。本轮不产生任何新增设备验证证据，不以 CI 通过替代设备结论。
