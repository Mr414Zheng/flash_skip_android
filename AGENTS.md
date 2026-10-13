# Repository Guidelines

## Project Structure & Module Organization

FlashSkip is a single-module Android application built with Kotlin and Jetpack Compose. Source lives in `app/src/main/kotlin/com/zhenghuaizhi/flashskip/`:

- `domain/`: models, rule validation, selector matching, automation policies, and port interfaces.
- `application/`: service status and rule-capture coordination.
- `data/preferences/`: DataStore persistence and codecs.
- `platform/`: Android accessibility, diagnostics, package lookup, and settings adapters.
- `ui/`: Compose screens, theme, and home ViewModel.

Wire dependencies in `AppContainer.kt`. Keep Android integration in `platform/` and business rules in `domain/`. Resources and icons live in `app/src/main/res/`; component declarations live in `AndroidManifest.xml`. Dependency versions are centralized in `gradle/libs.versions.toml`.

## Build, Test, and Development Commands

Use the checked-in Gradle wrapper from the repository root. The Gradle daemon requests JDK 21; Java source compatibility is 17. Configure the Android SDK through local `local.properties`; compilation targets SDK 36.1.

- `./gradlew :app:assembleDebug` — build the debug APK.
- `./gradlew :app:installDebug` — install on a connected device or emulator running Android 8.0/API 26 or later.
- `./gradlew :app:testDebugUnitTest` — run JVM unit tests.
- `./gradlew :app:lintDebug` — run Android Lint.

Launch FlashSkip from the device launcher and enable its accessibility service in Android settings when validating automation.

## Coding Style & Naming Conventions

Follow existing Kotlin style: four-space indentation, trailing commas in multiline argument lists, `PascalCase` types and composables, `camelCase` functions/properties, and `UPPER_SNAKE_CASE` constants. Match filenames to their primary class or responsibility. Preserve coroutine cancellation and inject clocks or platform ports where behavior needs deterministic testing. No dedicated formatter or Kotlin lint plugin is configured; use Android Studio formatting and Android Lint.

## Testing Guidelines

JUnit 4, Robolectric, and coroutine-test dependencies are configured. The tracked capture-save-retry suite contains five test classes and one shared fixture under `app/src/test/kotlin/`, covering coordinator contracts, cancellation and enable interleavings, HomeViewModel feedback, and real DataStore persistence. Its recorded run on 2026-10-13 passed 75 tests; this is scoped evidence, not whole-project or device coverage. No coverage threshold, tracked CI configuration, or `app/src/androidTest/` suite is currently configured.

Add tests under `app/src/test/kotlin/`, mirroring production packages. Cover observable behavior and relevant failure, cancellation, timing, and persistence boundaries. Reuse existing evidence only when the source and applicable code state are verified. Report JVM, Robolectric, UI component, and device evidence separately; a skipped or unavailable check is not a pass.

## Specification-Driven Development

Start with the [project specification index](specs/README.md), [capability inventory](specs/capabilities.md), [shared constraints](specs/constraints.md), and [phased roadmap](specs/roadmap.md). Keep approved contracts distinct from observed implementation behavior and proposed requirements. Link acceptance criteria to tasks, tests, results, and remaining limitations.

P0 documentation is in place; P1 and later phases are planned, not executed or authorized by the roadmap itself. Production code must remain unchanged within the current documentation scope. SDD-001/T-12 is incomplete and stopped at the user's request: do not resume JD splash-ad reproduction investigations or the remaining device validation. Preserve that decision and do not report it as passed.

## Commit & Pull Request Guidelines

Follow history's `<type>(scope):<中文subject>` convention, for example `fix(rule-capture):修正候选匹配逻辑`. Keep commits focused. PRs should explain the behavior change, link relevant issues, report validation commands/results, and include screenshots for UI changes plus device/API details for accessibility changes.

## Configuration & Diagnostics

Keep `local.properties`, signing keys, generated artifacts, and diagnostic logs out of Git. Capture journals are debug-only; review diagnostic evidence for sensitive screen content before sharing.
