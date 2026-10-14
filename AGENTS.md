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
- `PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts/ci/tests -v` — validate CI checker positive and negative cases.
- `PYTHONDONTWRITEBYTECODE=1 python3 scripts/ci/check_specs.py` — check specification IDs, links, and references.
- `PYTHONDONTWRITEBYTECODE=1 python3 scripts/ci/check_test_results.py --require-artifacts` — check freshly generated JUnit/build/Lint evidence; run Gradle first as documented in the CI guide.

Launch FlashSkip from the device launcher and enable its accessibility service in Android settings when validating automation.

## Coding Style & Naming Conventions

Follow existing Kotlin style: four-space indentation, trailing commas in multiline argument lists, `PascalCase` types and composables, `camelCase` functions/properties, and `UPPER_SNAKE_CASE` constants. Match filenames to their primary class or responsibility. Preserve coroutine cancellation and inject clocks or platform ports where behavior needs deterministic testing. No dedicated formatter or Kotlin lint plugin is configured; use Android Studio formatting and Android Lint.

## Testing Guidelines

JUnit 4, Robolectric, and coroutine-test dependencies are configured. The tracked capture-save-retry suite contains five test classes and one shared fixture under `app/src/test/kotlin/`, covering coordinator contracts, cancellation and enable interleavings, HomeViewModel feedback, and real DataStore persistence. Local and remote Linux runs on 2026-10-13 passed all 75 tests without failures or skips; this is scoped evidence, not whole-project or device coverage. GitHub Actions also passed 28 CI checker tests, specification/PR checks, debug assembly, and Android Lint (0 errors, 15 remote warnings). A controlled invalid PR description failed Specs and retained a downloadable report; restoring the description returned the checks to success. Traceable runs and artifact details are in the [CI guide](specs/ci.md). P2 adds 16 test classes and two fixtures (126 cases), plus the three user-approved production policy fixes. The full local run at baseline `ad01dc7` plus these changes executed 201 cases: all passed, zero failures/errors/skips; all original 75 cases and all 36 new Robolectric platform cases passed. The earlier 197-case run with 22 failures is retained as pre-fix evidence. Debug assembly, 28 checker tests, specification and artifact checks passed; local Lint reported zero errors and 12 warnings. Independent static code review reported no findings. P2 local and remote CI acceptance passed. PR #3 was merged as `c2a286b` after rechecking its latest successful checks. Main CI run `38376388018` passed all 201 tests, 28 checker tests, debug assembly, and Lint (0 errors, 15 warnings); both artifacts were downloaded and verified. Remote run `38374303555` passed Specs and Android for head `3a4b360`, with 201 tests, 28 checker tests, debug assembly, and Lint (0 errors, 15 warnings); both artifacts were downloaded and verified. This is not independent QA or device evidence. See [rule readiness](specs/rule-readiness/validation.md) and [automation execution](specs/automation-execution/validation.md). No coverage threshold or `app/src/androidTest/` suite is configured.

Add tests under `app/src/test/kotlin/`, mirroring production packages. Cover observable behavior and relevant failure, cancellation, timing, and persistence boundaries. Reuse existing evidence only when the source and applicable code state are verified. Report JVM, Robolectric, UI component, and device evidence separately; a skipped or unavailable check is not a pass.

## Specification-Driven Development

Start with the [project specification index](specs/README.md), [capability inventory](specs/capabilities.md), [shared constraints](specs/constraints.md), and [phased roadmap](specs/roadmap.md). Keep approved contracts distinct from observed implementation behavior and proposed requirements. Link acceptance criteria to tasks, tests, results, and remaining limitations.

P0 documentation and P1 CI are on main: PR #1 merged as `2818232` on 2026-10-13, and main CI run `38362313424` passed Specs and Android with 28 checker tests, 75 JVM/Robolectric tests, debug assembly, and Lint (0 errors, 15 warnings). Required checks and branch protection have not been configured. P2 specification packages, offline tests, and the three user-approved policy fixes are now present; local, PR, and post-merge main CI acceptance passed; PR #3 is merged as `c2a286b`. P3 repairs and real-service integration passed local, PR and post-merge main CI; PR #4 was merged as `471ccd2`; P4 minimum repairs and local contract acceptance are complete; P5 onward remain pending. See the [CI guide](specs/ci.md) for traceable runs, required test inventory, report policy, and validation limits. The current P2 authorization includes specification documents, offline tests, evidence records, required-test inventory, and minimal production adaptations for stable selector structure, catalog invalidation, and events strictly after the most recently accepted action. These three policies are approved; other parameters retain their baseline status. Do not change remote settings, application dependencies/build configuration, or resume device work. The user subsequently authorized merging PR #3 after verifying its latest checks, waiting for main CI, and recording that evidence. The merge and main CI acceptance are complete; the evidence follow-up is documentation only. The roadmap itself does not authorize later phases. SDD-001/T-12 is incomplete and stopped at the user's request: do not resume JD splash-ad reproduction investigations or the remaining device validation. Preserve that decision and do not report it as passed.

