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
                         │  • bound socket @minis-chroot-v2-<gen>   │  through RootManager
                         │  • per request: fork → unshare(NEWNS)    │  (per-invocation confirm)
                         │    → mounts → chroot → setuid(0) → exec  │
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
| `test/.../UbuntuNormalCompatibilityTest.kt` | ordinary asset/source/bootstrap configuration checks |

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

Chroot is a **real uid-0 backend**. Inside the guest, commands run as root so
`apt`/`dpkg` can chown files and write `/var/lib/dpkg`; the helper still runs in
a private mount namespace with `setgroups(0)` / `NoNewPrivs`, and the socket
still authenticates the app uid for the offload socket (via the client's
temporary euid switch), while the command itself keeps real uid 0. Chroot is
not a security boundary and the docs say so.

Every spawn (shell_execute, warm/fresh shell, terminal, `android-chroot-cli`)
is decided by the ONE unified Root tri-state (设置 → 权限 → 原生 Root) at
`ChrootKernel.spawn`, before any helper or `su` runs:

| Level | Chroot command / interactive session | Helper launch |
|---|---|---|
| 完全允许 | uid 0 directly, no app dialog | direct |
| 每次询问 | one explicit confirmation per command / per terminal session; refusal, cancel and the 120 s timeout execute **nothing** (no app-uid downgrade) | reuses the same approval via a single-use ticket — no second dialog |
| 不允许 | refused with a settings hint, no `su`, no dialog | not started |

The approval is a `RootExecGrant`, never a boolean: it is bound to
SHA-256(command, interactive, sessionId, required), to the Root authorization
epoch (a revoke/downgrade bumps it) and to a 120 s TTL, and `RootExecPermission`
consumes it exactly once. A cached "approved" flag can therefore not authorise
a different command, a warm shell's next stdin line, or a second terminal.

Consequences that are enforced in code:

* A successful `su` probe only records "Root is usable" for the status line. It
  never promotes 每次询问 to 完全允许; old builds that auto-promoted are migrated
  once to 每次询问.
* Automatic paths (startup, autostart, liveness probes, settings status) never
  run `su` under 每次询问; they report the settings hint.
* `ExecutionCoordinator` forces the FRESH_PROCESS strategy for chroot even when
  the debug/rollback setting says WARM_SHELL: a long-lived root `/bin/sh` would
  let later commands bypass the per-command confirmation. Only a real terminal
  keeps a session-scoped process, authorised once per session.
* Downgrading/revoking stops controllable sandbox activity and clears the
  in-memory helper grant; a running interactive terminal must be stopped by the
  user (the UI says so).

### Helper capability and token

The helper accepts uid-0 frames only when it was started with `--root-exec`,
and only from a frame that echoes the 32-byte token the helper itself generated
and printed on its handshake line (`MINIS-CHROOT-READY <socket> <pid> <token>`).
The token is kept in app memory; it is never in the helper argv (root can read
root argv), never logged (logs redact it) and stripped from the environment the
client forwards to the guest command. Without `--root-exec` (level 不允许) the
helper can never serve root, and an upgrade relaunches it with a fresh token.

The client option is `--root-user`; `--root DIR` remains the rootfs option.

### Explicit CLI

`android-chroot-cli status` / `--help` execute nothing. `exec` obeys the same
tri-state: 完全允许 runs without a dialog, 每次询问 shows ONE dialog for that
exact command, 不允许 refuses. The single answer is turned into a single-use
grant for the spawn and a single-use ticket for the helper launch, so one
confirmation never produces two prompts. The command string is never part of
the privileged launcher argv; it travels in a length-prefixed frame over the
socket.

### Operit reference and intentional differences

