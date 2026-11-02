# Public build39 source integration and native-card removal

## Exact source, not the release-note label

- Requested release: <https://github.com/OpenMinis/OpenMinis/releases/tag/0-beta-build39>.
- Public tag resolves to `1a71df0f381cefde80bf912b723de6f9831bc177` (2026-10-26); public `main` resolved to the same commit when checked on 2026-11-02.
- User-provided `OpenMinis-0-beta-build39.zip`, SHA-256 `adb87f6756cc27029ac12369440eeb06588d01e423f9088563120db8f9a36c0f`, contains 3,995 files. Every file's Git blob hash matches that commit: no missing, extra or different files. GitHub source archives omit the `deps/ish` and `deps/proot` submodule contents.
- The ZIP's actual `src/android/app/build.gradle.kts` says `versionCode = 37`, `versionName = "1.15"`.
- The release API labels the release **Android 1.16-beta.1 (build 39)** and supplies `MinisApp-1.16-beta.1-build39-arm64-v8a.apk`. Its published time is 2026-11-01 16:05:46 UTC. Those release notes are newer than the tagged public source. A release asset is not evidence that the source archive contains its implementation.

### Content checks behind the mismatch finding

These checks read source content directly inside the uploaded ZIP, rather than infer functionality from filenames:

| Release-note example | What this source actually contains |
| --- | --- |
| Home Unread section with a 30-minute read-item retention | `SessionListScreen.kt` contains `SessionUnreadStore`-backed unread dots on rows and collapsed groups. The described new top-of-list partition is not implemented there. |
| Glass chat/home surfaces | `ChatScreen.kt` and `SessionListScreen.kt` retain their older surfaces; the described glass/backdrop composition is not present. |
| Storage: clear container temporary files | `StorageUsageViewModel.kt` has `clearLogsAndCaches()` and `clearSelectedFiles()`, not the described container `/tmp` cleanup action. |
| Environment editor password field with an eye button | Existing list rows have visibility icons. The editor's value uses an ordinary `DialogTextField` without password configuration; the new editor is not present. |
| Usage: start a new period / hide deleted providers | The supplied `UsageStatsScreen.kt` is the older snapshot-based screen, without those controls. |

This is not a claim that every line of the release notes is absent: some fixes or older forms already exist in the baseline. It is evidence that merging this public tag cannot truthfully deliver the entire 1.16 release-note feature set. No missing feature has been replaced by an unrelated design or claimed as merged.

## What was integrated

Original Next HEAD: `5c4246b`. Common upstream ancestor: `6eda5523a9c14fa8edc4fd9fb72e643fe47f23be` (upstream 1.15). The public tag adds three commits: `ee718c6`, `b518a7c`, and merge `1a71df0`.

All eight non-version files changed by upstream were brought across byte-for-byte, verified by comparing the final tree against the public tag:

- `deps/rclone-mobile/nethub/{dns.go,nethub.go,udp.go,dial_tailnet_test.go}`
- `deps/rclone-mobile/tsnet/{proxyview.go,tsnet.go}`
- `src/shared/network/{README.md,route_vectors.json}`

These contain the upstream Tailscale exit-node networking update: IPv4-first hostname connections with IPv6 fallback, loopback kept local, offline exit nodes refusing connections instead of silently using direct routing, and IPv4 DNS answers when the selected exit path cannot carry IPv6. Upstream test source is retained unchanged; it was not executed locally.

### Merge conflict and retained fork behavior

1. **Only textual merge conflict:** `src/android/app/build.gradle.kts`, upstream `1.15 / 37` versus Next `1.5 / 50`. Keep Next `1.5 / 50`, package `com.openminis.app.next`, its release repository and independent release signing. Do not downgrade or replace the installation identity. No version bump was requested.
2. **Existing semantic divergence, not a new merge conflict:** upstream preserves catalog models absent from a provider response for seven days, marking them unavailable. Next's deliberate live-catalog replacement (`ModelListReconcile` / `ProviderRepository.replaceEntries`) removes absent catalog entries immediately and prunes references. This task leaves that existing Next behavior unchanged and reports it rather than silently reverting prior customization. A separate decision is needed to restore upstream's seven-day policy.
3. Next's Ubuntu/CHROOT backend, permission gates, workspace instructions, provider customization, composer layout and previous streaming/state fixes are retained. The public upstream delta does not update those customized files. This does not prove behavioral parity with the newer release APK.

## Removed native interactive-card feature

Removed the optional feature's production package, dedicated tests, two localized resource files, old feature document and its exclusive attribution copy. Removed tool schema registration, executor dispatch, generated prompt bullet, chat renderer, follow-up send adapter/parameter, and special fold barrier. Updated CI filters and zero-skip report checks; added `test_removed_native_ui.py` to prevent dangling runtime references.

No conversation database or saved tool output is deleted or migrated. Existing historical blocks use the ordinary tool capsule/detail fallback; this is source-checked, not an installed-device migration test. `user_ask`, shell/file/browser/memory/sub-agent tools, web previews, composer geometry and their tests remain. Shared Compose testing dependencies remain because surviving UI tests still use them.

## Validation performed locally

- Uploaded archive: all 3,995 blobs match the public tag.
- Upstream non-version files: exact final-tree match, no unresolved merge entries, `git diff --check` clean.
- Production compilation: **809 Kotlin + 4 Java files**, Kotlin 2.1.0 with Compose and serialization plugins; both compiler exit codes 0. Uses cached real Android/AndroidX dependencies, compile-only `R`/`BuildConfig` symbols and a clearly isolated Go API signature stub. Kotlin bytecode optimization was disabled for this bounded-memory verification. This is **not an AGP/KSP/DEX/APK build or a Go runtime test**.
- Existing streaming/state regression: **51 passed**.
- Tool folding, composer geometry, tool switches: **25 passed**.
- Removal completeness, workflow resource contract, independent Next identity: **5 + 5 + 5 passed**.
- **529** resource XML files parse successfully.

Not verified: actual Go compilation/runtime, full Gradle/KSP build, final APK packaging/signing, installed-device history rendering, network behavior, visual/GPU acceptance, or the newer release-note functionality. No APK was produced, no install/Release/remote push was performed by this integration. Local evidence is saved in the task workspace's `next-build39/` directory.
