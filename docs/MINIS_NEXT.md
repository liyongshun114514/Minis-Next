# Minis Next 1.1 / code 46

This update repairs native math plot drag/hit-testing and continuous sliders, restores the composer's original 20dp corners with 16dp bottom safe-area clearance, adds a per-provider custom HTTPS balance URL, and replaces discovered models with each successful live catalog (failed reads retain the old catalog and show an error).

Current branch: `minis-next`, repository https://github.com/liyongshun114514/Minis-Next . Continues Kairos 1.0.2 functions without removing features; display name Minis Next, applicationId com.openminis.app.next, namespace com.openminis.app. Launcher icons remain the pinned upstream originals. `docs/KAIROS.md` is historical, not current installation/signing guidance.

## New stable signing identity

Public certificate SHA-256: `f1464cfe2b280348ee1fa611733dc55818bdcdcae659dd4fcbf27421a5a1065f`.

Release variants use explicit NEXT_KEYSTORE and NEXT_STORE_PASSWORD and alias minisnext; CI restores NEXT_KEYSTORE_BASE64 and NEXT_STORE_PASSWORD from encrypted repository secrets. Secrets/key material are never committed. This is NOT the old Next debug certificate or Kairos release certificate. **Cannot directly update either differently signed app.** Back up in the old app before any uninstall; do not claim uninstall is lossless. No automatic migration, uninstall, installation or device Root is performed by the build workflow.

## Intelligent UI

See [INTELLIGENT_UI.md](INTELLIGENT_UI.md) for protocol, native component catalog, upstream attribution, limitations and tests. Existing tools, terminal and web-preview functionality remain; generated Intelligent UI cards do not execute web code or replace approval prompts.

## Provider settings in 1.1

In the provider's Quota section, **Balance URL** accepts a complete HTTPS endpoint; blank restores automatic detection. The configured endpoint receives the instance's API key using Bearer authentication. Redirects are not followed. Common balance response shapes (DeepSeek, Moonshot, SiliconFlow, New API, Sub2API, billing, LiteLLM, Novita and root/data balance fields) are supported; unreadable responses are errors, never invented zero balances. An explicit ISO currency is kept; unknown sites do not get a guessed currency. Room v4→v5 adds one nullable column without deleting existing rows, and older backups default to automatic detection.

Each model refresh bypasses discovery caches. A successful catalog replaces upstream-discovered ids immediately, including an empty catalog; errors keep the previous list with a visible reason. Existing ids keep user overrides/hidden state. Hand-added custom rows and voice templates that are not supplied by the catalog are retained. No public catalog or compiled-in image list tops up a successful live answer. Removed ids are pruned from group and agent-loop references.

## Rebuild and verify

Run `.github/workflows/android-ubuntu.yml` on `minis-next` with variant release. It creates a verified preinstalled Ubuntu rootfs, compiles helpers, executes selected JVM/Compose tests, assembles Release and verifies package, launcher aliases, signature/alignment and the final APK's production rootfs extraction. Missing signing credentials reject the build. `scripts/stage_kairos_release.py` retains its historical filename but stages **MinisNext** artifacts with the new identity and exact certificate check into `release/minis-next`.

Actions artifact: `MinisNext-arm64-<run_number>`. APK protocol: `MinisNext-1.1-build46-arm64-v8a.apk`, plus .sha256, release-metadata.json and mapping. No Release or tag is created in this iteration. Cloud build/test success is not a device installation or runtime UI validation claim.
