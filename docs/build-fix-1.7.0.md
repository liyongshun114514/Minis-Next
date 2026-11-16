# 1.7.0 CI test-contract repair

Actions run [43871170157](https://github.com/liyongshun114514/Minis-Next/actions/runs/43871170157) failed at `:app:testGithubDebugUnitTest`: 1152 tests ran and one assertion failed in `SystemChannelPlacementTest.kt:152`. Production Kotlin compilation completed; Build Tools was not the failure.

The test still required the retired Chinese preamble sentence, while `ApplicationSystemPromptTest` already described the current SOUL preamble. Update the source contract to require truthful reporting of refused/failed actions, preserving provider channel ordering, catalog wiring and non-logging assertions. No production prompt text changes are part of this repair.

Add `SystemChannelPlacementTest` to the offline SOUL/group suite's pinned classes so it is no longer omitted locally. Also include the previously prepared Build Tools 35.0.0 module pin and project guidance, matching the existing Actions SDK installation.

Validation on the Ubuntu ARM64 host:

- Direct Kotlin/JUnit compilation of real `ProductionSources.kt` and `SystemChannelPlacementTest.kt`, using cached Kotlin 2.1.0, JUnit 4.13.2 and Hamcrest 1.3: before the repair, 11 tests / one failure at the same line as Actions; after the repair, 11 / 11 passed.
- `check_soul_groups_tests.py --list`: the repaired test is present in the default selection (28 classes).
- Python workflow resource tests: 6 passed; identity tests: 5 passed.
- `git diff --check`: passed.

The direct JUnit run does not build an APK. The pushed revision triggers the normal Actions workflow for full Android compilation, regression tests and APK packaging. Device installation/runtime behavior remains unverified.
