#!/usr/bin/env python3
"""Behavioural tests for the Ubuntu guest toolset bootstrap (stdlib only).

`SandboxDistribution.bootstrapScript()` / `bootstrapProfileScript()` are shell
programs stored as Kotlin string literals, so they can be executed here exactly
as shipped: this file extracts the literals from the Kotlin source, substitutes
the guest paths with a temporary root, and runs them against **stub** `apt-get`
/ `dpkg-query` (and `id` for the unprivileged case). No real apt/dpkg runs, no
network, no guest is touched.

Covered:
  * unattended apt: `DPkg::Use-Pty=false`, stdin `/dev/null`,
    `DEBIAN_FRONTEND=noninteractive`, no env/proxy values in the log
  * success only after `dpkg-query` verifies every package (exit 0 from apt is
    not enough), stale `bootstrap.done` re-verified instead of trusted
  * real apt failure keeps its exit status, writes no `bootstrap.done`, and
    prints the tail of the real log on stderr
  * non-root uid (chroot): no apt call, no `bootstrap.failed`, explicit
    "switch to PRoot" message, `bootstrap.skipped` records uid/backend
  * profile hook: reports missing packages but never runs apt/bootstrap

Run:
  python3 scripts/tests/test_ubuntu_bootstrap.py -v
"""

import re
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
KT_PATH = (
    REPO
    / "src/android/app/src/main/java/com/openminis/app/sandbox/SandboxDistribution.kt"
)
KT = KT_PATH.read_text(encoding="utf-8")

CONSTS = dict(re.findall(r'const val (\w+) = "([^"]*)"', KT))

_ESCAPES = {
    "n": "\n",
    "t": "\t",
    "r": "\r",
    "\\": "\\",
    '"': '"',
    "'": "'",
    "$": "$",
}


def _parse_literal(text, i):
    """Parse one Kotlin string/char literal starting at text[i]; return (value, next)."""
    quote = text[i]
    assert quote in "\"'", text[i : i + 20]
    i += 1
    out = []
    while i < len(text):
        c = text[i]
        if c == "\\":
            out.append(_ESCAPES.get(text[i + 1], text[i + 1]))
            i += 2
            continue
        if c == quote:
            return "".join(out), i + 1
        out.append(c)
        i += 1
    raise ValueError("unterminated Kotlin literal")


def extract_build_string(name):
    """Reconstruct the string returned by `fun <name>(): String = buildString { … }`."""
    marker = "fun %s(): String = buildString {" % name
    start = KT.index(marker) + len(marker)
    parts = []
    i = start
    while True:
        close = KT.find("\n    }\n", i)
        call = KT.find("append(", i)
        if call == -1 or (close != -1 and close < call):
            break
        p = call + len("append(")
        while KT[p].isspace():
            p += 1
        if KT[p] in "\"'":
            value, p = _parse_literal(KT, p)
        else:
            m = re.match(r"[A-Za-z_][A-Za-z0-9_]*", KT[p:])
            if not m:
                raise AssertionError("unsupported append argument near %r" % KT[p : p + 40])
            ident = m.group(0)
            value = CONSTS[ident]
            p += len(ident)
        while KT[p].isspace():
            p += 1
        assert KT[p] == ")", KT[p : p + 40]
        parts.append(value)
        i = p + 1
    if not parts:
        raise AssertionError("no append() calls extracted for %s" % name)
    return "".join(parts)


def extract_packages():
    m = re.search(
        r"val BOOTSTRAP_PACKAGES: List<String> = listOf\((.*?)\n    \)",
        KT,
        re.S,
    )
    assert m, "BOOTSTRAP_PACKAGES not found"
    return re.findall(r'"([^"]+)"', m.group(1))


SCRIPT_RAW = extract_build_string("bootstrapScript")
PROFILE_RAW = extract_build_string("bootstrapProfileScript")
PACKAGES = extract_packages()
APT_CONF_RAW = extract_build_string("aptProotConf")

APT_CALLS = (" update", " install ")


def guestize(script, root):
    """Point the guest's absolute paths at a temporary root (longest first)."""
    # One replacement per prefix: a second overlapping mapping would rewrite
    # the already-rewritten host path again.
    mapping = [
        ("/var/log", root / "var/log"),
        ("/var/lib/minis", root / "var/lib/minis"),
        ("/etc/minis", root / "etc/minis"),
    ]
    for guest, host in mapping:
        script = script.replace(guest, str(host))
    return script


