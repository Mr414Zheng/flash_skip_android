# 采集诊断

Debug 构建同时输出 `FlashSkipCapture` Logcat 和应用私有本地日志。启动时有
`stage=capture_diagnostics_ready`，没有会话或无障碍服务尚未连接也会记录应用侧状态。
Release 构建关闭这些诊断，不创建日志目录。

本地目录：`no_backup/capture-diagnostics/`。目录不随保存、取消或删除应用规则而清除；
应用升级和进程重启继续保留，卸载或清除应用数据会删除日志。

- `session-<会话ID>.log`：最近 10 次采集，每次最多 1 MiB。普通过程记录使用约 3/4
  预算，剩余空间用于状态、失败诊断和终止摘要；达到上限会记录截断标记。
- `runtime.log`、`runtime-1.log`：启动、无会话服务事件、读取错误和队列掉记录计数，
  各最多 128 KiB。单条最多 4 KiB，队列最多 256 条，满时优先保留关键记录。
- 会话按明确开始的顺序保留；私有索引记录该顺序，系统时间回拨不会把新的采集当成旧日志。
- 每行含 `schema`、`epochMs`、会话 ID 和关键记录标记。`epochMs` 是 Unix 毫秒，
  可按北京时间定位操作；序号与相对时间属于当前进程。

广告不必立即复现。下次手动操作后即使过一段时间再取日志，该会话仍会保留，直到
超过保留的会话数。提取时应保存整个目录，以包含掉记录/写入故障信息：

```sh
adb exec-out run-as com.zhenghuaizhi.flashskip.debug tar -cf - no_backup/capture-diagnostics > capture-diagnostics.tar
```

也可以在 Android Studio Device Explorer 中打开同一私有目录，保存所有文件。
Logcat 查询使用 `tag:FlashSkipCapture`，级别包含 Debug。不要使用包名或 MainActivity
作为普通文本条件，这可能排除没有这些文字的采集记录。

日志只保存固定阶段、枚举、计数、耗时和窗口数字，不保存页面文本、包名、Activity、
资源 ID、坐标、节点树、异常消息或堆栈。文件写入在独立后台线程执行；磁盘故障不影响
采集业务。`journal_queue_dropped`、`journal_write_failures`、`journal_session_truncated`
表示证据有缺口。突然杀进程或断电仍可能丢失尚未出队的尾部，磁盘不可写时不能保证保存。

实际验收建议：安装本次 Debug APK，启动应用；进行一次普通添加/取消，确认本地有启动
和会话记录。京东广告下次出现时，执行添加、手动关闭广告、返回应用；保留红字结果，
随后提取整个日志目录。无需为了检查日志反复触发京东广告。
