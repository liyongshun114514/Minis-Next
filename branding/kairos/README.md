# Kairos launcher identity (1.0.2)

The application name remains **Kairos**. Launcher icons are the original OpenMinis assets, restored byte-for-byte from upstream commit `6eda5523a9c14fa8edc4fd9fb72e643fe47f23be` (v1.15 merge), including all five densities, night variants, light/dark adaptive icons and the vector monochrome silhouette.

- `upstream-icons.json`: pinned source commit and SHA-256 for all 40 launcher resources.
- Prior generated hourglass art and its preview are no longer used or retained; the earlier version remains available in Git history.
- `release-signing-certificate.pem`: **public certificate only**, safe to distribute; it is not a private signing key.

Verify the committed resources with `python3 -m unittest scripts.tests.test_kairos_identity`. To restore them from a full Git checkout containing the pinned upstream history:

```sh
python3 scripts/gen_kairos_icons.py
```

The restoration script needs only Python and Git, not Pillow, numpy or an image service. Source archives already contain all resources and the hash manifest; no regeneration is needed to build an APK. Icons do not alter Android permissions, launcher aliases or model instructions.

The private release keystore and password are intentionally absent from this repository, all APKs and published artifacts. Future release builds must use the same private Kairos key. Public certificate SHA-256:

`1DEDA3DB350D32849D53212D29F210B64CF5F0401BA36BA7C3C3791994B3C628`
