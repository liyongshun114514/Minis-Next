# Android execution backends: PRoot and chroot

Status: implemented in source, compiled for C, **not verified on a device**.
See “What is verified / what is not” at the end before shipping.

## 1. Why a second backend

PRoot is the default and stays the default. It is unprivileged, works on every
device, and carries `native_offload` — the mechanism every `android-*`,
`minis-*` and `model_use` tool depends on.

Chroot exists for the cases PRoot cannot serve: code that needs real
`chroot(2)` semantics (a package that resolves `/proc/self/root`, a runtime
that statically links against an absolute loader path, systemd-style service
supervision experiments). Both backends run **the same rootfs**; switching is a
routing change, not a migration. No user data is copied, moved or deleted in
either direction.

## 2. Architecture

```
              ┌──────────────────────────── app process (app uid) ───────────────────────────┐
SandboxRouter │  SandboxBackendStore.selected(context)                                        │
              │     PROOT  → PRootKernel.buildProotCommand / buildProotInteractiveCommand     │
              │     CHROOT → ChrootKernel.clientArgv  → libminis-chroot.so --client …         │
              └───────────────────────────────────────────────────────────────────────────────┘
                                    │  PROOT: proot + native_offload
                                    │  CHROOT: unix stream socket (abstract name)
                                    ▼
                         ┌──────────────────────────────────────────┐
                         │ libminis-chroot.so --serve   (uid 0)     │  started once per app run
                         │  • bound socket @minis-chroot-<gen>      │  through RootManager
                         │  • per request: fork → unshare(NEWNS)    │  (per-invocation confirm)
                         │    → mounts → chroot → setuid(app) → exec│
                         └──────────────────────────────────────────┘
                                    │
                                    ▼  command runs as the APP uid inside the private namespace
```

Files:

| file | role |
|---|---|
| `sandbox/SandboxBackendStore.kt` | persisted selection, per-process grant, switch policy, activity registry, router |
| `sandbox/ChrootKernel.kt` | argv/mount policy/lifecycle/availability, no Android-root code |
| `sandbox/ChrootPrivilege.kt` | the only place root is requested — `RootManager.runProcess` |
| `sandbox/PRootKernel.kt` | added `buildProotCommand(cmd, mounts)`, `buildProotInteractiveCommand`, `refreshOffloadStubs`, chroot stub hook at boot; existing API unchanged |
| `sandbox/{ShellExecutor,FreshProcessShell,PersistentShell,TerminalSession}.kt` | all four spawn sites route through `SandboxRouter`; backend-specific env is separated |
| `sandbox/RootfsManagementScreen.kt` | backend section, risk dialog, active-task dialog, Root Bridge settings entry |
| `cpp/minis_chroot.c` | privileged helper + unprivileged client, one protocol |
| `cpp/minis_offload_client.c` | guest-side native_offload client (same wire protocol as proot) |
| `test/.../ChrootBackendTest.kt` | pure-JVM tests |

## 3. Switching: refuse, never silently cut

`SandboxSwitchPolicy.decide(...)` is pure and returns a value:

* **NoChange** — same backend.
* **RefusedActiveTasks(tasks)** — anything running (agent shell via
  `FreshProcessShell`/`PersistentShell`, or a live PTY) is listed. The UI shows
  the list and offers “stop and switch”; only that second, explicit action
  passes `stopActive = true`, which stops exactly those tasks and re-runs the
  decision. Nothing is killed by the first attempt.
* **RefusedUnavailable** — helper not packaged, or root bridge missing.
* **Allowed(stopTasks, requiresRootGrant)** — `requiresRootGrant` is true only
  when switching **to** chroot without a grant for this app process.

Ordering in `SandboxSwitchController.requestSwitch`: decide → stop (if
confirmed) → `ChrootKernel.ensureServer` / `ChrootKernel.shutdown` → persist
LAST. A failed handshake therefore leaves the previous backend selected.

## 4. Grant model

Two separate gates, both required:

1. **Persisted risk acknowledgement** (`SandboxBackendStore.authorizedAt`),
   written only by the settings dialog that lists what chroot does and does not
   change. Persisted so the UI can show “you enabled this on …”.
2. **Runtime grant** (`grantChrootForProcess`), in memory only, valid for one
   helper generation in one app process. Restart, process death, or a switch
   back to PRoot (`revokeChrootGrant`) drops it. Enabling chroot can never mean
   “root forever”, and nothing outside the settings UI can set either gate: no
   shell, tool, offload handler or guest program exposes a path to it.

