# Minis Ubuntu — development fork

This branch replaces the Android Alpine guest with **Ubuntu 24.04 LTS arm64**.
The iOS iSH/Alpine guest is unchanged. It is an independent Android installation:
`com.openminis.app.ubuntu.next`, labelled **Minis Ubuntu Next**, test-signed by Actions.
This package also coexists with the earlier Ubuntu test builds, whose signing keys changed.
Do not uninstall the upstream app to try it. Export/import configuration and data
explicitly; an independent package cannot read the upstream app's private data.

## Features and scope

* Official Ubuntu Base asset with pinned SHA-256, apt/deb822 sources, guest DNS,
  mirrors and first-run tool bootstrap. A legacy guest inside this app's own
  data directory is preserved as a sibling backup rather than overwritten.
* Default non-root **PRoot** backend and selectable **Chroot** backend. Switching
  refuses active work or explicitly stops it; no guest data reset is required.
* Chroot uses the Root grant from Settings → Permissions with no additional
  application confirmation on switching. It requires working su, kernel
  namespace/mount support and compatible SELinux policy. The device su manager
  still controls actual access. Commands drop to the app UID after entering
  the chroot; **apt/dpkg installs must be performed in PRoot mode**.
* **Native Root**, parallel to Shizuku: default off, explicitly granted in the
  permission page. Granted calls run directly until revoked. An agent tool
  request while off can request approval for that invocation; rejecting it
  runs nothing and does not change the settings switch.
* Original upstream chat UI restored from pre-port commit `6eda552`;
  MinisAllInOne Composer toolbar, particle slider and merged menu removed.
* Settings → **System Prompt** contains avatar, name and one system-prompt
  editor. Existing SOUL.md data remains compatible. Prompt text is sent through
  the provider's system/instructions field; length and validation remain active.

## Build in GitHub Actions

Workflow: `.github/workflows/android-ubuntu.yml` (**Android Ubuntu APK**).
It runs on pushes to `ubuntu-chroot` and can be dispatched manually after it is
visible on the repository's default branch. The runner installs SDK/NDK/Go,
builds PRoot and rclone, prepares the verified Ubuntu asset, compiles both
Chroot helpers, builds a release APK and uploads APK/checksum artifacts.

The initial dependency build can be lengthy. Go, Gradle, native outputs and the
Ubuntu archive have caches; no on-device full APK compilation is needed.
The cached debug signing key is for testing, not production. If its cache is
removed a newly generated key may prevent in-place upgrades of earlier test
builds; use a private release-signing Secret for a production distribution.

## Verification levels

Source changes and ordinary offline configuration/permission tests are not
proof of APK compilation or device operation. Each Actions run must finish with
a successful build and APK ZIP/ABI/signature/alignment checks before an APK is
called built. Chroot, native Root, first-run apt, terminal interaction, merged
menus and visual behaviour still require explicit device acceptance. Building
an APK never authorizes installing it or executing Root on a device.

More detail: `android-ubuntu.md`, `android-chroot.md`, `native-composer-port.md`.

## Sources and licensing

Base project: https://github.com/OpenMinis/OpenMinis, GPL-3.0.
Port input: user-supplied `MinisAllInOne-1.0.2-source.zip`, GPL-3.0; its licence
is retained in `third_party/minis-all-in-one/LICENSE`. Composer observable UI
behaviour was reimplemented with Compose. RootExecutor, ShellCommand,
RootExecutorConfig and WireProtocol derive from its Root Bridge and are adapted
to native routing and per-command confirmation. Xposed hooks, compatibility
maps, DEX scanners and the precompiled title DEX are deliberately not bundled.

Ubuntu Base contains separately licensed Ubuntu packages. Their notices and
licences remain in the guest under `/usr/share/doc/*/copyright`; Ubuntu archive
signing keys and package metadata remain intact. See THIRD_PARTY_LICENSES.md.
