# FlashSkip Design System

> 版本：1.0  
> 状态：设计评审稿，不包含实现代码  
> 更新日期：2026-08-10  
> 目标平台：Android，Jetpack Compose + Material 3  
> 视觉基准：[视觉方向.png](../../视觉方向.png)  
> 产品依据：[设计简报](../../极简开屏广告关闭器：设计简报.md)

## 使用规则

本文件是 FlashSkip 的全局设计源。实现具体页面前，先检查 `pages/<page-name>.md`；页面文件只覆盖明确声明的规则，其余均继承本文件。

当前只有一个应用页面，其专用规范见 [pages/home.md](pages/home.md)。

## 1. 设计立场

### 1.1 视觉定义

FlashSkip 采用 **Flat Native Utility**：接近 Android 系统设置的扁平单列工具界面，以排版、间距、分隔线和少量语义色建立层级。

它不是仪表盘、广告拦截品牌页或监控大屏。界面只需要让用户快速确认：服务是否可用、哪些应用已启用、最近发生了什么。

### 1.2 设计调节值

| 维度 | 值 | 含义 |
| --- | ---: | --- |
| 视觉变化度 | 2/10 | 克制、居中、避免装饰性构图 |
| 动效强度 | 2/10 | 只反馈操作与状态变化 |
| 信息密度 | 7/10 | 紧凑但保持 Android 48 dp 触控基线 |

### 1.3 核心原则

1. **状态可读，不靠猜测**：颜色必须同时配合标题、图形或结果文案。
2. **结构由留白与对齐完成**：优先使用间距、字体和轻分隔线，不用卡片堆叠。
3. **健康状态保持平静**：绿色只表达服务健康，不把整页染成“持续监控中”。
4. **谨慎是产品能力**：`已放弃` 使用中性语义，避免被误读为严重错误。
5. **尊重系统习惯**：使用 Material 3 原生开关、状态层、字体缩放、深浅主题和系统安全区。

## 2. 品牌与视觉语汇

### 2.1 产品名

- 正式写法：`FlashSkip`
- 不写作：`Flash Skip`、`FLASHSKIP`、`flashskip`
- 页面标题只出现一次，不附加口号。

### 2.2 产品图标方向

- 气质：精确、快速、工具向。
- 可以表达“跳过明确目标”或“短时间窗口内完成动作”。
- 不使用盾牌、扫帚、大面积禁止符号或目标应用品牌拼贴。
- 适配 Android Adaptive Icon，并提供 Android 13+ 主题图标单色层。
- 当前设计系统只约束方向，不锁定最终图形。

## 3. 颜色系统

### 3.1 使用原则

- 所有组件只引用语义 Token，不直接使用原始 Hex。
- 主色蓝负责结构标记、启用开关、焦点和主要操作。
- 服务健康使用绿色；未开启使用琥珀色；异常和执行失败使用红色。
- `已放弃` 是防误触机制的正常结果，默认使用中性色，不使用错误红。
- 日志中的成功动作继续使用主色蓝，与服务健康绿区分。

### 3.2 Light Theme

| Token | Hex | 用途 |
| --- | --- | --- |
| `color.background` | `#FFFFFF` | 页面底色 |
| `color.surface` | `#FFFFFF` | 列表与普通内容表面 |
| `color.surfaceSubtle` | `#F7F8FA` | 轻提示、禁用背景、必要的分组底色 |
| `color.onSurface` | `#101828` | 主标题、应用名、主要结果 |
| `color.onSurfaceVariant` | `#475467` | 辅助说明、时间、规则 ID |
| `color.primary` | `#155EEF` | 分节标记、启用开关、成功日志节点、焦点 |
| `color.onPrimary` | `#FFFFFF` | 主色实心控件内容 |
| `color.primaryContainer` | `#EAF0FF` | 主要操作的轻量容器 |
| `color.onPrimaryContainer` | `#0F3D99` | 主色容器文字 |
| `color.success` | `#027A48` | 服务运行正常文字或图标 |
| `color.successIndicator` | `#12B76A` | 服务正常圆点，仅作辅助 |
| `color.warning` | `#B54708` | 服务未开启、等待用户处理 |
| `color.warningContainer` | `#FFFAEB` | 未开启状态的轻量提示背景 |
| `color.error` | `#B42318` | 服务异常、执行失败 |
| `color.errorContainer` | `#FEF3F2` | 异常状态轻量提示背景 |
| `color.outline` | `#98A2B3` | 次要描边、未激活时间线节点 |
| `color.divider` | `#EAECF0` | 1 dp 列表与底部分隔线 |
| `color.disabledContent` | `#98A2B3` | 禁用文字和图标 |
| `color.disabledContainer` | `#F2F4F7` | 禁用控件轨道或背景 |
| `color.focusRing` | `#155EEF` | 键盘与辅助输入焦点 |