The helper launch itself goes through `RootManager.runProcess`, which shows the
exact argv with a fresh per-invocation confirmation. The user's command is
**never** part of that argv — that is the whole point of the socket protocol.

## 5. Native offload under chroot

PRoot's extension intercepts `execve`; chroot has no ptrace layer, so the
`/usr/local/bin/minis-*` stubs would print “unavailable”. Two honest outcomes:

* `minis-offload-client` present in the rootfs
  (`/usr/local/lib/minis/`, installed at boot by `ChrootKernel.onRootfsReady`) →
  `SandboxOffloadRoute.GUEST_CLIENT`. The wrapper execs it; it connects to
  `MINIS_NOFF_SOCKET`, sends the same `MAGIC_REQ` frame the proot extension
  sends, reads the reply file, prints it and exits with the handler's code.
  Peer uid is the app uid, which is what `NativeOffloadServer`'s check accepts.
* client absent → `SandboxOffloadRoute.BLOCKED` **and** the settings row says
  “Minis tools BLOCKED”. The stub keeps the `EX_UNAVAILABLE` (69) message.
  The backend never silently pretends Android tools work.

The client deletes its reply file after copying it; the proot path leaves that
to the server's age-gated sweep.

## 6. Package management (honest limitation)

Commands run as the **app uid**, not root — that is the security boundary and it
is not negotiable for the sake of convenience. Consequences a user will notice
inside the rootfs:

* `apt-get install`, `dpkg -i`, `adduser`, `systemctl`, `chown root:root` and
  anything else that checks `geteuid() == 0` **will refuse** or fail. This is
  reported truthfully: the package manager prints its own error.
* Files created by the chroot backend are owned by the app uid; the PRoot
  backend's fake root (`-0`) sees them as root, so switching back is harmless.
* To install system packages, switch to PRoot (default), install, switch back.
  The rootfs is shared, so the packages are there afterwards.

The backend does not fake root inside the guest and does not silently claim
package management works.

## 7. Mounts and namespace policy

Per command, in the forked child, before `execve`:

1. `unshare(CLONE_NEWNS)` + `mount("/", MS_REC|MS_PRIVATE)` — nothing below can
   propagate to the real mount table.
2. fresh `proc` on `<rootfs>/proc`.
3. `/dev`: tmpfs, then individual binds of `null, zero, full, random, urandom,
   tty` — not the whole host `/dev`; `devpts` mounted (or bind fallback);
   `/dev/ptmx` → `pts/ptmx`.
4. `/sys`: recursive bind, then remount **inside the namespace only**, read-only.
5. `/var/minis/*` and user mounts: each pair validated twice —
   `ChrootKernel.validateMounts` (guest target must be under a fixed allowlist,
   host source must resolve under `filesDir`/`cacheDir`/external storage/mounted
   volumes, no `..`, no control chars, no `:`), then the C helper re-checks with
   `realpath()` against its `--allow-root` list. Read-only mounts (from
   `MountedFoldersStore.effectiveWritable`) get `MS_REMOUNT|MS_RDONLY`.
6. `chroot(rootfs)`, `chdir(cwd)`, `umask(022)`, `setgroups(0)`, `setgid/setuid`
   to the **app uid**, `setsid()`, `execvpe`.

Explicitly **not** done: `setenforce`, any SELinux policy change, any remount of
a real filesystem, any change to init/partitions/boot, any mount of `/`,
`/data`, `/system`. Teardown is namespace death; the helper’s exit, the idle
TTL (15 min default) or `--control stop` removes the only persistent artefact —
an abstract socket name, which is not a file.

## 8. Exit codes, sessions, environment

* The client passes its own stdio fds (SCM_RIGHTS); the server child `dup2`s
  them. The host therefore holds a process whose stdout/stderr are the
  command’s, whose `waitFor()` is exact, and whose SIGKILL (Stop / timeout)
  reaches the child because the server kills the child’s process group on
  client disconnect.
* `ForegroundCommandGroup.wrapForFreshProcess` is still used for chroot, so the
  `.minis-exit` status file and pid file keep working — real exit codes,
  including 124 (timeout) and 130 (Stop), unchanged.
* Interactive terminals pass `--tty`; the pty slave is the client’s fd 0/1/2,
  so readline, Ctrl-C and window size keep working.
* Environment: the guest gets `PATH/HOME/BROWSER/ENV/CHARSET/TZ/NO_COLOR/…`,
  proxy variables, `MINIS_NOFF_SOCKET`, `MINIS_CHAT_SESSION_ID`. The
  link2symlink workarounds (`GIT_CONFIG_*`, `UV_LINK_MODE`) are **not** exported
  under chroot, where hardlinks work normally.

