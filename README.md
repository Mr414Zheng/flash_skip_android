# FlashSkip

FlashSkip 是一个完全离线的 Android 开屏广告关闭工具。它通过系统无障碍服务，在应用启动后的有限时间窗口内查找用户本机采集的关闭节点，并在匹配结果唯一且满足安全约束时自动执行点击。

项目目前面向开发者本人侧载使用，不提供远程规则、规则市场、截图识别或固定坐标点击。

## 功能

- 通用规则采集：无需预先登记应用，成功采集后按实际包名生成应用项。
- 本机规则：保存经验证的 Activity、限长节点语义和点击时序。
- 动态规则目录：不同关闭选择器可累积为多条规则，相同选择器合并证据。
- 独立应用开关：新生成的规则默认关闭，必须由用户明确启用。
- 安全执行：动作前复核包名、Activity、活动窗口和唯一节点匹配。
- 执行记录：最多保存并展示最近 20 条结果及阶段耗时。
- 隐私优先：无网络权限，不保存截图、坐标、完整节点树或 Android 节点对象。

## 使用流程

### 开启服务

1. 安装并打开 FlashSkip。
2. 点击“前往系统设置”。
3. 在无障碍设置中开启“FlashSkip 开屏关闭服务”。

### 采集规则

1. 在首页点击“采集规则”，FlashSkip 会暂停自动点击并返回 Home。
2. 手动启动需要适配的应用。
3. 开屏广告出现后，只点击一次关闭或跳过按钮。
4. 返回 FlashSkip，点击“采集完成”。
5. 规则达到 Ready 后，手动开启对应应用的自动关闭开关。

采集会绑定离开 Home 后首个验证成功的应用。采集期间不要先打开其他应用；无法获得可靠无障碍节点时，本次采集会安全失败。

## 工作原理

FlashSkip 使用单个 `AccessibilityService` 监听窗口变化。常态下只对已存在 Ready 规则的包进行处理；主动采集期间临时监听点击事件，并在识别目标应用后立即将会话收窄到该包。

自动执行会话限定在目标应用进入前台后的 10 秒内。引擎优先执行节点 `ACTION_CLICK`，仅在节点已唯一匹配但不支持直接点击时尝试节点中心手势。不确定、歧义、页面切换或遍历超限时不会盲目执行。

## 技术栈

- Kotlin 2.3
- Jetpack Compose / Material 3
- AndroidX DataStore
- Kotlin Coroutines
- Android Gradle Plugin 9.2
- Gradle 9.4
- Java/Kotlin 17
- `minSdk 26` / `targetSdk 36`

## 本地开发

使用 Android Studio 打开仓库，并确保 Android SDK 36.1 可用。项目已包含 Gradle Wrapper。

```bash
./gradlew :app:assembleDebug
```

生成的 Debug APK 位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

常用检查命令：

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:compileDebugAndroidTestKotlin
./gradlew :app:lintDebug
```

## 项目结构

```text
app/src/main/kotlin/com/zhenghuaizhi/flashskip/
├── application/   # 服务状态与规则采集协调
├── data/          # DataStore 和版本化本地数据
├── domain/        # 自动化引擎、领域模型和规则分析
├── platform/      # 无障碍、系统设置和包信息适配
└── ui/            # Compose 首页与主题
```

## 隐私与限制

- 所有规则和执行记录仅保存在应用私有存储中。
- 应用没有网络、外部存储、截图或 `QUERY_ALL_PACKAGES` 权限。
- 规则不绑定目标应用版本；目标应用升级后应重新验证规则是否仍然安全有效。
- WebView、自绘界面或不暴露可靠无障碍语义的广告可能无法适配。
- Android 厂商对后台和无障碍服务的管理策略不同，服务持续性需要在实际设备上验证。
