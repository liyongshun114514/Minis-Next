# Minis Next 1.7.8 (66): one glass status panel

The latest request supersedes the separate-surfaces layout in 1.7.7: keep sub-agent content above work content, but merge both into one rounded glass panel using the same renderer/material/backdrop as the chat input.

- One background pass covers both rows when sub-agent status exists. The upper entry remains content-only, with no inner rounded border/shadow or duplicate tint.
- Remove the 4dp gap introduced by the separate surfaces. Both rows total 65dp, matching the preview's 65dp; the previous 69dp panel left the preview bottom 4dp short.
- Bottom-align the preview and use shared dimension constants. Preserve the no-sub-agent thumbnail overhang, tool navigation, expand/collapse behavior and 1.7.7 synchronous visibility/height fixes.
- Update geometry contracts and native pixel tests: verify the center seam retains the same fill, the preview top/bottom align with the merged panel, theme/preview toggles retain content, and the real-backdrop panel matches the input.

Local validation: 23 JVM geometry/source contracts and 10 Python identity/build checks pass. Native Compose pixel checks and APK verification await GitHub Actions; physical-device appearance is not yet verified.