### 3.3 Dark Theme

| Token | Hex | 用途 |
| --- | --- | --- |
| `color.background` | `#0C111D` | 页面底色 |
| `color.surface` | `#161B26` | 普通表面 |
| `color.surfaceSubtle` | `#1F242F` | 轻提示和禁用背景 |
| `color.onSurface` | `#F2F4F7` | 主标题与主要结果 |
| `color.onSurfaceVariant` | `#D0D5DD` | 辅助说明、时间、规则 ID |
| `color.primary` | `#84ADFF` | 分节标记、启用开关、焦点 |
| `color.onPrimary` | `#002E69` | 主色实心控件内容 |
| `color.primaryContainer` | `#102A56` | 主色轻量容器 |
| `color.onPrimaryContainer` | `#D1E0FF` | 主色容器文字 |
| `color.success` | `#6CE9A6` | 服务正常文字或图标 |
| `color.successIndicator` | `#32D583` | 服务正常圆点 |
| `color.warning` | `#FEC84B` | 服务未开启 |
| `color.warningContainer` | `#3A2B0B` | 未开启提示背景 |
| `color.error` | `#FDA29B` | 服务异常、执行失败 |
| `color.errorContainer` | `#3B1513` | 异常提示背景 |
| `color.outline` | `#667085` | 次要描边和节点 |
| `color.divider` | `#333741` | 分隔线 |
| `color.disabledContent` | `#667085` | 禁用文字和图标 |
| `color.disabledContainer` | `#1F242F` | 禁用轨道或背景 |
| `color.focusRing` | `#84ADFF` | 焦点 |

### 3.4 关键对比度

| 前景 / 背景 | Light | Dark | 要求 |
| --- | ---: | ---: | --- |
| `primary` / `background` | 5.41:1 | 8.44:1 | 通过 AA 正文 |
| `onSurface` / `background` | 17.75:1 | 17.12:1 | 通过 AAA |
| `onSurfaceVariant` / `background` | 7.69:1 | 12.79:1 | 通过 AAA |
| `success` / `background` | 5.41:1 | 12.43:1 | 通过 AA 正文 |
| `warning` / `background` | 5.43:1 | 12.19:1 | 通过 AA 正文 |
| `error` / `background` | 6.57:1 | 9.71:1 | 通过 AA 正文 |

`successIndicator` 只作为圆点辅助，不承担独立文本信息。

## 4. 字体系统

### 4.1 字体家族

- 默认：Android 系统字体，即 Latin 使用 Roboto、简体中文使用系统 Noto Sans CJK 回退。
- 不随 APK 打包 Web 字体；避免增大体积并保持系统一致性。
- 页面 UI 不使用衬线字体。
- 时间和规则 ID 使用系统 Sans 的等宽数字特性；除非实际对齐不足，不引入第二套 Monospace 字体。

### 4.2 Type Scale