## 9. Build integration

`scripts/build_chroot_helpers.sh` (owned by the main agent) builds:

* `minis_chroot.c` → `jniLibs/arm64-v8a/libminis-chroot.so` with
  `-fPIE -pie`: an **executable PIE**, not a loadable library. It must not be
  built as `SHARED`/`add_library(... SHARED ...)`, which would drop `main()`.
  Packaged under the name `ChrootKernel.LAUNCHER_NAME`.
* `minis_offload_client.c` → `assets/chroot/minis-offload-client`, static.

Runtime install of the guest client is implemented in
`ChrootKernel.installOffloadClient`: `assets.open("chroot/minis-offload-client")`
→ `<rootfs>/usr/local/lib/minis/.minis-offload-client.tmp` → `chmod 0755` →
`rename()`. It never goes through `RootfsManager`, and a half-written binary is
never visible under the final name. If the asset is absent the backend reports
`BLOCKED` and `/usr/local/bin/minis-*` keeps saying "unavailable" (exit 69).

`RootManager` must stay initialised at startup and “Native Root” enabled; that
is the only wiring `ChrootPrivilege` needs.

## 10. Security review notes (post-review fixes)

Applied after the main agent's review, no vulnerability reproduction performed:

1. **SO_PEERCRED** on every accepted connection: peer uid must equal the
   `--uid` the helper was started with. Abstract sockets have no filesystem
   permissions, so this is the only thing that keeps other apps out.
2. `argv`/`envp` are allocated with `count + 1` and a NULL terminator; every
   allocation and read failure frees and aborts the request. Embedded NUL in a
   frame string is refused.
3. SIGCHLD stays at its default — `SIG_IGN` makes `waitpid()` return `ECHILD`
   and a killed command would read as success.
4. Daemon fork failure is an error (exit 1, no handshake); the handshake names
   the **daemon** pid; fd 0 is `/dev/null` and 1/2 go to `helper.log`; the stop
   client runs as the app uid (never through `RootManager`, which would be
   uid 0 and correctly rejected by the peer check).
5. `File(root).isAbsolute` (a `String` has no `isAbsolute`).
6. The helper is a PIE **executable** (`-fPIE -pie`), not a shared library.
7. Mount targets: `lstat` (symlinks refused), type must be directory or char
   device, and `realpath(target)` must stay inside `realpath(rootfs)` — a
   symlink inside the user-writable rootfs can no longer place a mount on the
   host's `/data` or `/system`. The host source is also `realpath`-checked
   against `--allow-root`.
8. One **fork per connection**, so a long-lived terminal shell cannot block
   every other request; Stop/EOF on the client kills the command's process
   group. Idle TTL plus `--control stop` bound the helper's lifetime.
9. The launch is accepted only when the bridge returns exit code **0** and the
   handshake names this generation.
10. App-uid execution is documented as a real limitation (§6), not papered
    over.

## 11. What is verified / what is not

Verified in this workspace (no device root used):

* `minis_chroot.c` and `minis_offload_client.c` compile clean with
  `aarch64-linux-android24-clang -std=c11 -O2 -Wall -Wextra` (NDK r28).
* Pure-JVM tests: switch policy (refusal vs confirmed stop), mount allowlist
  (escape, `..`, `:`, `/`, `/etc`), argv shapes (command only after `--`,
  privileged argv free of user data), handshake nonce matching, availability
  wording, protocol constants matched against `NativeOffload.kt`, and source
  facts for the four spawn sites. `ChrootBackendTest`.
* `./gradlew testDebugUnitTest --tests '*ChrootBackendTest'` was attempted;
  see the delivery report for whether the offline Gradle cache allowed it in
  this environment. If it did not run, the tests are still compile-ready and
  should be run in CI.

NOT verified (needs a rooted device):

* `unshare`/`mount`/`chroot`/`setuid` behaviour on a real Android kernel and
  SELinux domain (the helper is started via `su`, so it runs in `su`'s domain —
  mount and mknod inside app data are expected to work, but this is unproven).
* The handshake against `RootManager`’s confirmation UI.
* PTY interaction through the fd-passing path.
* `devpts` fallback on older kernels.

Any failure is designed to surface as: exit 97 (helper not running), 98
(rejected request), 126 (setup/drop failed), 127 (exec failed) — never as a
silent success.
