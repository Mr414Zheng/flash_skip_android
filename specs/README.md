# FlashSkip 项目规格索引

本目录按业务能力组织规范驱动开发（SDD）的规格、实施任务和验收证据。项目基线为 `e354a99`，P1 实施基线为 `97d3010`，首次远端验收对应 `85fcd27`，更新日期为 2026-10-14。P0 文档及 P1 本地、远端 CI 验收已完成；[PR #1](https://github.com/Mr414Zheng/flash_skip_android/pull/1) 已合入 main，合并提交为 `2818232`，[主干 CI](https://github.com/Mr414Zheng/flash_skip_android/actions/runs/38362313424) 通过。P2 三项政策已审定并完成生产修复，本地、PR 及合并后主干 CI 验收通过，P3 最小生产修复及真实服务集成完成，本地、PR 与合并后主干 CI 验收通过，PR #4 已合入 main（`471ccd2`），P4 最小生产修复与本地契约验收已通过，P5～P6 尚未执行，不代表全项目已完成 SDD 改造。

P2 基线为 main `ad01dc7`（文档 PR #2 已合并）。用户已批准稳定结构、目录失效和最近接受动作事件三项政策并授权最小生产修复。本轮完整重跑 201 例全部通过，包括原 75 例和 P2 新增 126 例；构建、Lint 及证据检查结果见验收记录。其余契约参数沿用基线，不扩大为后续阶段授权。已按续授权将 [PR #3](https://github.com/Mr414Zheng/flash_skip_android/pull/3) 合入 main（`c2a286b`），主干 CI `38376388018` 通过且报告已下载核验，见 [主干验收记录](ci.md#p2-合并与主干-ci-验收)，不修改远端设置、应用依赖或构建配置。`capture-save-retry` 的 T-12 未完成，已按用户要求停止；不继续排查京东广告复现原因，也不恢复剩余设备验证。

## 项目级入口

| 文档 | 用途 |
| --- | --- |
| [能力清单](capabilities.md) | 业务边界、实现入口、已有证据、验证缺口和优先级 |
| [公共约束](constraints.md) | 授权边界、术语、已有契约、实现事实与证据规则 |
| [分阶段计划](roadmap.md) | P0～P6 的依赖、产出、完成标准与当前状态 |
| [持续检查](ci.md) | P1 工作流、阻断规则、本地与远端验证证据及主干落地限制 |
| [仓库说明](../AGENTS.md) | 目录约定、构建命令、测试和提交要求 |

## 规格登记表

以下编号为稳定引用标识。SDD-001 已审定；SDD-002、SDD-003 的三项政策已审定，其他参数沿用基线，已完成本地离线验证；SDD-004、SDD-005 的五项政策已确认，最小修复和本地、PR 及主干 CI 契约验收通过，SDD-006、SDD-007 的 P4 政策及最小修复已确认，28 个原失败关闭，全量 594 例通过，其余仍为规划条目；尚未创建的预留目录以文本列出，不作为可访问链接。批准 P0 文档不等于批准这些能力的全部行为或实施。

| 编号 / 能力 | 规格包 | 契约状态 | 验证状态 | 阶段 |
| --- | --- | --- | --- | --- |
| SDD-001 / C01 保存与安全重试 | [capture-save-retry](capture-save-retry/spec.md) | 已审定 AC-01～AC-08 | 必需自动化通过；T-12 未完成且停止 | 已有试点 |
| SDD-002 / C02 规则就绪与目录 | [rule-readiness](rule-readiness/spec.md) | 三项政策已审定；其余沿用基线 | 32 例全部通过；见 [验证记录](rule-readiness/validation.md) | P2 |
| SDD-003 / C03 自动执行与动作确认 | [automation-execution](automation-execution/spec.md) | 三项政策已审定；其余沿用基线 | 94 例全部通过；见 [验证记录](automation-execution/validation.md) | P2 |
| SDD-004 / C04 采集生命周期 | [capture-lifecycle](capture-lifecycle/spec.md) | 五项政策已确认；其余沿用基线 | 196 例全部通过，含 14 例真实服务／存储集成；见 [验证](capture-lifecycle/validation.md) | P3 |
| SDD-005 / C05 采集证据与目标识别 | [capture-evidence](capture-evidence/spec.md) | 五项政策已确认；其余沿用基线 | 82 例全部通过；见 [验证](capture-evidence/validation.md) | P3 |
| SDD-006 / C06 持久化与兼容 | [persistence-compatibility](persistence-compatibility/spec.md) | P4 政策与最小修复已授权 | 68 例全部通过；见 [验证](persistence-compatibility/validation.md) | P4 |
| SDD-007 / C07 规则管理 | [rule-management](rule-management/spec.md) | P4 政策与最小修复已授权 | 47 例全部通过；见 [验证](rule-management/validation.md) | P4 |
| SDD-008 / C08 服务与首页交互 | `service-and-home/` | 待编写、审定 | 保存相关 ViewModel 状态有部分覆盖 | P5 |
| SDD-009 / C09 诊断与数据边界 | `diagnostics/` | 待编写、审定 | 尚无专项契约测试 | P5 |

SDD-001 的 [计划](capture-save-retry/plan.md)、[任务](capture-save-retry/tasks.md) 和 [验证记录](capture-save-retry/validation.md) 保留为该试点的事实来源。2026-10-13 本地及 P1 远端 Linux 均执行了现有 75 例测试并全部通过；P0 本身没有重跑测试，不将该结果扩展到其他能力。

## 规格包与追踪方式

每个能力包沿用 `spec.md`、`plan.md`、`tasks.md`、`validation.md` 四份文档：分别记录行为与验收条件、实现边界与顺序、可执行任务、实际验证证据。不为每个源文件或类单独建立规格包。

跨文档引用使用完整标识，例如 `SDD-001/AC-06`、`SDD-001/T-12`，避免不同规格包的局部编号冲突。每条验收条件至少关联实现入口、任务、测试类与方法或验证步骤、结果、证据层级及剩余限制。未创建测试或没有证据时明确填写缺口，不用代码位置替代验证结果。

契约状态与验证状态分别维护：

- 契约状态：待编写、待审定、已审定、已替代。实现中观察到的行为不自动成为已审定契约。
- 验证状态：未执行、通过、产品失败、环境阻塞；另记录“按用户要求停止”等执行决定。停止不能折算为通过。
- 能力结论：自动化、组件集成和设备证据分别报告，不用一个“完成”标签掩盖未验证层级。

新增行为先定位已有能力；确实属于新能力时才登记新编号。后续修改已审定契约，应说明触发原因、受影响 AC、兼容性及验证变化。P0 建立这些记录约定；P1 的 CI 和 PR 自动检查已通过远端验收并落入主干，执行规则、运行记录与尚未配置必需检查的限制见 [持续检查](ci.md)。

## P3 当前范围

用户续授权五类政策最小生产修复与必要内部测试接缝，已完成。完整 479 例全部通过，保留修复前 453 个身份与预期（含原 201 例）；本次新增 26 例。构建、28 个检查器、规格及产物门禁通过，Lint 0 错误／12 警告。真实服务与实际 DataStore 集成在 Robolectric SDK 36 下通过；不等于设备证据。原 42 个失败的历史报告保留，详见两包验证记录。

2026-10-14 用户续授权新分支提交、推送、创建 PR、等待远端 CI 并核验产物后回写证据；当时暂不合并，后续已获合并与主干验收授权并完成。不修改依赖、构建配置和远端设置；SDD-001/T-12 未完成且继续停止，P4 以后未启动。

[PR #4](https://github.com/Mr414Zheng/flash_skip_android/pull/4) 已创建并推送；[CI 38491677713](https://github.com/Mr414Zheng/flash_skip_android/actions/runs/38491677713) 的 479 例、28 个检查器、构建及 Lint 通过，报告已下载核验，详见 [远端验收](ci.md#p3-pr-与远端-linux-ci-验收)。PR 已按后续授权合入 main；[主干 CI 38493360255](https://github.com/Mr414Zheng/flash_skip_android/actions/runs/38493360255) 通过且报告已下载核验，见 [主干验收](ci.md#p3-合并与主干-ci-验收)。
