# Repository Guidelines

## Project Structure & Module Organization

FlashSkip is an offline Android accessibility app built with Kotlin and Jetpack Compose. The single `app` module keeps source under `app/src/main/kotlin/com/zhenghuaizhi/flashskip/`:

- `domain/`: automation engine, models, rules, and storage interfaces.
- `application/`: service status and rule-capture coordination.
- `data/`: Preferences DataStore implementations and codecs.
- `platform/`: Android accessibility, package, and settings adapters.
- `ui/`: Compose home screen, view model, and theme.

Keep Android integration in `platform/` and business rules in `domain/`. Android resources live in `app/src/main/res/`; documentation images live in `docs/assets/`. Dependency versions belong in `gradle/libs.versions.toml`.

## Build, Test, and Development Commands

Use Android Studio, Android SDK 36.1, and the checked-in Gradle Wrapper. Gradle daemon criteria specify JDK 21; Java source compatibility is 17. Devices require Android 8.0/API 26 or later.

- `./gradlew :app:assembleDebug`: build the debug APK.
- `adb install -r app/build/outputs/apk/debug/app-debug.apk`: install on a connected device; launch FlashSkip and enable its accessibility service manually.
- `./gradlew :app:lintDebug`: run Android Lint.
- `./gradlew :app:testDebugUnitTest`: run JUnit and Robolectric tests.
- `./gradlew :app:assembleRelease`: build with code and resource shrinking.

## Coding Style & Naming Conventions

Follow existing Kotlin style: four-space indentation, trailing commas in multiline lists, and explicit imports. Use `PascalCase` for classes and composables, `camelCase` for functions and properties, and lowercase package names. Name files after their principal type, such as `RuleCaptureCoordinator.kt`. Use Android Studio formatting; no standalone ktlint or Detekt configuration is present.

## Testing Guidelines

Tests under `app/src/test/` use JUnit 4, coroutine virtual time, and Robolectric 4.17 with API 26/36. Mirror production packages and use `*Test.kt` filenames. Give each fix a regression test; prioritize capture races, timeouts, automation safety, storage compatibility, and privacy. Isolate test storage and cancel test scopes. No coverage percentage or instrumentation runner is configured. Manually verify real accessibility events, explicit rule enablement, cold-start execution, and service recovery on a device.

## Commit & Pull Request Guidelines

Follow history's `<type>(scope):<中文subject>` convention, for example `fix(rule-capture):修复候选采集异常`. Keep commits focused. PRs should explain behavior changes, link relevant issues, and report validation commands and results. Include device/Android versions for accessibility changes and sanitized screenshots for UI changes.

## Privacy & Configuration

Preserve offline operation and explicit user enablement. Avoid storing screenshots, full node trees, fixed coordinates, or long-lived accessibility nodes. Never commit signing keys, `local.properties`, or personal diagnostic data.