| Token | 字号 / 行高 | 字重 | 用途 |
| --- | --- | --- | --- |
| `type.appTitle` | 32 sp / 40 sp | 700 | `FlashSkip` 页面标题 |
| `type.sectionTitle` | 20 sp / 28 sp | 600 | `目标应用`、`最近执行` |
| `type.statusTitle` | 18 sp / 26 sp | 500 | `服务运行正常` 等状态标题 |
| `type.rowTitle` | 18 sp / 26 sp | 500 | 应用名、执行结果 |
| `type.body` | 16 sp / 24 sp | 400 | 状态说明、主要正文 |
| `type.metadata` | 14 sp / 20 sp | 400 | 时间、规则 ID、应用行说明 |
| `type.label` | 14 sp / 20 sp | 500 | 按钮和紧凑标签 |
| `type.caption` | 12 sp / 16 sp | 400 | 仅限必要的辅助信息，不作为主要正文 |

### 4.3 排版规则

- 中文正文保持系统默认字距，不使用负字距。
- `FlashSkip` 可使用 `-0.2 sp` 的轻微字距调整，其余标题不压缩。
- 时间与规则 ID 启用 tabular figures，避免数值更新引发水平跳动。
- 正文优先换行，不用省略号隐藏状态或恢复路径。
- 支持系统字体缩放至 200%；行高随字号增长，组件高度不得写死。

## 5. 间距、尺寸与栅格

### 5.1 Spacing Scale

| Token | 值 | 用途 |
| --- | ---: | --- |
| `space.1` | 4 dp | 极小间距、标记内部 |
| `space.2` | 8 dp | 图文微间距、触控目标之间的最小净距 |
| `space.3` | 12 dp | 标题与说明、行内元素 |
| `space.4` | 16 dp | 组件标准内边距 |
| `space.5` | 20 dp | 紧凑分节间距 |
| `space.6` | 24 dp | 手机页面水平边距 |
| `space.8` | 32 dp | 一级区块间距 |
| `space.10` | 40 dp | 状态区与首个分节 |
| `space.12` | 48 dp | 最小触控尺寸、主要空白 |
| `space.16` | 64 dp | 大区块间距，仅在内容较少时使用 |

### 5.2 尺寸 Token

| Token | 值 | 用途 |
| --- | ---: | --- |
| `size.touchMin` | 48 dp | Android 最小触控区域 |
| `size.iconSm` | 16 dp | 装饰状态点附近小图形 |
| `size.iconMd` | 24 dp | 隐私、异常、操作图标 |
| `size.appIconCompact` | 48 dp | 小屏或大字体模式 |
| `size.appIcon` | 56 dp | 默认应用图标 |
| `size.switchVisual` | 52 × 32 dp | Material 3 Switch 视觉尺寸 |
| `size.sectionMarker` | 4 × 20 dp | 蓝色分节标记 |
| `size.timelineNode` | 12 dp | 默认时间线节点 |
| `size.timelineRail` | 2 dp | 时间线连接线 |

### 5.3 页面栅格

- `< 360 dp`：水平边距 16 dp，应用图标 48 dp。
- `360–599 dp`：水平边距 24 dp，应用图标 56 dp。
- `≥ 600 dp`：内容最大宽度 560 dp，水平居中；保持单列，不改为双栏。
- 横屏仍使用单一纵向滚动容器，并限制内容宽度，避免文字横向拉散。
- 所有固定内容尊重状态栏、显示刘海和手势导航 Insets。

## 6. 形状、边框与层级

| Token | 值 | 用途 |
| --- | ---: | --- |
| `shape.none` | 0 dp | 页面与列表底面 |
| `shape.small` | 4 dp | 分节标记、极小标签 |
| `shape.medium` | 8 dp | 按钮、提示容器 |
| `shape.appIcon` | 12 dp | 仅在源图标需要统一裁切时使用 |
| `shape.full` | 999 dp | 状态圆点、开关轨道 |

- 默认内容层级 `elevation = 0 dp`。
- 普通列表不使用阴影，不给每行加卡片边框。
- 分隔线固定 1 dp；只出现在同组行之间或页尾隐私区上方。
- 对话框、底部表单等未来组件可使用 Material 3 系统层级，但不提前加入 MVP 首页。