APT_STUB = """#!/bin/sh
root="$MINIS_TEST_ROOT"
# Version probes are not package-manager jobs and do not consume stdin.
case " $* " in *" --version "*) printf 'apt synthetic-test-version\\n'; exit 0;; esac
printf '%s\\n' "$*" >> "$root/apt_calls"
readlink /proc/self/fd/0 >> "$root/apt_stdin"
printf '%s\\n' "${DEBIAN_FRONTEND-}" >> "$root/apt_frontend"
case " $* " in
  *" install "*)
    if [ -f "$root/install_effect" ]; then cp "$root/install_effect" "$root/installed"; fi
    if [ -f "$root/install_stderr" ]; then cat "$root/install_stderr" >&2; fi
    rc=0
    if [ -f "$root/install_rc" ]; then rc=$(cat "$root/install_rc"); fi
    exit "$rc"
    ;;
esac
exit 0
"""

DPKG_STUB = """#!/bin/sh
pkg=
for arg in "$@"; do pkg="$arg"; done
root="$MINIS_TEST_ROOT"
if grep -qxF "$pkg" "$root/installed" 2>/dev/null; then
  printf 'install ok installed'
  exit 0
fi
printf 'dpkg-query: no packages found matching %s\\n' "$pkg" >&2
exit 1
"""

ID_STUB = """#!/bin/sh
printf '%s\\n' "${MINIS_TEST_UID:-10123}"
"""

TRIPWIRE_STUB = """#!/bin/sh
printf '%s\\n' "$*" >> "$MINIS_TEST_ROOT/tripwire.log"
exit 0
"""


class Guest:
    """A throwaway guest root with stub apt/dpkg on PATH."""

    def __init__(self, installed=(), uid=None):
        self._tmp = tempfile.TemporaryDirectory(prefix="minis-bootstrap-test-")
        self.root = Path(self._tmp.name)
        for rel in ("etc/minis", "var/lib/minis", "var/log", "bin", "tripwire-bin", "home"):
            (self.root / rel).mkdir(parents=True, exist_ok=True)
        (self.root / "etc/minis/bootstrap-packages").write_text(
            "\n".join(PACKAGES) + "\n", encoding="utf-8"
        )
        self.set_installed(installed)
        self._write_stub(self.root / "bin/apt-get", APT_STUB)
        self._write_stub(self.root / "bin/dpkg-query", DPKG_STUB)
        # Always simulate the guest uid, never depend on the host runner uid.
        # This affects only the isolated test PATH, not real permissions.
        self.uid = 0 if uid is None else uid
        self._write_stub(self.root / "bin/id", ID_STUB)
        self.script = self.root / "bootstrap.sh"
        self.script.write_text(guestize(SCRIPT_RAW, self.root), encoding="utf-8")
        self.profile = self.root / "profile.sh"
        self.profile.write_text(guestize(PROFILE_RAW, self.root), encoding="utf-8")

    @staticmethod
    def _write_stub(path, body):
        path.write_text(body, encoding="utf-8")
        path.chmod(0o755)

    def set_installed(self, names):
        (self.root / "installed").write_text("\n".join(names) + "\n", encoding="utf-8")

    def installed(self):
        text = (self.root / "installed").read_text(encoding="utf-8")
        return {line.strip() for line in text.splitlines() if line.strip()}

    def file(self, rel):
        return self.root / rel

    def marker(self, name):
        return self.file("var/lib/minis/" + name)

    def log(self):
        path = self.file("var/log/minis-bootstrap.log")
        return path.read_text(encoding="utf-8") if path.exists() else ""

    def calls(self):
        path = self.file("apt_calls")
        if not path.exists():
            return []
        return [l for l in path.read_text(encoding="utf-8").splitlines() if l]

    def apt_jobs(self):
        return [c for c in self.calls() if any(tok in c for tok in APT_CALLS)]

    def stdin_targets(self):
        path = self.file("apt_stdin")
        if not path.exists():
            return []
        return [l for l in path.read_text(encoding="utf-8").splitlines() if l]

    def env(self, backend="PROOT", uid=None):
        env = {
            "PATH": "%s:/usr/bin:/bin:/usr/sbin:/sbin" % (self.root / "bin"),
            "HOME": str(self.root / "home"),
            "MINIS_TEST_ROOT": str(self.root),
            "MINIS_SANDBOX_BACKEND": backend,
            # Deliberately secret-looking: must never reach the log.
            "MINIS_MCP_PROXY": "http://mcp:sup3rsecret@127.0.0.1:9/proxy",
            "HTTPS_PROXY": "http://user:pr0xypass@127.0.0.1:9",
        }
        env["MINIS_TEST_UID"] = str(self.uid if uid is None else uid)
        return env

    def run(self, *args, backend="PROOT", uid=None):
        return subprocess.run(
            ["sh", str(self.script)] + list(args),
            env=self.env(backend=backend, uid=uid),
            capture_output=True,
            text=True,
            timeout=60,
        )

    def run_profile(self):
        """Source the hook in an interactive shell, with install tripwires on PATH."""
        env = self.env()
        trap = self.root / "tripwire-bin"
        env["PATH"] = "%s:%s:/usr/bin:/bin" % (trap, self.root / "bin")
        self._write_stub(trap / "apt-get", TRIPWIRE_STUB)
        self._write_stub(trap / "minis-bootstrap", TRIPWIRE_STUB)
        self._write_stub(trap / "apt", TRIPWIRE_STUB)
        return subprocess.run(
            ["sh", "-i", "-c", ". '%s'" % self.profile],
            env=env,
            capture_output=True,
            text=True,
            timeout=60,
        )

    def tripwire_fired(self):
        path = self.root / "tripwire.log"
        return path.exists() and path.read_text(encoding="utf-8").strip() != ""

    def cleanup(self):
        self._tmp.cleanup()


