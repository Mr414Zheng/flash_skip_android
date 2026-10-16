# P5 两个 T-05 本地收尾（2026-10-16）

本轮从 main `1e696980021d1ea9fabded34b294ed3648d17c3d` 建立隔离工作区，带入已整理的 13 份 T-12 文档。原工作区的三个诊断生产文件和新增时序测试没有混入基线，收尾时原工作区逐文件摘要未变。T-12 用户手工验收结论、旧安装包归属及留证限制原样保留；没有恢复京东调查，也没有重跑 D-01～D-05。

用户授权测试、必要内部接缝、测试依赖／构建配置、CI 门禁、限定 P5 系统验证及证据文档；本轮没有产品行为修复、持久化格式／公共业务接口变更、提交、推送、PR、远端设置或发布操作。SDD-008/T-05 与 SDD-009/T-05 按下列明确范围完成本地契约验收；这不表示其他 OEM/API、跨阶段全部 G-SYS 或发布就绪。

## 本地最终结果与来源

| 项目 | 最终结果 |
| --- | --- |
| Debug 全量 | 69 类／874 例，0 失败、错误、跳过；原 735 身份全部保留并通过 |
| 新增 Debug | 139 例：32 Compose/Robolectric、1 独立 JVM 进程测试、106 真实服务／AppContainer／实际 DataStore 集成与正控制 |
| Release 专用 | 2 类／6 例，实际 `BuildConfig.BUILD_TYPE=release`、`DEBUG=false`；不重复计入新增 Debug 身份 |
| 构建 | Debug APK 与未签名 Release APK 均成功；Release R8／资源收缩正常完成 |
| Lint | Debug、Release 均 0 错误／12 警告，无新增忽略规则；修正新增测试的 Composable 命名警告后复跑 |
| CI 检查器 | 39 例通过，保留原 28 例，新增 11 个 Release 检查器测试 |
| 身份与产物门禁 | Debug 874、Release 6 的各自清单／JUnit／HTML／Lint／非空 APK 校验通过 |
| 历史保持 | 原 30 个服务测试体逐字不变；仅开放夹具、诊断参数和故障观察；历史 19 个 P5 红例及先前报告未删除 |