## 7. 图标与品牌素材

### 7.1 系统图标

- 首选 Material Symbols Rounded / Compose Material Icons Rounded。
- 同一层级保持 Rounded 风格和一致视觉重量。
- 常规图标 24 dp；装饰图标不单独获得 TalkBack 焦点。
- 状态点、文字和系统图标组合表达状态，禁止只依赖颜色。

建议映射：

| 语义 | 图标/形式 |
| --- | --- |
| 服务正常 | 12 dp 绿色圆点 + 状态文字 |
| 服务未开启 | `WarningAmber` + 状态文字 |
| 服务异常 | `ErrorOutline` + 状态文字 |
| 正在确认 | `Sync` 或小型 `CircularProgressIndicator` |
| 隐私说明 | `ShieldOutlined` |
| 日志成功 | 蓝色实心时间线节点 + `已关闭` |
| 日志放弃 | 灰色实心时间线节点 + `已放弃` |
| 日志失败 | `ErrorOutline` 或红色节点 + `执行失败` |

### 7.2 目标应用图标

- 动态应用行使用应用名称首字符的中性图标，不维护固定品牌素材。
- 默认显示 56 dp、12 dp 圆角，使用 `primaryContainer` 与 `onPrimaryContainer`。
- 图标仅辅助定位；应用名称和包名才是规则身份依据。

## 8. 组件体系

### 8.1 组件清单

| 组件 | 职责 | 核心约束 |
| --- | --- | --- |
| `FlashSkipScreen` | 安全区与单列滚动容器 | 不含底部导航，不使用嵌套滚动 |
| `ServiceStatusBlock` | 服务状态、启用数量、最近成功、条件式 CTA | 不放主服务开关 |
| `SectionHeader` | 分节标记与标题 | 4 × 20 dp 蓝色标记 |
| `TargetAppRow` | 图标、名称、独立开关、采集控制、内联规则卡 | 最小 96 dp，高度可随字体增长 |
| `ExecutionTimeline` | 最近 20 条动作记录 | 时间倒序，单一纵向时间线 |
| `ExecutionItem` | 结果、方式、时间、规则 ID | 结果不用颜色单独表达 |
| `PrivacyFooter` | 离线与数据最小化声明、条件式全部清除 | 页面底部 |
| `InlineAction` | 前往系统设置 | 只在未开启或异常时出现 |
| `EmptyExecutionState` | 无记录说明 | 无插画，无庆祝动画 |

组件的详细布局、状态和文案见 [pages/home.md](pages/home.md)。

## 9. 交互与反馈

- 主页面仅支持纵向滚动；不启用下拉刷新、自定义横滑或长按菜单。
- 应用行不整体切换；自动关闭 Switch 与采集按钮分别获得明确焦点，防止采集动作和启用动作混淆。
- 开关变更立即持久化，无需确认对话框；这是可逆、低风险操作。
- 触控后 100 ms 内出现 Material ripple/state layer。
- 禁用控件必须同时降低强调度、移除点击行为并暴露 disabled 语义。
- 打开系统设置返回后自动刷新；开始采集配置成功后返回 Home，由用户手动启动任意待适配应用。
- 自动关闭广告时，本应用不弹 Toast、不发通知、不跨应用显示反馈。

## 10. 动效系统

| Token | 时长 | 用途 |
| --- | ---: | --- |
| `motion.instant` | 80 ms | 按压状态层出现 |
| `motion.fast` | 120 ms | 状态点和小图标颜色变化 |
| `motion.standard` | 180 ms | 开关、状态文字 Crossfade |
| `motion.slow` | 240 ms | 条件式 CTA 或空状态进入 |

- 使用 Material 3 原生 easing 或 Compose spring；不使用 linear。
- 离场时长约为进场的 60–70%。
- 不做首屏列表 stagger、滚动视差、缩放卡片或循环装饰动画。
- `减少动态效果` 开启时：取消位移动画，仅保留不超过 100 ms 的必要颜色/透明度切换。
- 动效不能阻塞点击或改变周围组件边界。