def profile_stderr(result):
    """Drop the shell's own 'no tty' notice from `sh -i -c`."""
    lines = [
        line
        for line in result.stderr.splitlines()
        if "can't access tty" not in line and line.strip()
    ]
    return "\n".join(lines)


class StaticContract(unittest.TestCase):
    def test_script_has_unattended_apt_switches(self):
        self.assertTrue(SCRIPT_RAW.startswith("#!/bin/sh\n"))
        self.assertEqual(SCRIPT_RAW.count("-o DPkg::Use-Pty=false"), 2, SCRIPT_RAW)
        self.assertEqual(SCRIPT_RAW.count("</dev/null"), 2, SCRIPT_RAW)
        self.assertIn("DEBIAN_FRONTEND=noninteractive", SCRIPT_RAW)
        self.assertIn("install --no-install-recommends", SCRIPT_RAW)
        # No `cat` word-splitting of the package list any more.
        self.assertNotIn('$(cat "$list")', SCRIPT_RAW)

    def test_script_guards_on_real_uid_and_skips_without_failure(self):
        self.assertIn('guest_uid=$(id -u)', SCRIPT_RAW)
        self.assertIn('if [ "$guest_uid" != 0 ]; then', SCRIPT_RAW)
        self.assertIn("exit 3", SCRIPT_RAW)
        self.assertIn(CONSTS["BOOTSTRAP_SKIPPED_MARKER"], SCRIPT_RAW)
        # The guard must not be a pure backend-variable check.
        self.assertNotIn('if [ "$backend" != "PROOT" ]', SCRIPT_RAW)
        # failed marker is only written by fail()/at the end of a real failure
        self.assertIn('date -u +%Y-%m-%dT%H:%M:%SZ > "$failed_marker"', SCRIPT_RAW)

    def test_script_verifies_dpkg_state_before_done(self):
        self.assertIn("missing_packages()", SCRIPT_RAW)
        self.assertIn("dpkg-query -W -f='${Status}'", SCRIPT_RAW)
        self.assertIn("install ok installed", SCRIPT_RAW)
        done_write = SCRIPT_RAW.index('> "$done_marker"')
        verify = SCRIPT_RAW.index('[ -z "$missing" ] || fail 1 "package verification failed')
        self.assertLess(verify, done_write)

    def test_profile_only_reports_and_never_installs(self):
        self.assertNotIn("apt-get", PROFILE_RAW)
        self.assertNotIn("apt ", PROFILE_RAW)
        self.assertNotIn("/usr/local/sbin/minis-bootstrap", PROFILE_RAW)
        for line in PROFILE_RAW.splitlines():
            self.assertFalse(
                line.strip().startswith("minis-bootstrap"),
                "profile must not execute minis-bootstrap: %r" % line,
            )
        self.assertIn("dpkg-query -W -f='${Status}'", PROFILE_RAW)
        self.assertIn("guest toolset incomplete", PROFILE_RAW)
        # Status check is interactive-shell only.
        self.assertIn('case "$-" in', PROFILE_RAW)

    def test_proot_apt_conf_disables_dpkg_pty(self):
        self.assertIn('DPkg::Use-Pty "false";', APT_CONF_RAW)
        self.assertIn('APT::Sandbox::User "root";', APT_CONF_RAW)
        self.assertIn('APT::Sandbox::Seccomp "false";', APT_CONF_RAW)

    def test_package_list_is_the_shipped_toolset(self):
        self.assertEqual(len(PACKAGES), 16, PACKAGES)
        for name in ("python3", "git", "curl", "ripgrep", "ca-certificates"):
            self.assertIn(name, PACKAGES)


