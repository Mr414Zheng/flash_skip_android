# 实施计划：SDD-009

依据 [规格](spec.md)，任务见 [任务](tasks.md)，证据见 [验证](validation.md)。

1. 从 AppContainerRuntime、CaptureTraceLogger 及四类其他输出入口建立诊断出口清单，区分诊断标识与业务 DataStore 字段。
2. 用全新 logger 和 Robolectric ShadowLog 验证开关、字段、重复合并、迟到事件及诊断异常；通过真实 AccessibilityActionEvidence 路径检查动作日志。
3. JVM 测试 CaptureDiagnosticTrail；真实临时文件与手动异步 Executor 验证 CaptureDiagnosticJournal，使用目录文件冲突、执行器拒绝及原始损坏索引控制故障，不靠 sleep 或 chmod 偶然行为。
4. 默认配置检查容量／保留数，缩小合法容量检查边界及重开；对应默认值的源码只读核对另列静态证据，不混称文件写入规模已实测。
5. 用现有 Debug 和 Release 变体运行 DiagnosticBuildBoundaryTest；读取最终合并 Manifest、备份排除和 no_backup 装配。Release 专项单独报告，不重复计入新增测试身份。
6. 全量回归，保存源码指纹、测试报告、命令、失败身份与 AC 映射；更新必需身份。

## 接口与修复方向

本轮沿用 CaptureDiagnosticSink、已有 logger 构造和 journal 执行器参数，不新增生产接缝。后续最小修复预计需要统一进程内诊断标识以及快照提供者错误隔离；不能改业务选择器、持久化编码或凭替代 token 宣称匿名化。跨进程标识重置的正向验证要在方案获授权后补齐。

日志后端异常注入目前只覆盖已有 sink 接缝及 recordSafely 包装；Android Log 自身失败没有独立注入接缝，静态捕获不能冒充执行证据。文件测试证明正常进程内调用、限额及重开，不能证明断电或 OEM 备份行为。

## 续授权修复实施

2026-10-14 用户授权修复全部 19 个失败及必要内部测试接缝，保留 701 个身份与预期，补回归并重跑完整本地验证。以上“不新增接口／不改生产”描述首轮阶段；本节为当前实施。

1. 增加内部 ProcessDiagnosticIdentifiers，以每进程随机且不持久化的密钥及命名空间生成关联 token；业务 ID、选择器及 v2 编码保持原样。
2. 统一自动执行、扫描、动作重验／回调和管理出口；采集日志使用进程范围与会话序号，journal 接收进程内映射的数值会话 ID，索引 schema 和容量不变。
3. 两个诊断快照提供者独立隔离并输出固定错误分类；日志后端与本地 sink 独立失败。内部构造可注入标识密钥与日志后端用于可重复验证，默认生产装配不变。
4. 新增 5 JVM 和 10 Robolectric 组件回归，本包累计 74 例通过。通过两组受控密钥验证作用域隔离，不冒充真实操作系统进程重启。
5. 保留 Release 单测任务缺失的原始证据；核对配置源指纹后复用两变体静态配置，不修改构建。
