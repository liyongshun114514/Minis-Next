# SOUL groups 1.7.0 — final offline verification

Version: **1.7.0 / versionCode 58**, baseline `ec2caea`. Host: Ubuntu 24.04 aarch64, JDK 21.

## Final result

**214 tests PASS; 18 tests ENV-GAP; zero remaining ordinary JUnit failures.**

The final run `final-verified` compiled 820 Kotlin + 4 Java production files (8,216 classes; compiler exit codes 0) and all 27 selected test classes. Ordinary JUnit uses AGP's own `MockableJarGenerator` with `returnDefaultValues=true`, matching the app Gradle configuration. SQLite JDBC is resolved from the existing Gradle cache; the row-preserving v5→v6 migration test passed.

The only unverified tests are `SoulIconImportRoboTest` (13) and `ModelGroupAvatarImageRoboTest` (5): Robolectric NATIVE graphics rejects Linux/aarch64. Both remain selected in x86_64 GitHub Actions. ENV-GAP never counts as a pass; the runner returns exit code 1 on this host.

Canonical evidence:

- [Final machine-readable result](focused-final-run-summary.json)
- [Final per-class report](focused-final-run-summary.md)
- [Failure classification and resolution](focused-failure-classification.md)
- [Source snapshot hash](source-snapshot.sha256.txt)
- [Resolved test dependencies](test-only-deps.tsv)

The compiler snapshot differs from the final worktree only by removal of five obsolete comment lines; no executable code changed after the final compile. `typecheck-final2-summary.json` is an earlier successful typecheck, not the final acceptance snapshot; the final compiler results are in `focused-final-run-summary.json`.

## Resolved defects and harness issues

- Identity and SOUL body now derive from one per-loop snapshot, including after a concurrency-slot wait.
- Draft promotion and later SOUL taps use the same FIFO write chain, so an older migration cannot overwrite a newer selection.
- Three stale test expectations were corrected: save-helper scope, renamed pending-choice variable, and aspect-preserving extreme image dimensions.
- The runner now uses mockable Android classes before the real stub jar and resolves declared SQLite JDBC. It does not replace application classes or business methods.
- Prompt catalog, all Chinese resources (2496 strings + 13 plurals), 42 icon assets, version/workflow contracts and 125 composer/SOUL static checks passed.

## Reproduce

```sh
python3 scripts/verification/check_soul_groups_tests.py \
  --out /var/minis/workspace/tmp/minis-soul-groups-verify --label final
```

The script resolves existing SDK/Gradle/offline-test-bundle inputs and generates temporary compile/runtime artifacts under the supplied output directory. It does not depend on retained conversation temporary files.

## Limits

No APK was built or installed by this check. R/BuildConfig and the missing gomobile API use compile-only stubs. Resource packaging, KSP/Room generation, native bitmap tests and device behavior remain outside this verification. APK and native graphics verification run through GitHub Actions. Temporary compiler outputs are removed after evidence is archived.