class BootstrapBehaviour(unittest.TestCase):
    def setUp(self):
        self.guest = None

    def tearDown(self):
        if self.guest:
            self.guest.cleanup()

    def _normal_guest(self, installed=(), uid=None):
        guest = Guest(installed=installed, uid=uid)
        guest.file("install_effect").write_text("\n".join(PACKAGES) + "\n", encoding="utf-8")
        self.guest = guest
        return guest

    def test_normal_run_installs_verifies_and_logs_diagnostics(self):
        guest = self._normal_guest()
        result = guest.run()
        self.assertEqual(result.returncode, 0, result.stderr + guest.log())
        self.assertTrue(guest.marker("bootstrap.done").exists())
        self.assertFalse(guest.marker("bootstrap.failed").exists())
        self.assertEqual(guest.installed(), set(PACKAGES))

        log = guest.log()
        self.assertIn("diag: backend=PROOT uid=0", log)
        self.assertNotIn("sup3rsecret", log)
        self.assertNotIn("pr0xypass", log)
        self.assertNotIn("MINIS_MCP_PROXY", log)

        jobs = guest.apt_jobs()
        self.assertEqual(len(jobs), 2, jobs)
        for call in jobs:
            self.assertIn("-o DPkg::Use-Pty=false", call)
        self.assertTrue(any(" install " in c and "--no-install-recommends" in c for c in jobs))
        # Only the update/install jobs consume stdin; --version is diagnostic.
        self.assertEqual(len(guest.stdin_targets()), len(jobs))
        self.assertEqual({t.strip() for t in guest.stdin_targets()}, {"/dev/null"})
        frontend = guest.file("apt_frontend").read_text(encoding="utf-8").splitlines()
        self.assertEqual(set(frontend), {"noninteractive"})
        self.assertEqual(len(frontend), len(jobs))
        self.assertIn("packages verified installed and configured", log)

    def test_apt_success_with_a_missing_package_is_a_failure(self):
        guest = Guest()
        guest.file("install_effect").write_text(
            "\n".join(PACKAGES[:-1]) + "\n", encoding="utf-8"
        )
        self.guest = guest
        result = guest.run()
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(guest.marker("bootstrap.done").exists())
        self.assertTrue(guest.marker("bootstrap.failed").exists())
        self.assertIn("package verification failed", result.stderr)
        self.assertIn(PACKAGES[-1], result.stderr)
        self.assertIn("== minis-bootstrap", result.stderr)  # log tail on terminal

    def test_real_apt_failure_keeps_exit_code_and_writes_no_done(self):
        guest = Guest()
        guest.file("install_rc").write_text("100\n", encoding="utf-8")
        guest.file("install_stderr").write_text(
            "E: Unable to fetch some archives\n", encoding="utf-8"
        )
        self.guest = guest
        result = guest.run()
        self.assertEqual(result.returncode, 100, result.stderr)
        self.assertFalse(guest.marker("bootstrap.done").exists())
        self.assertTrue(guest.marker("bootstrap.failed").exists())
        self.assertIn("FAILED (exit 100)", result.stderr)
        self.assertIn("apt-get install", result.stderr)
        self.assertIn("E: Unable to fetch some archives", result.stderr)
        self.assertFalse(guest.marker("bootstrap.skipped").exists())

    def test_pty_restore_error_is_explained_not_swallowed(self):
        guest = Guest()
        guest.file("install_effect").write_text("\n".join(PACKAGES) + "\n", encoding="utf-8")
        guest.file("install_rc").write_text("100\n", encoding="utf-8")
        guest.file("install_stderr").write_text(
            "E: Setting in Stop via TCSAFLUSH for stdin failed! - tcsetattr (5: Input/output error)\n",
            encoding="utf-8",
        )
        self.guest = guest
        result = guest.run()
        self.assertEqual(result.returncode, 100)
        self.assertFalse(guest.marker("bootstrap.done").exists())
        self.assertIn("TCSAFLUSH", result.stderr)
        self.assertIn("DPkg::Use-Pty must stay false", result.stderr)

    def test_stale_done_marker_is_reverified(self):
        guest = Guest(installed=PACKAGES[:-1])
        guest.marker("bootstrap.done").write_text("old\n", encoding="utf-8")
        guest.file("install_effect").write_text("\n".join(PACKAGES) + "\n", encoding="utf-8")
        self.guest = guest
        result = guest.run()
        self.assertEqual(result.returncode, 0, result.stderr + guest.log())
        self.assertEqual(len(guest.apt_jobs()), 2)
        self.assertIn("done marker not trusted", guest.log())
        self.assertIn("installing missing:", guest.log())
        self.assertEqual(guest.installed(), set(PACKAGES))
        self.assertTrue(guest.marker("bootstrap.done").exists())

    def test_repeat_completion_verifies_without_apt(self):
        guest = self._normal_guest()
        first = guest.run()
        self.assertEqual(first.returncode, 0, first.stderr + guest.log())
        calls_after_first = len(guest.calls())

        second = guest.run()
        self.assertEqual(second.returncode, 0, second.stderr)
        self.assertIn("already installed and verified", second.stderr)
        self.assertEqual(len(guest.calls()), calls_after_first)
        self.assertTrue(guest.marker("bootstrap.done").exists())

    def test_non_root_skips_without_apt_or_failed_marker(self):
        guest = Guest(uid=10123)
        self.guest = guest
        result = guest.run(backend="CHROOT", uid=10123)
        self.assertEqual(result.returncode, 3, result.stderr)
        self.assertEqual(guest.calls(), [])
        self.assertFalse(guest.marker("bootstrap.done").exists())
        self.assertFalse(guest.marker("bootstrap.failed").exists())
        skipped = guest.marker("bootstrap.skipped").read_text(encoding="utf-8")
        self.assertIn("uid=10123", skipped)
        self.assertIn("backend=CHROOT", skipped)
        self.assertIn("switch to the PRoot", result.stderr)
        self.assertIn("SKIPPED", result.stderr)
        self.assertIn("skipped: uid=10123 backend=CHROOT", guest.log())

    def test_preinstalled_done_on_chroot_is_success(self):
        guest = Guest(installed=PACKAGES, uid=10123)
        guest.marker("bootstrap.done").write_text("built\n", encoding="utf-8")
        self.guest = guest
        result = guest.run(backend="CHROOT", uid=10123)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("already installed and verified", result.stderr)
        self.assertEqual(guest.calls(), [])
        self.assertFalse(guest.marker("bootstrap.failed").exists())


