# Kairos hourglass identity

- `hourglass-source.png`: maintained 1254 × 1254 ivory/amber hourglass artwork; SHA-256 `f596cb05e9c2dbde33aa97c5a1994cb5db0a921e8f47c4626c65d24ba5884d55`.
- Replaces the 1.0.0 orbital-star icon in Kairos 1.0.1.
- `icons-preview.png`: dark and light launcher variants.
- `release-signing-certificate.pem`: **public certificate only**, safe to distribute; it is not a private signing key.

Rebuild launcher density PNGs, adaptive-icon XML and the same-artwork monochrome silhouette with:

```sh
python3 scripts/gen_kairos_icons.py
```

This script requires Pillow and numpy, not an image-generation service. The source artwork was created for Kairos with GPT Image 2.5 Sunburst; rebuilding from it does not make a model/API request. The hourglass stays within the adaptive circular safe region. Image assets do not alter Android permissions, launcher alias behavior or model instructions.

The private release keystore and password are intentionally absent from this repository, all APKs and published artifacts. Future release builds must use the same private Kairos key. Public certificate SHA-256:

`1DEDA3DB350D32849D53212D29F210B64CF5F0401BA36BA7C3C3791994B3C628`