## Commit & Pull Request Guidelines

Follow history's `<type>(scope):<中文subject>` convention, for example `fix(rule-capture):修正候选匹配逻辑`. Keep commits focused. PRs should explain the behavior change, link relevant issues, report validation commands/results, and include screenshots for UI changes plus device/API details for accessibility changes.

## Configuration & Diagnostics

Keep `local.properties`, signing keys, generated artifacts, and diagnostic logs out of Git. Capture journals are debug-only; review diagnostic evidence for sensitive screen content before sharing.

## P3 Pre-repair Scope and Evidence (Historical)

The user authorized SDD-004 and SDD-005 specification packages, offline tests, evidence, and required-test inventory. Five policies are approved: pending payload preservation on disconnect/recovery, lock-time monotonic deadlines for new evidence, explicit event semantics for missing sources, complete unique scan evidence, and complete cross-window metadata. Policy approval does not authorize production fixes. Preserve all original 201 test identities, report failures without ignoring or relaxing them, and separate JVM, Robolectric component, real service, and real storage evidence. Do not modify production code, public APIs, persistence formats, dependencies, build configuration, or remote settings. No commits, pushes, or PRs are included. T-12 remains incomplete and stopped. See [lifecycle](specs/capture-lifecycle/validation.md) and [evidence](specs/capture-evidence/validation.md) validation records for current results.

P3 local evidence: 19 new test classes and two fixtures add 252 cases. The complete run executed 453 cases: 411 passed, 42 failed, zero errors/skips; all original 201 identities passed. New evidence is 197 JVM cases (37 failures) and 55 Robolectric SDK 36 component cases (5 failures). Failures expose the five approved policy gaps; production fixes remain unauthorized. Debug assembly, 28 checker tests and specification checks passed; Lint reported zero errors and 12 warnings. Required-test/artifact validation correctly fails on the 42 red cases. Real service integration remains blocked by closed AppContainer/DataStore lifecycle seams; component success is not service or device evidence. No new real-storage tests were added; original persistence cases were rerun.

## P3 Production Repair Authorization

The user subsequently authorized the minimum production fixes for all five P3 policy gaps and the internal test seams needed for real service integration. Preserve all existing test expectations and identities; rerun the complete tests, debug build and Lint and update evidence. Internal construction seams may inject scopes, actual stores, clocks and dispatchers while preserving default production wiring. No commits, pushes, PRs, dependency/build/remote-setting changes or resumed T-12/device work. Earlier no-production-fix statements describe the pre-repair phase. Current local validation passed all 479 cases in 41 classes, preserving all 453 pre-repair identities including the original 201 and the 42 formerly failing cases. P3 now adds 278 cases: 207 JVM, 57 Robolectric SDK 36 component, and 14 Robolectric real-service/AppContainer/actual-DataStore integration cases. Debug assembly, 28 checker tests, specification and artifact checks passed; Lint reported zero errors and 12 warnings. Independent static re-review closed the restore-gate finding with no new findings; no independent QA, remote CI, device or Compose evidence is claimed. Evidence is in both P3 validation records; pre-repair failures remain archived.

## P3 PR Delivery Authorization

On 2026-10-14 the user authorized a new branch, commits, push, PR creation, remote CI completion, downloaded artifact verification and evidence updates. At that delivery stage the PR was kept unmerged; later merge authorization is recorded below. Earlier no-commit/no-push/no-PR statements record the local implementation phase. Do not change dependencies, build configuration, workflows or remote settings; T-12 remains incomplete and stopped.

At the pre-merge delivery stage, P3 PR #4 was OPEN on `codex/p3-capture-contracts`, implementation head `a5e7ddf`. Remote Linux run `38491677713` passed Specs and Android on 2026-10-14: all 479 cases, 28 checker tests, specification/PR checks, debug assembly and artifact checks; Lint had 0 errors and 15 warnings. Both report artifacts were downloaded and verified against test identities and checkout SHA. The workflow verifies a nonempty APK on the runner but does not upload its binary. See [P3 remote CI](specs/ci.md#p3-pr-与远端-linux-ci-验收). Evidence-document follow-up checks must be verified on their own head; no merge, device or independent QA claim.

