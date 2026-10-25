# Minis Ubuntu — development fork

This branch replaces the Android Alpine guest with **Ubuntu 24.04 LTS arm64**.
The iOS iSH/Alpine guest is unchanged. It is an independent Android installation:
`com.openminis.app.ubuntu`, labelled **Minis Ubuntu**, test-signed by Actions.
Do not uninstall the upstream app to try it. Export/import configuration and data
explicitly; an independent package cannot read the upstream app's private data.

## Features and scope

* Official Ubuntu Base asset with pinned SHA-256, apt/deb822 sources, guest DNS,
  mirrors and first-run tool bootstrap. A legacy guest inside this app's own
  data directory is preserved as a sibling backup rather than overwritten.
* Default non-root **PRoot** backend and selectable **Chroot** backend. Switching
  refuses active work or explicitly stops it; no guest data reset is required.
* Chroot setup requires a fresh Root confirmation, a working su implementation,
  kernel namespace/mount support and compatible SELinux policy. This fork does
  not disable SELinux or alter partitions. Commands drop to the app UID after
  entering the chroot; **apt/dpkg installs must be performed in PRoot mode**.
  Do not confuse guest PRoot uid 0, app UID in Chroot, and Android device Root.
* **Native Root**, parallel to the source's Shizuku integration: state/permission
  page, `android-root-cli`, grouped package/device/settings/file APIs and `exec`.
  Default disabled. Every invocation needs native approval of the exact argv;
  no cached session-wide approval, no Xposed and no separate socket Root bridge.
* Native Composer merged attachment/command menu, two-line centred title and
  particle thinking-level slider; existing model/rename/send callbacks retained.
* Soul body-length mode: native limits, unlimited counter, or custom limits.
  This changes only application length handling. Native prompt construction,
  injection checks and instruction priority are preserved.

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