Reference: [Operit](https://github.com/AAswordman/Operit), whose `terminal`
submodule points at [OperitTerminalCore](https://github.com/AAswordman/OperitTerminalCore).
Its `TerminalManager.generateStartScript` selects PRoot/chroot from the persisted
`chroot_enabled` setting and starts chroot through `su`; its terminal settings
show the mount/Root requirements. This is a backend-setting design, not a
dedicated model-visible chroot approval API.

Minis keeps its own helper and narrow host-mount allowlist rather than copying
Operit's broad host mounts. The uid-0 execution it does add is gated by the
unified tri-state on every spawn, not only by the CLI. `status`
reports prerequisites and recorded grants, not a fabricated liveness result.
The model prompt advertises this interface rather than insisting chroot is
already active or inferring host Root from a guest `id` result.

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

### Client refresh (was install-once)

`installOffloadClient` used to return immediately when the target existed and
was executable, so the first client ever written into a rootfs was kept
forever. When chroot commands began running as **real uid 0**, that stale
client still connected to the offload socket as uid 0; `NativeOffloadServer`
refuses any peer that is not this app's uid, and it used to close the socket
without a reply — which the client reports as
`unavailable - malformed reply from the Minis offload service` (exit 69).
That hit `android-chroot-cli status` and `exec` alike, for every
Android/minis tool inside a chroot shell, and survived app updates.

Now the copy is content-compared: the asset is streamed once to
`.minis-offload-client.tmp` while being CRC32-hashed, the installed file and a
`/.minis-offload-client.stamp` are compared, and the rename happens only when
they differ (`OffloadClientInstall.needsRefresh`, unit-tested). It runs at
rootfs boot, before the helper is (re)started, and before a one-shot
`android-chroot-cli exec`, so an updated APK repairs an existing rootfs.

### Error frames instead of silent closes

The wire format now lives in `OffloadWire.kt` (pure, JVM-tested) and every
connection is answered:

| case | exit | body |
| --- | --- | --- |
| refused peer uid (not this app's uid) | 77 `EX_NOPERM` | names the peer uid, the expected uid and the stale-rootfs-client cause |
| unparseable / truncated frame | 65 `EX_DATAERR` | names the parse reason; no handler ran |
| no handler registered | 127 | `no handler for '<name>'` |
| over 128 calls in flight | 75 `EX_TEMPFAIL` | busy |

A reply always carries a guest path; an empty path with a non-zero code means
“refused before a body could be written” and the client reports that instead
of calling it a malformed reply. `scripts/test_offload_client.py` compiles
`minis_offload_client.c` and drives the real binary through all of these cases
(host protocol test — not a device, root or chroot test).

## 6. Package management (real root inside the guest)

Chroot commands run with **real uid 0 inside the guest** when the unified Root
tri-state allows it (完全允许, or one approved command under 每次询问). That is
the point of this backend: `apt-get install`, `dpkg -i`, `adduser` and anything
else that checks `geteuid() == 0` work, and `/var/lib/dpkg` can be written.

What still bounds it:

* the child lives in a **private mount namespace** — mounts it creates are not
  visible to Android or to later commands, and host paths outside the allowlist
  are never mounted;
* `setgroups(0)` and `NoNewPrivs` are set before `exec`, so a setuid binary in
  the guest cannot gain host credentials it did not already have;
* the helper is only reachable through an abstract socket whose peer must be
  this app's uid, and it only accepts a uid-0 frame when it was started with
  `--root-exec` **and** the frame echoes its per-helper token;
* at 不允许 nothing runs at all (no app-uid fallback), and at 每次询问 a refused
  or timed-out confirmation runs nothing.

### Shared file handoff in 1.3

Files created by an approved root command keep uid 0. Before binding App-managed
shared roots, the helper installs a POSIX default ACL naming the actual App UID
with `rwx`. The primary GID comes from `Os.getgid()`, not an assumed UID/GID
equality. This applies only to the four session roots (`workspace`, `attachments`,
`offloads`, `browser`), four global roots (`memory`, `skills`, `shared`,
`mcp-servers`), and the fixed App-created `workspace/tmp`. It never changes
`/opt`, package files, user mounts, or the rest of the guest tree. Read-only
mounts are excluded. Each preparation touches those roots only; it never scans
or recursively changes their contents. PRoot's execution identity is unchanged.

Ordinary `mkdir` (creation mode 0777) and file creation (0666) inherit the App
entry, including through nested directories. Under an inherited ACL Linux uses
the requested creation mode rather than umask to constrain the ACL mask, so
these normally show 0775 / 0664 while the group-object entry keeps its previous
permissions. Root remains root for `apt` / `dpkg`. Explicit modes such as
`mkdir -m 700`, `open(..., 0600)`, or later `chmod` still limit the App's access;
Minis does not undo them. Moving in an existing inode does not make it inherit
an ACL. Existing file contents, ownership and permissions are never repaired
automatically. An existing different default ACL is preserved and reported.

A pre-existing root-owned `workspace/tmp` can therefore remain unwritable to the
App. The helper reports that exact path and preserves it; a newly created task
directory directly under the managed workspace inherits sharing. Changing an
old tree's ownership or permissions is a separate, deliberate user operation.
Filesystems that reject POSIX ACL xattrs report the OS error and continue with
their existing permissions. SELinux denials are not fixed by this DAC policy.
The App file tools stop on the first parent creation error and include its path
and exception rather than attempting a write that obscures the cause as ENOENT.

Validation uses isolated ordinary filesystem fixtures, including distinct
numeric root/App identities on cloud Linux, nested creation, existing files,
explicit modes, and parallel task directories. It is not Android chroot or
SELinux runtime validation. No Root/Shizuku operation on the user's device is
part of this build. Roll back the code with a normal Git revert; existing
inherited ACLs persist on files, so reverting code alone does not remove those
metadata. Removing them is a separate scoped filesystem operation, not a
recursive automatic cleanup or a data migration.

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
6. `chroot(rootfs)`, `chdir(cwd)`, `umask(022)`, `setgroups(0)`. Then
   `setgid/setuid(0)` for the frames the Kotlin tri-state authorised; the frame
   carries the helper's root token. (The protocol still understands an
   app-uid frame for wire compatibility, but **no current entry point requests
   one**: every chroot spawn is real uid 0 and a refusal runs nothing rather
   than downgrading.) Then `setsid()`, `execvpe`. `NoNewPrivs` is set.

The helper itself performs no `setenforce`, no SELinux policy change, no
remount of a real filesystem, no change to init/partitions/boot, and mounts
neither `/`, `/data` nor `/system`. The **user's uid-0 command is not sandboxed
by this list** and may still modify container files or shared mounts. Teardown
is namespace death; the helper’s exit, the idle
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

## 9b. Ubuntu rootfs: no busybox, and the first-run bootstrap

The rootfs is shared with the Ubuntu 24.04 port, which ships no busybox. The
PRoot wrappers were rewritten accordingly:

* `top` calls `ps` from `PATH` (procps on Ubuntu, busybox applet on the old
  Alpine image) instead of `/bin/busybox ps`.
* The read-only-mount write guards exec `/usr/bin/<name>` or `/bin/<name>`
  first (absolute, so they cannot re-enter the wrapper) and fall back to
  `/bin/busybox <name>` only when busybox exists; otherwise they print
  `not found in /usr/bin or /bin` and exit 127.

`RootfsManager` performs the Ubuntu bootstrap from the first INTERACTIVE
shell, so an agent's non-interactive command can run before python/git exist.
`SandboxRouter` therefore attaches a one-time, user-visible hint to a
non-interactive PRoot spawn when `isBootstrapComplete` is false:

* not failed → “first-time initialisation is not finished; open a terminal and
  run `minis-bootstrap` once”;
* failed → the same line framed as a retry instruction.

It deliberately does not execute `minis-bootstrap` itself: that is an apt run
that can exceed the tool-call timeout, and retrying it per command is exactly
the loop the requirement forbids. The hint is emitted once per shell instance,
prepended to the command's output, never as a silent success.

## 10. Security review notes (post-review fixes)

Applied after the main agent's review, no vulnerability reproduction performed:

1. **SO_PEERCRED** on every accepted connection: peer uid must equal the
   `--uid` the helper was started with. Abstract sockets have no filesystem
   permissions, so this is the only thing that keeps other apps out.
2. `argv`/`envp` are allocated with `count + 1` and a NULL terminator; every
   allocation and read failure frees and aborts the request. Embedded NUL in a
   frame string is refused.
3. SIGCHLD stays at its default — `SIG_IGN` takes `waitpid()` return `ECHILD`
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
* The integrator independently cross-compiled the Android PIE launcher with NDK r28 and verified all PT_LOAD segments for 16 KiB compatibility plus the exact Android linker path. It was not executed.
* Lifecycle and mount handling received static code review only. No security-trigger or exploitability tests were run; associated synthetic security fixtures are not in the final delivery.
* Seven ordinary configuration/Root-disabled/Root-denied tests ran successfully outside Gradle (`UbuntuNormalCompatibilityTest`, `NativeRootPolicyTest`). Cloud CI is the authoritative whole-module compilation and APK check; this document does not claim it succeeded.

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
nning), 98
(rejected request), 126 (setup/drop failed), 127 (exec failed) — never as a
silent success.
