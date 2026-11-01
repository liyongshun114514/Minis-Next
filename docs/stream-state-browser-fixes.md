# Streaming reasoning, activity and queued-message fixes (2026-11-01)

Baseline: `9068b21e1ecd12e087f0bb9976ca202015f14598`.

## Changes

1. `ThinkPrefixStreamParser` recognizes `<think>` and `<thinking>` at the response prefix, including fragmented opening/closing tags. The shared `splitThinkingPrefix()` adapter normalizes every provider stream before UI accumulation and persistence. Ordinary prose/code containing tags remains literal; ordinary Markdown whitespace is preserved by the shared adapter. Each collection has its own parser, including retries and long tool loops.
2. `SessionActivityTracker` serializes active/processing publication and records the registering stream job. A cancelled job unwinding after its replacement cannot deactivate that replacement, finish its overlay slot or send its completion event. All five stream entry/exit paths carry job ownership; explicit Stop retains its immediate session-wide semantics.
3. Queue enqueue/dequeue and targeted message updates use StateFlow CAS rather than whole-list snapshots. Bubble publication precedes queue visibility. Injected/drained user messages retain DB identities. Once an injected user row is durable and dequeued, switching to Main cannot cancel the user-bubble commit; a stopped job does not create another running placeholder, and a cleared chat is not repopulated. Stream finalization, flush and error updates preserve concurrent user appends.
4. Live browser snapshots use the existing bounded, serialized offscreen GPU render host, with software fallback, instead of only software drawing a detached WebView. Preview polling survives an individual capture failure. Cancellation propagates through both capture layers, whose existing finally blocks release the WebView/images. Visible browser-sheet WebViews are never stolen by the offscreen host.

## Local verification

- `python3 scripts/test-stream-state-regressions.py --work-dir <isolated-directory>`: **51 JUnit tests passed**. Production parser, adapter, tracker and atomic list-update helper are compiled directly. The runner substitutes inert Android/service/registry/resource/model dependencies; it is not a device or complete ViewModel integration test.
- `--baseline`: runs the pre-fix parser from the pinned commit through the adapter to demonstrate the thinking-tag failure (expected nonzero, not an application build).
- Full **828 production Kotlin sources**, plus Java source type dependencies, compiled with Kotlin 2.1.0 and Compose/serialization plugins: exit 0. Real cached libraries/Android SDK/Go binding API were used; R/BuildConfig were compile-only generated symbols. This is **not** Gradle/KSP/native/APK acceptance.
- Additional existing StopLateWrite, ScheduledActiveSession, OverlayMultiTaskIndicator and FgsStartAfterStop tests: **55/56 passed**. The sole failure is a pre-existing ScheduledActiveSessionTest source assertion at line 101, searching `manager.rescheduleNext(taskId)` while the baseline already uses `manager.rescheduleNext(taskId, slot)`. No production scheduled-alarm code was changed.
- Workflow resource tests: **5 passed**. Relevant new tests, StopLateWrite and ScheduledPrefillWiring are selected in CI.

## Still requires device/build verification

Full CI APK build, final signatures/package validation, installation, long-context live provider sessions, rapidly sending while tools run, concurrent stop/resume and real GPU/WebView thumbnail behavior. No claim that the intermittent device symptoms have been reproduced or eliminated on hardware. This change does not install an APK, publish a Release or modify credentials/settings.
