# 验证任务：规则就绪与目录

依据：[规格](spec.md)、[计划](plan.md)；实际状态见 [验证记录](validation.md)。三项政策及对应生产修复已获用户明确批准；SDD-001/T-12 保持停止。

## 任务清单

- [x] **T-01 规格与政策落盘**（AC-01～AC-06）：本包 spec、plan、tasks、validation；保留事实与目标的区别。 当前：完成。
- [x] **T-02 规则与选择器测试**（AC-01～AC-04）：RuleCandidateAnalyzerTest、RuleValidatorTest、SelectorMatcherTest；单样本、结构、数字语义、分组排序、非法条件和动态误匹配。 当前：已实现并执行，当前已列用例全部通过，证据见 validation.md。
- [x] **T-03 目录与版本测试**（AC-04～AC-06）：RuleCatalogSnapshotTest、CompositeRuleCatalogTest；严格版本、全局冲突回退、同批发布、旧计算取消及删除不恢复。 当前：已实现并执行，当前已列用例全部通过，证据见 validation.md。
- [x] **T-04 启用门禁回归**（AC-06）：保留 SDD-001/AC-06 现有 16 例交错测试和真实目录集成；拒绝请求不自动重放。 当前：已实现并执行，当前已列用例全部通过，证据见 validation.md。
- [x] **T-05 偏差分类与修复清单**（AC-02）：新政策独立反例；核对测试替身和实际结果，记录最小生产修复边界并按明确授权实施。 当前：RR-01 已修复，原 10 个失败反例全部通过，证据见 validation.md。
- [x] **T-06 验收收尾**（AC-01～AC-06）：逐 AC 证据、既有 75 例、CI 检查器、规格检查、构建、Lint；全部目标满足后再标记完成。 当前：本地离线验收通过；专项 32 例和完整 201 例全部通过，原 75 例保留；规格与报告检查均通过，完整证据已归档。

## 验收追踪表

| 条件 | 任务 | 测试组织 | 关键观察 | 当前结果 |
| --- | --- | --- | --- | --- |
| AC-01 | T-02 | `RuleCandidateAnalyzerTest` | 资格过滤、单样本、分组和 Activity 隔离 | 已列用例通过 |
| AC-02 | T-02、T-05 | `RuleCandidateAnalyzerTest、RuleValidatorTest` | 纯文本候选、内置入口拒绝、稳定结构正例 | 已列用例通过，RR-01 已修复 |
| AC-03 | T-02 | `RuleCandidateAnalyzerTest、SelectorMatcherTest` | 数字语义、祖先距离和倒计时误匹配 | 已列用例通过 |
| AC-04 | T-02、T-03 | `RuleValidatorTest、CompositeRuleCatalogTest` | 全部非法字段、本机优先、跨目标冲突回退 | 已列用例通过 |
| AC-05 | T-03 | `CompositeRuleCatalogTest` | 发布顺序、旧计算取消、删除和会话更新 | 已列用例通过 |
| AC-06 | T-03、T-04 | `RuleCatalogSnapshotTest、RuleCaptureEnableInterleavingTest` | 版本矩阵和显式开启门禁 | 已列用例通过 |

全部列出的测试类已实现并执行；真实类/方法、修复前失败场景和剩余限制见 [验证记录](validation.md)。任务勾选表示测试或记录工作已完成，不把断言失败算作契约通过。本地离线验收通过；T-06 已完成规格及报告检查核验，不代表设备或远端验收。
