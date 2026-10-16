# 实施计划：SDD-008

依据 [规格](spec.md)，任务见 [任务](tasks.md)，证据见 [验证](validation.md)。

1. 保留 main `0c9a53f` 全部 594 个测试身份与预期，建立 AC 与新增测试方法映射。
2. 使用虚拟时钟测试 ServiceStatusResolver、ServiceStatusCoordinator 的期限、连接与系统读取失败；用 Robolectric SDK 36 验证系统组件列表及 Intent／启动结果。
3. 通过 HomeViewModel 公开入口和真实 RuleCaptureCoordinator 驱动配置、离开、重建、导航失败、操作与反馈交错；存储替身仅控制提交屏障，不实现待测政策。
4. 在现有 FlashSkipAccessibilityServiceLifecycleTest 内追加测试，复用真实服务、AppContainer、实际 DataStore 的既有夹具；原 19 个测试体保持逐字相同。由现有 serviceConnections 连接真实 ServiceStatusCoordinator，系统设置读口以已启用输入控制；不声称 Android 系统投递证据。
5. 全量 Debug 单测、构建、Lint、检查器、规格及必需身份／产物门禁；红例仍登记必需，不修改生产。

## 接口与修复方向

本轮不新增生产接口。ServiceStatusCoordinator 负责状态组合与确认轮次；服务拥有连接、恢复与中断事实；HomeViewModel 拥有导航与反馈归属；系统适配器负责 Intent 和启用信息。

后续授权后可将服务连接信息扩展为内部可用性快照，保持默认装配；确认期限绑定轮次；导航保留可消费、可失效的会话请求并接入前台生命周期；操作反馈以请求身份判定归属。必须避免将“采集暂停自动执行”误判为服务故障。具体最小修复依实际失败清单确定，当前仅列方向。

Compose 交互测试需要新增依赖，不在本轮授权内；只读核对 HomeScreen／MainActivity 的消费调用，保留未验收状态。不得用 ViewModel 替身宣称真实控件通过。

## 2026-10-14 续授权修复实施（历史）

2026-10-14 用户授权修复全部 19 个失败及必要内部测试接缝，保留 701 个身份与预期，补回归并重跑完整本地验证。以上“不新增接口／不改生产”描述首轮阶段；本节为当前实施。

1. ServiceConnectionRegistry 增加内部连接周期与就绪快照，真实服务分阶段发布恢复、中断、存储可用性；旧连接回调不能改变新周期。
2. ServiceStatusCoordinator 固定每轮首次确认的 2 秒期限，隔离系统读取异常；HomeViewModel 开始入口读取最新可用性。
3. ViewModel 保留会话绑定的待消费导航，消费前验证前台、会话、实际离开和期限；HomeScreen 仅在 RESUMED 收集。
4. 反馈与重试使用操作归属，异步完成前重新核对；补配置变化、旧生命周期回调与新操作交错回归。
5. 新增 16 JVM 和 3 真实服务／实际 DataStore 回归；本包累计 67 例通过。Compose／设备限制不变。

## 2026-10-16 收尾实施

用户明确授权从 main `1e696980021d1ea9fabded34b294ed3648d17c3d` 的隔离工作区补齐 Compose、实际 Release、真实 JVM 进程、诊断故障矩阵和限定系统证据；允许必要内部接缝及测试／构建／CI 配置。原工作区诊断改动不纳入。采用现有 Compose BOM 的 ui-test-junit4／Debug test manifest，配置 Release 单测专用过滤与清单，保留全部原测试身份；只改变两个服务摘要提供者的内部可注入位置，默认表达式及业务装配不变。

按定向验证、完整双变体回归、设备取证、检查器正负例、证据归档顺序执行。结果与验收边界统一见[P5 本地收尾](../p5-closeout.md)；诊断逐出口对应见[故障矩阵](../diagnostics/fault-matrix.md)。本地等价检查完成，远端修改后的 CI 未执行；没有提交／推送／PR 或恢复京东／T-12。
