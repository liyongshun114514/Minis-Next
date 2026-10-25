# Ubuntu preinstalled environment — 1.15-ubuntu.2

## User-facing contract

The APK contains a complete Ubuntu 24.04 arm64 tool environment. Installing the
app's environment is an offline extraction, not an apt job started when the
terminal opens. Both PRoot and Chroot use the same preinstalled rootfs.

Included default packages:
`bash ca-certificates curl wget git openssh-client less procps iproute2 unzip xz-utils jq ripgrep python3 python3-pip python3-venv`.

The terminal uses Bash for interactive login; `/bin/sh` remains Dash for POSIX
agent commands. Profile hooks only inspect local package state and never run
apt. `minis-bootstrap --force` is an explicit maintenance command, not part of
first-start setup. Maintenance disables apt's dpkg PTY and redirects stdin from
`/dev/null`, retaining actual failure exit codes and printing the log tail.

## Reason for the change

The user's real `minis-bootstrap.log` showed packages configured successfully,
followed by apt's `tcsetattr` / `TCSAFLUSH` terminal restoration error. The old
script marked the entire environment failed. A separate uploaded `bootstrap.log`
was byte-identical to the official Ubuntu image's build log, not the phone log.
The revised design removes first-terminal apt completely instead of hiding the
error or creating a success marker without installed packages.

## Build pipeline

1. GitHub Hosted `ubuntu-24.04-arm` job runs `build_ubuntu_toolset.sh`.
2. The base fetcher validates official Ubuntu 24.04.5 signatures and pinned
   SHA-256. Native ARM64 guest commands run only in the CI runner's disposable
   chroot with a private mount namespace; the script refuses Android/PRoot.
3. Signed official apt repositories install the complete list. Services are
   blocked by temporary policy-rc.d. No insecure repository override is used.
4. Every required package must be `install ok installed`, `dpkg --audit` clean,
   and version/venv probes must run successfully before completion is recorded.
5. The generated image includes `usr/local/share/minis/toolset-manifest.json`
   with schema, image identity, official-base digest, exact package versions
   and command outputs. The image and its SHA-256 are passed as an artifact to
   the Android build job. Package updates are recorded, not claimed bit-for-bit
   reproducible across dates.
6. The APK verifier requires the preinstalled manifest, completion marker,
   matching dpkg package states/versions and tool paths, and checks payload
   SHA-256 against the prepared image. Launcher, signing and alignment checks
   remain mandatory. A bare Ubuntu base cannot pass as the final environment.

For non-CI builds, obtain the checked toolset image and its `.sha256`, put them
under `deps/build/ubuntu-toolset/`, then run `prepare_android_sandbox.sh`.
`prepare_android_ubuntu.sh` fetches only the official base for the CI builder;
it is no longer sufficient to produce the final app environment by itself.

## On-device install and data

RootfsManager validates the extracted complete toolset before marking Installed
or swapping into place. A preinstalled manifest alone or a marker alone is not
enough: local package states and executable files must exist. An older bare or
incomplete guest is moved to a sibling backup before replacement; user chat and
shared files are not deleted. No automatic apt runs on the device.

The Rootfs settings offer equal-width PRoot/Chroot buttons; actual switches
still require the existing availability, activity and authorization checks.
Native Root CLI uses the same integration-card layout as Shizuku CLI, while
keeping its default-off and per-command confirmation policy.

## Validation limits

Host ordinary unit tests and static checks do not replace the complete CI
build. Native ARM64 preinstallation, APK generation and final verification must
all succeed before an APK is delivered. No on-device Root, APK installation,
terminal interaction or visual UI verification is performed by this task.
