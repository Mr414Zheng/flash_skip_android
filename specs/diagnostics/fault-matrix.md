# P5 诊断出口故障矩阵（2026-10-16）

关联 [SDD-009/AC-03](spec.md)、[P5 收尾](../p5-closeout.md)。本轮没有产品行为修复，仅把服务摘要的两个既有惰性表达式移入内部可替换提供者；默认表达式、时钟读取时机、业务保存／恢复快照及默认 AppContainer 装配不变。

静态清单 `app/build/reports/p5-closeout/diagnostic-call-sites.json` 列出基线加补丁的 85 个诊断调用位置，包含服务、采集 adapter、AppContainer、管理／存储及直接 Android Log。按共同出口和异常隔离边界归组如下；动态 stdout 的 `FAULT_POINT` 记录 stage、出口及生产调用栈，`fault-observations.json` 可逐项检索实际命中。85 个调用位置不是 85 个独立故障用例，也不表示全部日志文案分支已逐一执行。

| 生产调用点／出口 | 注入位置与实际路径 | 正控制、业务结果和恢复 |
| --- | --- | --- |
| `CaptureTraceLog` 的 Android Log 输出 | `DiagnosticBackendFaultShadow` 拦截实际 Log.d/i/w/println；从服务生命周期、配置、状态、摘要和新采集链调用；交替 IOException 与诊断 CancellationException | 原 30 个服务测试为无故障正控制，`DiagnosticServiceBackendFaultTest` 原样继承 30 个；存储状态、监听配置、恢复门禁和 ACTION_CLICK 断言不变 |
| 全局采集 sink 的 append／beginSession | `DiagnosticServiceSinkFaultTest` 注入实际全局 sink，30 个原业务场景；新会话沿管理 REQUEST_CAPTURE 到 beginSession；后端未被一并替换 | 每个 sink 故障用例要求实际命中；业务取消、存储和恢复继续；后端错误与 sink 错误分别实验 |
| 服务摘要两个惰性快照提供者 | `DiagnosticServiceProviderFaultTest` 通过两个内部提供者抛异常，实际服务仍调用全局 logger 的独立 provider 隔离 | 22 个达到摘要的场景分别要求两个 provider 都命中；8 个首读／不可用／失败恢复场景明确要求 0 命中，作为未到摘要的业务控制，不称为注入成功 |
| 自动执行 `AutomationTraceLog` | 实际 AppContainer 生产引擎 logger，经服务事件、真实目录和 DataStore；backend shadow 抛异常 | 记录 SESSION_START、QUERY、NODE_CLICK、ACTION_ACCEPTED、FINISHED／INVALIDATED 等实际命中；故障下仍得到 ACTION_CLICK、正确暂停／恢复及保存状态 |
| `AccessibilityScanTraceLog` | 实际 gateway／finder 扫描；Android Log 后端故障 | CANDIDATE_CHECK、SCAN_RESULT 命中，仍解析目标并执行动作；扫描失败的既有契约继续全量回归 |
| `AccessibilityActionEvidence` | 实际节点动作证据采样；Android Log 后端故障 | ACTION_EVIDENCE_READ 命中，动作前证据及之后执行继续 |
| `AccessibilityPageGateway` 动作重验／手势回调 | ACTION_REVALIDATED 及 GESTURE_CALLBACK 的直接 Log 后端故障；通过真实服务窗口和真实 gateway | `DiagnosticServiceContinuationTest` 有无故障正控制、Completed／Cancelled 回调结果、取消等待后迟到回调不能恢复及后续协调器操作 |
| `RuleManagementTraceLog` 与 coordinator／DataStore 的 recordSafely | 实际 stores／runtime 启用管理 trace；其输出进入同一全局采集 logger；backend 和 sink 分开故障 | REQUEST_CAPTURE／配置、取消、开关、读取／提交等实际路径；操作结果、原样本与后续动作断言；业务取消不被诊断隔离吞没 |
| `AppContainer` 启动及持久状态诊断观察器 | `DiagnosticApplicationObserverTest` 的真实 `diagnosticsEnabled=true` 装配；从 observer started、persisted state、storage unavailable 到 completed；backend／sink 故障分别注入 | 正常控制＋backend＋sink 三例；未知版本期间禁止动作，恢复有效原始格式后同一服务重新 ACTION_CLICK，原样本保持；每个声明 stage 要求命中 |
| AppContainer 的真实 journal 失败通知 Log.w | 真 AppContainer 创建 journal，no_backup 父路径使用文件形成确定 IO 失败；实际后台通知到 Android Log shadow 并抛异常 | 正常通知／后端失败两例，通过 CountDownLatch 证明实际通知到达；之后真实存储／服务业务仍继续，不以无异常替代业务断言 |
| `CaptureDiagnosticJournal` IO、executor 与 onFailure | `DiagnosticServiceContinuationTest` 把实际全局采集出口接到真实 journal：目录文件冲突、executor 拒绝；onFailure 本身抛诊断 CancellationException | 同一业务链无故障正控制；修复目录／解除拒绝后仍 ACTION_CLICK，新的管理记录写入且带分类失败恢复计数；不输出异常消息，不重放失败事件 |
| 轨迹回调及已有日志关闭／隐私边界 | 保留原 CaptureDiagnosticTrail、DiagnosticOutputContract、DiagnosticPrivacyRegression 等组件测试 | 原 735 身份全部通过，覆盖回调异常、后续记录、字段最小化和关闭时无惰性读取；不将组件证据计作新增真实服务用例 |

新增服务集成合计 **106** 例：三组继承矩阵 90 例、`DiagnosticServiceContinuationTest` 11 例、`DiagnosticApplicationObserverTest` 5 例。原 30 个服务方法的代码和断言逐字保持；它们作为无故障控制不重复计入新增数。完整 Debug 中旧服务类加新增矩阵共 136 例；Robolectric 是实际服务类／AppContainer／真实 DataStore 的受控集成，不是 OS 投递或独立 QA。

`DiagnosticServiceContinuationTest` 的新采集流程从真实请求、服务配置、已验证窗口、观察候选，到点击证据升级 CAPTURED，再取消；健康／backend／sink 三种条件都断言相同会话状态、证据类型与持久样本。测试中的目标页面和节点为合成数据，没有执行京东或 T-12 的设备场景。

业务取消有独立正控制、backend 故障和 sink 故障三例：真实 DataStore 写入屏障前取消协程，调用者收到 CancellationException、原状态不变；解除屏障后重试成功、服务继续 ACTION_CLICK。诊断自身 CancellationException 被隔离，不改变业务 scope 的取消语义。

本矩阵覆盖本轮约定的相关诊断出口／隔离边界，并保留各个实际调用栈；不作每条业务异常分支、全部文案、所有错误类型笛卡尔积或并发组合穷尽声明。后续出现新的直接出口、提供者或绕过隔离边界的调用时，不能仅凭此矩阵复用通过结论。
