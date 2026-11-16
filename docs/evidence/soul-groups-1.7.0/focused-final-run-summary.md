# Focused offline JUnit - FAIL

- when: 20261116T204853  elapsed: 153.3s
- repo: `/var/minis/shared/Minis-Next`
- production classes: mode=full kotlinc rc=0 (820 Kotlin files compiled, 8216 classes)
- test compile rc=0; selected 27 test sources
- JUnit: 27 classes, 232 tests, 18 failures

- android unit-test runtime: mockable android.jar (AGP MockableJarGenerator, returnDefaultValues=true), 4750 classes, sha256[:12]=d9eb9da824d9
- declared test-only jars: 1 resolved offline, absent: org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0, com.squareup.okhttp3:mockwebserver:4.12.0, org.json:json:20231013, androidx.compose:compose-bom:2025.09.00, androidx.compose.ui:ui-test-junit4

| class | runtime | tests | failures | result | env-gap kind |
|---|---|---|---|---|---|
| `com.openminis.app.agent.ApplicationSystemPromptTest` | mockable-android.jar | 12 | 0 | PASS |  |
| `com.openminis.app.agent.KairosPromptModeTest` | mockable-android.jar | 6 | 0 | PASS |  |
| `com.openminis.app.agent.SoulBodyLimitPolicyTest` | mockable-android.jar | 6 | 0 | PASS |  |
| `com.openminis.app.agent.SoulIconAspectRatioTest` | mockable-android.jar | 10 | 0 | PASS |  |
| `com.openminis.app.agent.SoulIconConfigEdgeTest` | mockable-android.jar | 13 | 0 | PASS |  |
| `com.openminis.app.agent.SoulIconImportPlanTest` | mockable-android.jar | 14 | 0 | PASS |  |
| `com.openminis.app.agent.SoulIconImportRoboTest` | robolectric/android.jar | 13 | 13 | ENV-GAP | robolectric-native |
| `com.openminis.app.agent.SoulIconSidecarTest` | mockable-android.jar | 5 | 0 | PASS |  |
| `com.openminis.app.agent.SoulIconTest` | mockable-android.jar | 20 | 0 | PASS |  |
| `com.openminis.app.agent.SoulProfilesStorageTest` | mockable-android.jar | 13 | 0 | PASS |  |
| `com.openminis.app.agent.SoulPromptRulesTest` | mockable-android.jar | 6 | 0 | PASS |  |
| `com.openminis.app.agent.SystemPromptInjectionWiringTest` | mockable-android.jar | 9 | 0 | PASS |  |
| `com.openminis.app.agent.prompts.PromptCatalogScopeTest` | mockable-android.jar | 6 | 0 | PASS |  |
| `com.openminis.app.groups.ModelGroupAvatarCatalogTest` | mockable-android.jar | 4 | 0 | PASS |  |
| `com.openminis.app.groups.ModelGroupAvatarImageRoboTest` | robolectric/android.jar | 5 | 5 | ENV-GAP | robolectric-native |
| `com.openminis.app.groups.ModelGroupAvatarImageTest` | mockable-android.jar | 6 | 0 | PASS |  |
| `com.openminis.app.groups.ModelGroupAvatarPersistenceTest` | mockable-android.jar | 9 | 0 | PASS |  |
| `com.openminis.app.groups.ModelGroupAvatarResolverTest` | mockable-android.jar | 16 | 0 | PASS |  |
| `com.openminis.app.groups.ModelGroupAvatarValueTest` | mockable-android.jar | 7 | 0 | PASS |  |
| `com.openminis.app.ui.components.SoulAvatarContractTest` | mockable-android.jar | 5 | 0 | PASS |  |
| `com.openminis.app.ui.sessions.GroupMemberTileTest` | mockable-android.jar | 5 | 0 | PASS |  |
| `com.openminis.app.ui.sessions.GroupRunningRingTest` | mockable-android.jar | 10 | 0 | PASS |  |
| `com.openminis.app.ui.settings.SoulPromptModeUiContractTest` | mockable-android.jar | 4 | 0 | PASS |  |
| `com.openminis.app.ui.settings.SoulPromptRuleUiTest` | mockable-android.jar | 3 | 0 | PASS |  |
| `com.openminis.app.ui.settings.SoulPromptUiAccessibilityTest` | mockable-android.jar | 4 | 0 | PASS |  |
| `com.openminis.app.ui.settings.SoulSettingsPageContractTest` | mockable-android.jar | 10 | 0 | PASS |  |
| `com.openminis.app.ui.settings.UnifiedPromptSettingsTest` | mockable-android.jar | 11 | 0 | PASS |  |

ENV-GAP is not a pass. Causes on this host:
- `robolectric-native`: Robolectric NATIVE graphics runtime unavailable on Linux/aarch64


## Warnings

- rclone.aar / rclone-api.jar absent -> gomobile stub (compile-only)
- com.openminis.app.agent.SoulIconImportRoboTest: ENV-GAP (robolectric-native) Robolectric NATIVE graphics runtime unavailable on Linux/aarch64
- com.openminis.app.groups.ModelGroupAvatarImageRoboTest: ENV-GAP (robolectric-native) Robolectric NATIVE graphics runtime unavailable on Linux/aarch64