最终执行：

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug :app:lintRelease --rerun-tasks --no-build-cache --continue --console=plain
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts/ci/tests -v
PYTHONDONTWRITEBYTECODE=1 python3 scripts/ci/check_specs.py
PYTHONDONTWRITEBYTECODE=1 python3 scripts/ci/check_test_results.py --require-artifacts
PYTHONDONTWRITEBYTECODE=1 python3 scripts/ci/check_test_results.py --variant release --require-artifacts
```

本地原始材料位于被 Git 忽略的 `app/build/reports/p5-closeout/`：`baseline.json`、`final-summary.json`、`implementation-fingerprints.json`、`final/` 的两变体 JUnit/HTML/Lint/命令日志、`diagnostic-call-sites.json`、`fault-observations.json`、`device/` 和文件摘要清单。原始报告不进入 Git。 实现文件清单的 SHA-256 为 `11e1da5b2e67c1d7ac6320f98e43f5185381805cb60744e946a7f2e9d057d2ad`；`automated-reports.zip` 为 535529 字节，SHA-256 为 `6c9eb1c4075b645e3b993a44a4264cee4b48a1c341188d1e4e1dc5a7858130cd`，解压后的报告与变体清单已重新校验。独立进程的 PID/token 记录在 `ProcessDiagnosticRestartTest` 的 JUnit stdout；只使用固定合成输入，不输出密钥或页面内容。

CI 定义保留原 Debug 门禁，增加 Release 专用任务、清单、变体标记检查及两变体报告归档；负例覆盖缺报告、缺用例、重复身份、失败／错误／跳过、错误清单与运行变体、空套件和缺产物。本轮仅执行本地等价门禁，**未执行修改后的远端 CI**，后续需获提交／PR 授权再验收。

## 15 条 AC 对照

| AC | 本轮补充证据 | 判定及边界 |
| --- | --- | --- |
| SDD-008/AC-01 | `HomeScreenComposeTest` 四态、开始门禁、服务不可用开关；原服务首读／恢复／中断测试；P01～P03、E01～E06、E09～E13 | 通过；准备耗时／存储故障精确边界用受控测试，手机只记录实际状态 |
| SDD-008/AC-02 | 原 `ServiceStatusContractTest`、`ServiceAvailabilityRegressionTest` 全量回归 | 通过；1999/2000ms 不以人工观察代替受控时钟 |
| SDD-008/AC-03 | 原导航适配器测试；P02/P04、E02/E08 实际设置／桌面到达 | 通过；截图与前台 Activity 支持到达，非仅 STARTED |
| SDD-008/AC-04 | `HomeRouteComposeTest` 实际 Route＋ViewModel＋协调器：RESUMED、缺接收者、配置停止与 composition 重建、取消／替换／过期；E09/E11 真进程终止 | 通过；受控生命周期与 OS 进程证据分列 |
| SDD-008/AC-05 | Route 桌面失败、取消落盘失败后重试、设置失败；P05 返回／锁屏；E14 实际 Activity 重建 | 通过；设备 E14 为说明弹窗重建，待消费会话的精确配置竞态由 Route 控件测试证明 |
| SDD-008/AC-06 | Route 旧删除失败不能覆盖新设置失败；原操作归属／返回交错回归 | 通过；断言反馈、会话与存储结果 |
| SDD-008/AC-07 | 19 个 Screen 控件用例＋13 个 Route 用例：点击、按钮/开关角色、选中／禁用状态、说明／删除对话框、重复点击、失效目标、重试和状态恢复 | 通过；真实 Compose 语义与动作，Screen 回调记录不冒充业务集成，Route 单独连接真实业务对象 |
| SDD-009/AC-01 | 共享 `DiagnosticBuildBoundaryTest` 在两个变体执行；Release 专用全局 logger、runtime、文件、两个惰性提供者及时钟屏障 | 通过；时钟测试含直接调用会抛异常的正控制，不声明设备 Release 全出口执行 |
| SDD-009/AC-02 | 原隐私／最小化回归；`ProcessDiagnosticRestartTest` 启动两个独立 JVM，真实 Process 单例、相同合成输入 | 通过；各进程内 token/文件 ID 一致，跨进程 PID/scope/token/文件 ID 不同；不构成匿名化声明 |
| SDD-009/AC-03 | [诊断出口故障矩阵](diagnostics/fault-matrix.md)，106 个新增服务集成／正控制及原诊断组件回归 | 通过；记录实际命中、业务结果、继续处理与取消传播；不宣称每个文案分支或异常组合穷尽 |
| SDD-009/AC-04 | 原合并、迟到会话、48 条轨迹、重置及重复计数回归 | 通过；完整 Debug 重跑 |
| SDD-009/AC-05 | 原真实文件／队列回归；新服务 journal IO、执行器拒绝、失败通知自身异常及恢复后 ACTION_CLICK | 通过；不承诺退出前队列无损 |
| SDD-009/AC-06 | 原 UTF-8、截断、容量、轮转、会话顺序及文件重开回归 | 通过；沿用已登记默认容量静态核对与缩小合法容量的动态方法 |
| SDD-009/AC-07 | 原损坏索引保留／修复恢复回归；真实服务故障后继续持久化与执行 | 通过；无自动修复界面，不承诺失败事件重放 |
| SDD-009/AC-08 | 两变体构建和 `DiagnosticBuildBoundaryTest` 最终 Manifest／数据提取排除／no_backup 装配检查 | 通过静态／本地运行契约；OEM 备份迁移仍未验证 |

32 个 Compose 测试运行于 Robolectric SDK 36 的真实 Compose 宿主。Route 使用真实 HomeViewModel／RuleCaptureCoordinator，存储和时钟由夹具控制；生命周期通过 LifecycleRegistry 驱动，configuration stop 调用真实 ViewModel 回调，恢复真实 composition。它不是 Android instrumentation，也不把 composition 重建冒充 OS Activity 重建。E14 的系统生命周期日志另有 `wm_on_destroy_called`、`wm_on_create_called`、`wm_relaunch_resume_activity`，PID 未变。

## 安装包与系统证据

- 实体机：OnePlus PKX110，Android 16／API 36，`PKX110_16.0.10.500(CN01)`；包名 `com.zhenghuaizhi.flashskip.debug`、版本 `0.1.0-debug`、versionCode 1。
- 模拟器：本轮独立 AVD `FlashSkip_P5_API36`，API 36 default arm64-v8a r2，Pixel 6 配置；没有复用或修改此前 T-12 的 API 35 AVD。结束时恢复旋转设置并关闭模拟器。
- 安装前旧诊断 APK SHA-256：`5343f08880caf6b0a9835a7c7f5c5199e0d089a74085fcf8681d2a1baa564f02`，保留 T-12 历史归属；只读取设备上摘要／版本，没有复制旧 APK。
- 本轮两设备实际安装的 Debug APK SHA-256：`d1c128bb0aedb7c4254f39f44d1a9e20774cf68631945477f3c7d16bfef84f2f`，`adb install -r` 保留数据。`device/apk-provenance.json` 保存构建时所有 main 源码／资源及构建输入摘要；最终重新核对全部一致。
- 最终全量重建 Debug APK SHA-256：`9ac3aafa9af41a71954f13fdf8fa6684fb51a88bf38139c11604376a41c6a40b`；未签名 Release APK：`cd2738fdbd2a30545dfed46169f66cdddead74a5f2dc56353eed66e0394d7808`。重建字节摘要不同，设备记录归属上述实际安装包，不能称最终重建二进制已在设备运行。

| 场景 | 操作、实际结果及留证 |
| --- | --- |
| P01 禁用 | 用户在系统关闭服务后返回；首页“需要开启”，既有 QQ 音乐／知乎仍显示“已开启，暂时无法使用”，开始受阻。`physical/p01-*` |
| P02 显式关闭／设置 | 用户保持服务关闭，明确关闭 QQ 音乐开关并点“去开启”；前台为系统 AccessibilitySettingsActivity。`p02-*` 及用户确认 |
| P03 重新启用 | 用户接受系统授权后返回；直接截图为“已准备好”，两个应用开关均关闭。用户确认 QQ 音乐仍保持关闭，知乎的关闭不归因为系统自动清除。`p03-screen-only.png`、系统绑定快照及用户确认 |
| P04 桌面到达 | 用户点击重新添加／确认开始，实际到桌面；用户确认只跳转一次。`p04-desktop.png`、`p04-activity.txt`、会话日志 |
| P05 返回／锁屏 | 用户从桌面重开、锁屏／解锁返回；确认未重复跳转且开关仍关闭。`p05-return-lock.png` 与 Activity 快照支持最终首页；锁屏过程与跳转次数按用户手工观察，不冒充连续录像。未打开目标应用，提示 RETURNED_BEFORE_TARGET 符合前置条件 |
| E01～E08 授权和到达 | 通过模拟器实际系统入口、服务开关和 ALLOW 授权；直接截图就绪，点击确认后前台实际为 QuickstepLauncher，日志进入 WAITING_FOR_TARGET。E07 名为 desktop 的初次截图实际是确认弹窗，不算到达证据；实际到达使用 E08 |
| E09 普通进程终止 | 宿主 `adb shell run-as ... kill -9 2669`；立即无 PID，系统约 2 秒后拉起 PID 3086。新进程先读到 WAITING_FOR_TARGET，再由服务恢复结算为 SERVICE_UNAVAILABLE；主动重开仍在 MainActivity、就绪且未重放旧导航。`e09-kill-process.json`、`e09-logcat.txt`、截图 |
| E11 强制停止 | 新会话确认进入 WAITING_FOR_TARGET／桌面后 `am force-stop`；PID 3086 消失，等待后仍无进程，主动启动为 3187。首页“需要开启”、开始禁用，未重放桌面导航；服务需实际重新授权。不能等同普通 kill 的自动重绑定。`e11-*` |
| E12～E14 重连／配置重建 | 再经系统服务开关授权后恢复就绪。说明弹窗期间旋转横屏再回竖屏：系统日志确认 MainActivity destroy/create，PID 3187 不变，弹窗保持且无自动开始／导航；恢复旋转原值 `accelerometer_rotation=1,user_rotation=0`。`e14-*` |

取证修正：早期 `uiautomator dump` 抓到“正在准备”，而用户持续报告就绪；其后不用 UiAutomation 的直接截图与系统绑定确认就绪。层级抓取存在扰动服务的可能，旧抓取不能作为持续准备的产品缺陷证据，也不能证明自然准备耗时。服务状态采用后续直接截图／系统记录；没有据此修改产品或新增忽略规则。

## 结论与保留限制

本轮列出的 Compose、实际 Release、两个真实 JVM 进程、诊断出口故障边界以及关键系统场景均有对应通过证据，两个 T-05 本地收尾。没有未关闭的本轮产品红例；新夹具调试中发现的重复标签、缺失桌面解析前置条件及“跳过”候选应进入待确认阶段均按既有契约修正夹具或新断言，未修改原测试预期或生产行为。

继续保留：未测 OEM/API、真实 Binder／窗口全部组合、OEM 备份／迁移、断电／崩溃中间写入、设备 Release 全出口运行、远端新 CI 和独立 review／QA。G-SYS 仅补齐这里列出的 P5 子项；G-STOP/T-12 留证限制不变。故障矩阵按实际诊断出口及共享隔离边界验收，不宣称所有业务路径、每条日志文案或所有故障组合均有独立执行。变更新增出口或绕开这些边界时必须重新验证。

## 交付准备复查（2026-10-16）

用户随后明确授权新分支、提交／推送／PR、远端 CI 与报告核验、检查通过后合并及 main CI 验收。作者重新审查全部 41 个改动文件及相关调用链，未发现达到报告门槛的代码缺陷；这不是独立 review／QA。重新通过 39 个检查器、规格／PR 格式检查和两变体报告／产物门禁，原 735 个身份及 30 个服务测试体保持。

暂存新文件时发现 `DiagnosticServiceFaultMatrixTest.kt` 末尾一个多余空行，已删除；除这一项纯空白差异，18 个非文档补丁均与上方本地验收指纹一致。上方摘要和归档仍对应原测试执行时的字节，不改写历史报告；交付提交另由其自身 PR 和合并后 main CI 验证，最终运行／报告核验结果在交接中记录。原工作区 17 个改动路径的摘要与隔离前记录完全一致。
