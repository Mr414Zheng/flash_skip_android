# 验证任务：自动执行与动作确认

依据：[规格](spec.md)、[计划](plan.md)；实际状态见 [验证记录](validation.md)。用户已审定三项跨包新增政策并明确授权最小生产修复；本包修复及本地离线验证完成，SDD-001/T-12 保持停止。

## 任务清单

- [x] **T-01 规格与政策落盘**（AC-01～AC-08）：基于 SDD-002 编写 spec、plan、tasks、validation，明确时钟、失效和证据边界。 当前：完成。
- [x] **T-02 观察与补点策略测试**（AC-05、AC-06）：PostActionObserverTest、ActionRetryPolicyTest；稳定证据、支持事件类型、最近接受动作和各阈值。 当前：已实现并执行，当前全部通过；修复前失败记录保留于 validation.md。
- [x] **T-03 引擎会话与动作测试**（AC-01～AC-04）：可暂停 PageGateway、受控目录、存储、时钟；资格、查询、提交及目录撤销交错。 当前：已实现并执行，当前全部通过；修复前失败记录保留于 validation.md。
- [x] **T-04 引擎失效与记录测试**（AC-03、AC-06～AC-08）：禁用、暂停、版本、溢出、关闭、记录异常/取消、旧任务、补点、冷却和 ABANDONED 事实。 当前：已实现并执行，当前全部通过；修复前失败记录保留于 validation.md。
- [x] **T-05 平台离线测试**（AC-02、AC-04、AC-05）：Robolectric SDK 36；刷新、歧义、窗口、不可读、扫描预算、动作祖先、手势取消/迟到。 当前：已实现并执行，当前全部通过；修复前失败记录保留于 validation.md。
- [x] **T-06 真实目录与引擎集成**（AC-03、AC-07、AC-08）：真实 CompositeRuleCatalog 接真实 AutomationEngine，受控页面和时钟；降级停止动作并保留已接受事实。 当前：已实现并执行，当前全部通过；修复前失败记录保留于 validation.md。
- [x] **T-07 偏差分类与修复清单**（AC-03、AC-05、AC-06）：保存独立失败证据；不以忽略测试或放宽预期掩盖差异；按用户明确授权完成 AE-01、AE-02 最小修复，并新增 4 个挂起边界测试。 当前：已实现并执行，当前全部通过；修复前失败记录保留于 validation.md。
- [x] **T-08 验收收尾**（AC-01～AC-08）：逐 AC 证据和全部回归、构建及检查；离线和设备结论分别报告。 当前：本地离线验收通过；完整 201 例、构建、Lint、28 项检查器、规格及报告检查通过，修复后证据已归档。

## 验收追踪表

| 条件 | 任务 | 测试组织 | 关键观察 | 当前结果 |
| --- | --- | --- | --- | --- |
| AC-01 | T-03 | `AutomationEngineSessionTest` | 开关、版本、Activity、延迟、10 秒期限和不重武装 | 已列用例通过 |
| AC-02 | T-03、T-05 | `AutomationEngineActionTest、AccessibilityCandidateFinderTest` | 唯一/歧义、重试、最新身份、扫描超限 | 已列用例通过 |
| AC-03 | T-03、T-04、T-06、T-07 | `AutomationEngineCatalogTest、AutomationCatalogIntegrationTest` | 首次点击/手势/补点前目录变更、无关元数据和已接受记录 | 已列用例通过，生产偏差已闭环 |
| AC-04 | T-03、T-05 | `AutomationEngineActionTest、AccessibilityPageGatewayTest` | 不可点击无手势、提交复核和回调边界 | 已列用例通过 |
| AC-05 | T-02、T-05、T-07 | `PostActionObserverTest、AccessibilityActionEvidenceTest` | 可信消失、稳定性、事件时间及事件类型 | 已列用例通过，生产偏差已闭环 |
| AC-06 | T-02、T-04、T-07 | `ActionRetryPolicyTest、AutomationEngineObservationTest` | 唯一且稳定的原目标、次数上限、旧事件和绝对期限 | 已列用例通过，生产偏差已闭环 |
| AC-07 | T-04、T-06 | `AutomationEngineInvalidationTest、AutomationCatalogIntegrationTest` | 失效后的动作计数、旧任务、迟到结果和记录 | 已列用例通过，生产偏差已闭环 |
| AC-08 | T-04、T-06 | `AutomationEngineObservationTest、AutomationEngineInvalidationTest` | CLOSED/ABANDONED、写入异常、取消及冷却隔离 | 已列用例通过，生产偏差已闭环 |

全部列出的测试类已实现并执行；真实类/方法、失败场景和剩余限制见 [验证记录](validation.md)。任务勾选表示对应实现或记录工作已完成；修复前失败不计作通过。当前本地离线验收通过，T-08 已完成规格/报告检查及修复后证据归档；无设备或远端 CI 验收结论。
