# Focused JUnit 1.7.0 — the 50 failures, classified one by one

Supersedes the failure analysis in `README.md` §2 of the pre-mockable run
(`focused-final-run-summary.pre-mockable.json`). Nothing in this document is a
pass for something that did not run: the 18 Robolectric NATIVE tests are still
**not verified** on this host.

- run: `final-verified`, `20261116T2047` (labels below are the runner's own)
- repo: `/var/minis/shared/Minis-Next` (dirty worktree, `src/android` 1.7.0 / 58)
- host: Ubuntu 24.04 aarch64 chroot, JDK 21, offline, no Gradle, no device
- artifact: `focused-final-run-summary.json` (machine), `focused-final-run-summary.md`
  (runner report), `source-snapshot.sha256.txt`, trimmed per-class logs `log-*.txt`

## 1. Verdict

| | classes | tests |
|---|---|---|
| selected | 27 | 232 |
| PASS | 25 | 214 |
| FAIL (real source/contract) | **0** | **0** |
| ENV-GAP (not a pass, not a failure) | 2 | 18 |

The 50 failures of the previous run are all accounted for: 29 were the harness
(classpath, not source), 18 were and remain the Robolectric native runtime gap,
and 3 were wrong expectations in the tests themselves (fixed by the test author,
not by this runner and not by production code).

## 2. The 29 harness failures — fixed in the runner

| class | old failures | real cause | now |
|---|---|---|---|
| `agent.SoulProfilesStorageTest` | 26 (13 tests × setUp+tearDown) | the test builds a `ContextWrapper` subclass; the runner put the **real stub** `android.jar` on the test runtime classpath, so `ContextWrapper.<init>` threw `java.lang.RuntimeException: Stub!`. Gradle's unit test task does not do that — with `testOptions.unitTests.returnDefaultValues = true` it runs against the **mockable** `android.jar`. | 13/13 PASS |
| `agent.KairosPromptModeTest` | 1 | `AppLogger.log` → `android.util.Log.w` → `Stub!` | 6/6 PASS |
| `agent.SoulPromptRulesTest` | 1 | same `Stub!` from the warning path of `SoulPromptRuleStore.readDocument` | 6/6 PASS |
| `groups.ModelGroupAvatarPersistenceTest` | 1 | `java.sql.SQLException: No suitable driver found for jdbc:sqlite::memory:`. The old note claimed `org.xerial:sqlite-jdbc:3.41.2.2` was not cached; it **is** — `/var/cache/minis/gradle/caches/modules-2/files-2.1/org.xerial/sqlite-jdbc/3.41.2.2/ddeb8d3a3004f412ed19b4c98b3aec11d9452ab5/sqlite-jdbc-3.41.2.2.jar` — but the runner never put it on the test classpath although the app declares it as `testImplementation`. | 9/9 PASS |

### 2a. How the mockable android.jar is produced (and why it is faithful)

`check_soul_groups_tests.py` now runs **AGP's own** mockable-jar generator —
`com.android.builder.testing.MockableJarGenerator` from
`com.android.tools.build:builder` in the Gradle module cache — through a
generated single-file launcher:

```
java -cp builder.jar:guava.jar:asm.jar:asm-tree.jar MockableJarMain.java true \
     /opt/android-sdk/platforms/android-36/android.jar <cache>/mockable-android-<sha12>.jar
```

This is the same class Gradle 8.7.x/8.9.x executes for
`unitTests.returnDefaultValues = true`; no framework class is hand-written and
no app class is touched. Accounting of the generated jar (verified, not assumed):

| item | count |
|---|---|
| classes in SDK `android.jar` (android-36) | 6 227 |
| left out by AGP (`java.`, `javax.`, `org.xml.`, `org.w3c.`, `junit.`, `org.apache.commons.logging`) | 1 477 |
| written to the mockable jar (`android/` 4 709 + `org/` 23 + `dalvik/` 16 + `androidx/` 2) | 4 750 |
| unexplained missing / extra | 0 / 0 |

Body check on the artifact: `android.util.Log.d(...)` → `iconst_0; ireturn`,
`android.content.ContextWrapper(Context)` → `aload_0; invokespecial
android/content/Context."<init>":()V; return` (construction succeeds, every other
method returns 0/false/null). Robolectric classes still get the **real**
`android.jar` (Robolectric instruments its own `android-all`), so the swap is
per-class-kind, and `focused-final-run-summary.json` records the runtime used per
class in `results[].android_runtime`.

The 4 750 number is not truncation: the SDK jar is 6 227 classes and 1 477 of
them are exactly the JDK/JUnit mirrors AGP deliberately skips.

### 2b. Classpath-ordering bug found while verifying

The first attempt at the swap still produced `Stub!`: `base_cp` (the production
dependency list) starts with the real `android.jar`, and on a JVM classpath the
first match wins, so the real stub classes were still loaded even though the
mockable jar was appended later. The runner now strips `android.jar` out of
`base_cp` for plain-JUnit classes and appends `mockable` → `android.jar` last.
This is the difference between `full-1` (46 failures, `android-stub` ENV-GAP on
Kairos/SoulPromptRules, 26 failures on storage) and `final-verified`
(0 harness failures).

## 3. The 3 "real" assertion failures — all three were test expectations

None of them was a production defect; the runner did not change production code
or any assertion. Each was corrected by the test author and now passes.

| test | old assertion | what the source actually does | now |
|---|---|---|---|
| `agent.SoulBodyLimitPolicyTest` | expected `SoulProfiles.save(` inside the `soul.body` writer slice | the writer slice ends at `saveCurrent(cur.copy(body = raw))`; the profile save lives in the `saveCurrent` helper of the same `registerSoul` scope | 6/6 PASS |
| `agent.SystemPromptInjectionWiringTest` | `selectedSoulForTurn()` must contain `SoulProfiles.load(context, draft)` | the turn snapshot reads the picked profile as `SoulProfiles.load(context, pending.profileId)` (the draft variable was renamed) | 9/9 PASS |
| `groups.ModelGroupAvatarImageTest` | `scaledSize(3000, 1, 192) == (1, 1)` | long-edge-preserving bounds: `(192, 1)`, and the vertical case `(1, 3000) → (1, 192)`; a 1-px edge is preserved, not inflated to the max edge | 6/6 PASS |

## 4. The 18 ENV-GAP failures — unchanged, still unverified

Both classes carry `@GraphicsMode(NATIVE)`; Robolectric 4.17 aborts with
`The Robolectric native runtime is not supported on Linux (aarch64)`. The runner
classifies each failing test by its exception line, so these show up as
`env_gap_kind: robolectric-native` and are excluded from the pass count.

| class | tests | failing |
|---|---|---|
| `agent.SoulIconImportRoboTest` | 13 | 13 |
| `groups.ModelGroupAvatarImageRoboTest` | 5 | 5 |

First failure of each is in `log-SoulIconImportRoboTest.txt` /
`log-ModelGroupAvatarImageRoboTest.txt`
(`java.lang.AssertionError: The Robolectric native runtime is not supported on
this platform (linux-aarch64)`). They need an x86_64 host with the same bundle;
Gradle's own `testDebugUnitTest` on CI is authoritative for them.

## 5. Source snapshot: the one comment-only difference

The run compiled production from a snapshot taken at `20261116T2047`:
1 396 files, aggregate sha256 (first 32) `6d630cec1350d6f78280604a54cb9cbc`,
`kotlinc rc=0`, `javac rc=0`, 820 Kotlin + 4 Java sources → 8 216 classes.

After the run, `ui/chat/ChatViewModel.kt` lost 5 lines that are pure comments
(the stale `[K-agents-supreme]` note at compiled line 16740-16744):

```
compiled 20 692 lines / current 20 687 lines
difflib ratio 0.99987917
opcodes: one 'delete', 5 lines, every differing line is a comment -> code_changed = false
```

So the compiled snapshot and the frozen tree are equivalent at bytecode level;
no recompile was needed or performed after the 5-line deletion.

## 6. Reproduce (no external temp tree required)

```sh
python3 scripts/verification/check_soul_groups_tests.py \
  --out /var/minis/workspace/tmp/minis-verify-$(date +%Y%m%d) \
  --label focused
```

Everything the run needs is already on the host: the Gradle module cache
(`/var/cache/minis/gradle`), the SDK (`/opt/android-sdk`), the offline JUnit /
Robolectric bundle (`/var/cache/minis/ui-verify-robolectric`) and AGP's builder
jar (`com.android.tools.build:builder`). The mockable android.jar is generated
into `<out>/shim/mockable/` and cached by android.jar content hash; the second
run reuses it (`cache_hit: true`, ~5 s saved).

Useful variants:

```sh
# exactly the harness-fix classes only
python3 scripts/verification/check_soul_groups_tests.py --out <out> --label probe \
  --no-default-tests --tests com.openminis.app.agent.SoulProfilesStorageTest,\
com.openminis.app.agent.KairosPromptModeTest,com.openminis.app.agent.SoulPromptRulesTest,\
com.openminis.app.groups.ModelGroupAvatarPersistenceTest

# prove the mockable jar is what makes the difference
python3 scripts/verification/check_soul_groups_tests.py --out <out> --label no-mockable \
  --no-default-tests --tests com.openminis.app.agent.SoulProfilesStorageTest \
  --no-mockable-android        # -> 26 failures, all RuntimeException: Stub!
```

## 7. What is proven, and what is not

Proven: the 232 selected JUnit tests compile against the real production
classes and, for 214 of them, behave correctly on a JVM with Gradle's
`returnDefaultValues` android runtime and Gradle's declared test-only jars.

Not proven: the 18 Robolectric NATIVE tests (host limitation), anything about
the APK (no aapt2/KSP/dex/R8, no device), and — as always — a missing test is
not a passing test: `pinned_missing` in the summary is empty for this run.