class ProfileBehaviour(unittest.TestCase):
    def setUp(self):
        self.guest = None

    def tearDown(self):
        if self.guest:
            self.guest.cleanup()

    def test_profile_silent_when_toolset_complete(self):
        self.guest = Guest(installed=PACKAGES)
        self.guest.marker("bootstrap.done").write_text("built\n", encoding="utf-8")
        result = self.guest.run_profile()
        self.assertEqual(result.returncode, 0)
        self.assertEqual(profile_stderr(result), "")
        self.assertFalse(self.guest.tripwire_fired())

    def test_profile_reports_missing_packages_without_installing(self):
        self.guest = Guest(installed=PACKAGES[:-2])
        result = self.guest.run_profile()
        self.assertEqual(result.returncode, 0)
        err = profile_stderr(result)
        self.assertIn("guest toolset incomplete", err)
        for name in PACKAGES[-2:]:
            self.assertIn(name, err)
        self.assertIn("minis-bootstrap --force", err)
        self.assertIn("PRoot backend", err)
        self.assertFalse(self.guest.tripwire_fired())
        # The hook must not create/modify install state either.
        self.assertFalse(self.guest.marker("bootstrap.done").exists())
        self.assertFalse(self.guest.marker("bootstrap.failed").exists())

    def test_profile_done_with_stale_failure_is_silent(self):
        self.guest = Guest(installed=PACKAGES)
        self.guest.marker("bootstrap.done").write_text("built\n", encoding="utf-8")
        self.guest.marker("bootstrap.failed").write_text("old\n", encoding="utf-8")
        result = self.guest.run_profile()
        self.assertEqual(profile_stderr(result), "")
        self.assertFalse(self.guest.tripwire_fired())

    def test_profile_mentions_earlier_failure_only_when_packages_ok(self):
        self.guest = Guest(installed=PACKAGES)
        self.guest.marker("bootstrap.failed").write_text("old\n", encoding="utf-8")
        result = self.guest.run_profile()
        err = profile_stderr(result)
        self.assertIn("earlier install attempt failed", err)
        self.assertFalse(self.guest.tripwire_fired())


if __name__ == "__main__":
    unittest.main(verbosity=2)