## 11. 无障碍规范

- 正文颜色对比度至少 4.5:1；大图形与非文本控件至少 3:1。
- 所有可点击区域至少 48 × 48 dp，相邻触控目标净距至少 8 dp。
- 视觉顺序即 TalkBack 顺序：标题 → 服务状态 → 目标应用 → 最近执行 → 隐私说明。
- 每个 Switch 暴露动态应用名与状态，例如：`应用名称自动关闭开关，已开启`；通用采集按钮独立朗读。
- Switch 提供 `stateDescription`，不能只朗读“开/关”。
- 应用图标在所属行已朗读应用名时标记为装饰，避免重复。
- 时间线装饰线、节点和分节蓝条不获得焦点。
- 运行、未开启、异常、放弃、失败均由文本表达，不靠绿色/红色区分。
- 字体放大到 200% 时允许行高和列表项增长；状态说明、应用名称和恢复操作不能截断。
- 键盘、Switch Access 和 TalkBack 的焦点样式使用 `focusRing`，宽度 2 dp。

## 12. Material 3 / Compose 映射

| Design Token | Compose 角色 |
| --- | --- |
| `color.primary` | `MaterialTheme.colorScheme.primary` |
| `color.onPrimary` | `onPrimary` |
| `color.primaryContainer` | `primaryContainer` |
| `color.background` | `background` |
| `color.surface` | `surface` |
| `color.surfaceSubtle` | `surfaceContainerLow` |
| `color.onSurface` | `onSurface` |
| `color.onSurfaceVariant` | `onSurfaceVariant` |
| `color.divider` | `outlineVariant` |
| `color.outline` | `outline` |
| `color.error` | `error` |
| Type Scale | 自定义 `Typography`，沿用 Material 角色语义 |
| Spacing/Size | 项目级不可变 Token，不在组件中散落常量 |

主题使用 `colorScheme` 切换深浅色；禁止在页面组件中判断主题后直接写 Hex。

## 13. 禁止模式

- 卡片套卡片，或将每个应用/日志项单独做成浮起卡片。
- 渐变、玻璃、霓虹、拟物、双阴影和装饰性 3D。
- 底部导航、悬浮按钮、统计图、成功率大数字或“今日拦截”营销指标。
- 用绿色表示所有成功操作，导致服务健康与动作结果混淆。
- 用红色表现 `已放弃`，把安全策略误导成错误。
- 为动态目标应用伪造或近似绘制品牌图标。
- 小于 12 sp 的可读文本、小于 48 dp 的触控目标、只靠颜色表达状态。
- 页面内主服务开关；无障碍服务只能通过明确的系统设置入口管理。
- 为装饰而使用的加载动画、首屏飞入、列表逐项弹跳。

## 14. 设计系统治理

- 修改全局颜色、字体、间距、形状或动效时，更新本文件版本。
- 页面级特殊规则写入 `pages/`，并说明覆盖原因；不得复制整套 Master。
- 新增组件前先确认现有组件无法组合完成需求。
- 所有 UI 实现最终必须通过：浅色、深色、字体 200%、TalkBack、360 dp 小屏、600 dp 平板与横屏检查。
- 视觉验收以选定截图为方向基准，以本设计系统的可访问性与真实文案规则为最终准则。

## 15. 数据库取舍记录

UI/UX Pro Max 的有效命中包括：

- `Flat Design Mobile`：零阴影、48 dp 触控、4/8 dp 间距与单一强调色。
- `Status Page / Incident Management`：状态优先、时间线、严重程度区分。
- `Material You MD3 (Roboto System)`：Android 系统字体和 Material 类型角色。
- `Professional blue + service green`：蓝色承担交互，绿色承担服务健康。
- Jetpack Compose：通过 `colorScheme`、语义 API 和不可变状态实现主题与无障碍。

自动生成的 `Exaggerated Minimalism / Landing Page` 建议因与 Android 个人工具及选定截图不符而被明确排除。