## P3 Merge and Main CI Acceptance

On 2026-10-14 the user authorized merging PR #4 after verifying its latest checks, waiting for main CI, downloading and verifying reports, and recording evidence. PR #4 was merged as `471ccd23d064ed6babd9fcd4f52e3c3aa44852e7` after latest head `0e36333` passed CI `38492187179`. Main CI `38493360255` passed all 479 tests, 28 checker tests, specs, debug assembly and artifact checks; Lint reported 0 errors and 15 warnings. Both report artifacts were downloaded, checked by full test identity and matched to the merge SHA. The user chose a separate documentation PR for the evidence follow-up; verify that PR’s CI and leave it unmerged for user review. No dependency, build, workflow or remote-setting changes; T-12 remains incomplete and stopped. See [P3 main CI](specs/ci.md#p3-合并与主干-ci-验收).

## P4 Pre-repair Scope and Local Evidence (Historical)

The user authorized SDD-006 and SDD-007 specifications, offline contract tests, required-test inventory and evidence on main `40e6080`. Approved policies preserve unknown-version raw data and block execution/overwrite, isolate damaged entries, distinguish unavailable storage from valid empty state, retain historical v2 defaults while abandoning v1, retain partial disables on bulk-delete failure, invalidate stale delete retries by affected data scope, and keep 20 records in write order without deleting history with rules. Policy approval does not authorize production fixes. Do not change production code, public APIs, persistence formats, dependencies, build/workflow configuration or remote settings. No commits, pushes or PRs. T-12 remains incomplete and stopped; P5 has not started.

P4 adds six test classes and one shared real-DataStore fixture, 95 cases: 33 JVM, 23 Robolectric SDK 36 Codec components, and 39 Robolectric SDK 36 actual-DataStore cases. Full local execution: 574 cases in 47 classes, 546 passed, 28 failed, zero errors/skips; all original 479 identities passed unchanged. Debug assembly passed; Lint has zero errors and 12 warnings. All 28 CI checker tests and specification checks passed. Required-test/artifact validation correctly fails on the 28 red cases with no missing identities or artifact errors. Contract acceptance is not passed; preserve the red cases. The existing 14 real-service integration cases reran as baseline regression, not as new P4 service evidence. See [persistence](specs/persistence-compatibility/validation.md) and [management](specs/rule-management/validation.md) for methods, evidence and remaining limits.

## P4 Production Repair Authorization and Local Acceptance

The user subsequently authorized the minimum production fixes for all 28 P4 failures, preservation of all 574 test identities and expectations, necessary regression tests and complete local verification. Persistence format, dependencies, build/workflow configuration and remote settings must remain unchanged. No commits, pushes or PRs; T-12 remains incomplete and stopped. Earlier no-production-fix and red-case statements describe the pre-repair phase.

Local acceptance passed: 594 cases in 49 classes, zero failures/errors/skips. All 574 pre-repair identities, including the 28 red cases, passed unchanged. Added 20 regressions: 5 JVM, 1 Robolectric Codec, 9 Robolectric actual-DataStore and 5 Robolectric real-service/AppContainer/DataStore integration cases. P4 now adds 115 cases overall: 38 JVM, 24 Codec, 48 actual-DataStore, 5 real-service integration. The existing service test class gained five cases and fixture fault controls; its original 14 test bodies were preserved verbatim. Debug assembly, all 28 CI checker tests, specifications and required-test/artifact gates passed; Lint has zero errors and 12 existing warnings.

The fix adds in-memory StorageRead availability observations with compatible defaults on both store ports, transactional write guards, per-entry/diagnostic decoding, availability consumers in catalog/home/service, and internal deletion request scope checks under the coordinator lock. Existing command signatures and v2 encoders/keys are retained; there is no migration or recovery UI. Raw pre-repair evidence remains under the ignored P4 full report directory; repaired-final contains current evidence. No remote CI, device, Compose controls, independent review or independent QA is claimed. See the two P4 validation records.

## P4 PR Delivery Authorization

On 2026-10-14 the user authorized a new P4 branch, commits, push, PR creation, remote CI completion, downloaded report verification, and evidence updates. Keep the PR unmerged for user review. Earlier no-commit/no-push/no-PR statements describe the local repair phase. Do not change persistence formats, dependencies, build/workflow configuration or remote settings; T-12 remains incomplete and stopped, and P5 has not started.
