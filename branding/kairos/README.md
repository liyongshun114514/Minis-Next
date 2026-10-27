# Kairos star-ring identity

- `star-ring-source.png`: maintained 1254 × 1254 generated source artwork; SHA-256 `4ef53318c4ea616063aef9e2b2b38fc59035f3a5fa5d3bc133f41222272fe389`.
- `icons-preview.png`: dark and light launcher variants.
- `release-signing-certificate.pem`: **public certificate only**, safe to distribute; it is not a private signing key.

Rebuild launcher density PNGs, adaptive-icon XML and the themed monochrome orbit/star silhouette with:

```sh
python3 scripts/gen_kairos_icons.py
```

This script requires Pillow and numpy, not an image-generation service. The source artwork was created for Kairos with GPT Image 2.5 Sunburst; rebuilding from it does not make a model/API request. The ring stays within the adaptive circular safe region. Image assets do not alter Android permissions, launcher alias behavior or model instructions.

The private release keystore and password are intentionally absent from this repository, all APKs and published artifacts. Future release builds must use the same private Kairos key. Public certificate SHA-256:

`1DEDA3DB350D32849D53212D29F210B64CF5F0401BA36BA7C3C3791994B3C628`
