#!/usr/bin/env python3
"""Static and isolated tests for scripts/build_ubuntu_toolset.sh.

No sudo, chroot, mount, apt, network or guest binary is executed here. The
privileged phase is only reachable after the GitHub Hosted Linux ARM64 gate;
these tests source the script in library mode and exercise the pure host,
dpkg-status and manifest helpers with synthetic data.

Run:  python3 scripts/tests/test_build_ubuntu_toolset.py -v
"""

import json
import os
import re
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SCRIPT = REPO / "scripts" / "build_ubuntu_toolset.sh"
PACKAGES = REPO / "scripts" / "ubuntu-toolset-packages.txt"
KOTLIN = (REPO / "src/android/app/src/main/java/com/openminis/app/sandbox/"
          "SandboxDistribution.kt")
BASE_SHA = "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2"
SCHEMA_KEYS = {"schema", "distro", "arch", "official_base_sha256", "packages", "commands"}


def read_packages():
    return [line.strip() for line in PACKAGES.read_text().splitlines()
            if line.strip() and not line.lstrip().startswith("#")]


def run_lib(body, *args, env_extra=None):
    env = os.environ.copy()
    env["UBUNTU_TOOLSET_LIB_ONLY"] = "1"
    if env_extra:
        env.update(env_extra)
    return subprocess.run(
        ["bash", "-c", 'source "$1"; shift; ' + body, "bash", str(SCRIPT), *args],
        capture_output=True, text=True, env=env, timeout=60,
    )


def run_script(args, env_extra=None, drop=()):
    env = os.environ.copy()
    for key in drop:
        env.pop(key, None)
    if env_extra:
        env.update(env_extra)
    return subprocess.run(["bash", str(SCRIPT), *args],
                          capture_output=True, text=True, env=env, timeout=60)


class StaticContractTests(unittest.TestCase):
    def test_bash_syntax(self):
        r = subprocess.run(["bash", "-n", str(SCRIPT)], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stderr)

    def test_package_list_matches_sandbox_bootstrap_packages(self):
        text = KOTLIN.read_text()
        match = re.search(r"val BOOTSTRAP_PACKAGES: List<String> = listOf\((.*?)\n\s*\)",
                          text, re.S)
        self.assertIsNotNone(match, "BOOTSTRAP_PACKAGES not found")
        kotlin = re.findall(r'"([a-z0-9+.-]+)"', match.group(1))
        self.assertEqual(len(kotlin), 16)
        runtime = re.search(r"val MCP_RUNTIME_PACKAGES: List<String> = listOf\((.*?)\n\s*\)", text, re.S)
        self.assertIsNotNone(runtime)
        mcp = re.findall(r'"([a-z0-9+.-]+)"', runtime.group(1))
        self.assertEqual(mcp, ['python3-httpx', 'python3-httpcore', 'python3-socksio'])
        self.assertEqual(kotlin + mcp, read_packages())
        self.assertEqual(len(set(kotlin + mcp)), 19)

    def test_package_list_is_one_flat_file(self):
        raw = PACKAGES.read_text()
        self.assertNotIn("ubuntu-toolset-packages.d", raw)
        self.assertEqual(len(read_packages()), 19)

    def test_base_pin_matches_prepare_script(self):
        build = SCRIPT.read_text()
        prepare = (REPO / "scripts" / "prepare_android_ubuntu.sh").read_text()
        self.assertIn(f'BASE_SHA256="{BASE_SHA}"', build)
        m = re.search(r'24\.04\.5\)\s+echo\s+"([0-9a-f]{64})"', prepare)
        self.assertIsNotNone(m)
        self.assertEqual(m.group(1), BASE_SHA)

    def test_manifest_schema_is_fixed(self):
        text = SCRIPT.read_text()
        for needle in ('"schema": 1', '"distro": "ubuntu24.04"', '"arch": "arm64"',
                       '"official_base_sha256"', '"packages": {', '"commands": {'):
            with self.subTest(needle=needle):
                self.assertIn(needle, text)
        # No unrequested fields in the emitted object.
        self.assertNotIn('"generated_utc"', text)
        self.assertNotIn('"distro_version"', text)

    def test_required_manifest_commands_are_probed(self):
        text = SCRIPT.read_text()
        for key in ("python3", "git", "curl", "wget", "ssh", "rg", "jq", "pip", "venv"):
            with self.subTest(key=key):
                self.assertRegex(text, rf"'{re.escape(key)}\|")
        self.assertIn("python3 -m venv /tmp/minis-venv-check", text)
        self.assertIn("python3 -m pip --version", text)
        self.assertIn("'mcp-http|/usr/bin/python3 -I -c", text)
        self.assertIn("import httpx, httpcore, socksio", text)

    def test_output_and_archive_contract(self):
        text = SCRIPT.read_text()
        self.assertIn("deps/build/ubuntu-toolset", text)
        self.assertIn("ubuntu-base-arm64.tar.gz", text)
        self.assertIn("$base.sha256", text)
        self.assertIn("gzip -t", text)
        self.assertIn("sha256sum -c", text)
        self.assertIn("toolset-manifest.json", text)
        self.assertIn("var/lib/minis/bootstrap.done", text)
        self.assertIn("CI build artifact", text)
        self.assertIn("do not commit", text)

    def test_apt_hardening(self):
        text = SCRIPT.read_text()
        self.assertIn("Dpkg::Use-Pty=0", text)
        self.assertIn("</dev/null", text)
        self.assertIn("policy-rc.d", text)
        self.assertIn("exit 101", text)
        self.assertIn("Acquire::AllowInsecureRepositories=false", text)
        self.assertIn("Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg", text)
        for forbidden in (
            "trusted=yes", "--allow-unauthenticated", "--force-yes", "apt-key",
            "--no-check-gpg", "Verify-Peer \"false\"", "Verify-Host \"false\"",
            "Check-Valid-Until \"false\"", "Acquire::https::Verify-Peer",
            "AllowInsecureRepositories=true", "insecure=yes",
        ):
            with self.subTest(forbidden=forbidden):
                self.assertNotIn(forbidden, text)

    def test_http_bootstrap_then_https_after_ca(self):
        text = SCRIPT.read_text()
        ca = text.index("install -y --no-install-recommends ca-certificates")
        https = text.index("set_sources_scheme https")
        self.assertLess(ca, https, "https may only be used after ca-certificates exists")
        self.assertIn("set_sources_scheme http", text)
        self.assertIn("assert_https_sources", text)
        self.assertIn("ports|archive|security", text)

    def test_host_gate_precedes_sudo(self):
        text = SCRIPT.read_text()
        main = text.index("main() {")
        self.assertLess(text.index("host_guard || die", main),
                        text.index("    ensure_root", main))
        self.assertIn('GITHUB_ACTIONS:-', text)
        self.assertIn('RUNNER_ARCH:-', text)
        self.assertIn("probe_android", text)
        self.assertIn("probe_proot", text)
        self.assertIn("unshare --mount --propagation private", text)

    def test_cleanup_is_scoped_to_temp_tree_and_mounts(self):
        text = SCRIPT.read_text()
        self.assertIn('"${TMPDIR:-/tmp}"/ubuntu-preinstall-agent.*', text)
        self.assertIn('case "$target" in', text)
        self.assertIn('"$ROOT"/*', text)
        self.assertIn("refusing to unmount path outside the temp root", text)


class HostGateTests(unittest.TestCase):
    ARGS = ("Linux", "aarch64", "ubuntu", "24.04", "0", "0", "true", "Linux", "ARM64")

    def check(self, *args):
        return run_lib(
            'host_guard_check "$1" "$2" "$3" "$4" "$5" "$6" "$7" "$8" "$9"',
            *args)

    def test_accepts_github_hosted_linux_arm64(self):
        r = self.check(*self.ARGS)
        self.assertEqual(r.returncode, 0, r.stderr)

    def test_rejects_non_linux(self):
        r = self.check("Darwin", *self.ARGS[1:])
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("not Linux", r.stderr)

    def test_rejects_x86_and_32bit_arm(self):
        for arch in ("x86_64", "armv7l", "i686"):
            with self.subTest(arch=arch):
                r = self.check("Linux", arch, *self.ARGS[2:])
                self.assertNotEqual(r.returncode, 0)
                self.assertIn("not aarch64", r.stderr)

    def test_rejects_non_ubuntu_or_wrong_release(self):
        for os_id, ver in (("debian", "12"), ("ubuntu", "22.04"), ("alpine", "3.21")):
            with self.subTest(os_id=os_id, ver=ver):
                r = self.check("Linux", "aarch64", os_id, ver, *self.ARGS[4:])
                self.assertNotEqual(r.returncode, 0)
                self.assertIn("Ubuntu 24.04", r.stderr)

    def test_rejects_android_and_proot(self):
        r = self.check("Linux", "aarch64", "ubuntu", "24.04", "1", "0", *self.ARGS[6:])
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("Android", r.stderr)
        r = self.check("Linux", "aarch64", "ubuntu", "24.04", "0", "1", *self.ARGS[6:])
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("PRoot", r.stderr)

    def test_rejects_missing_or_wrong_ci_identity(self):
        for gh, ros, rarch in (("", "Linux", "ARM64"), ("true", "macOS", "ARM64"),
                               ("true", "Linux", "X64"), ("false", "Linux", "ARM64")):
            with self.subTest(gh=gh, ros=ros, rarch=rarch):
                r = self.check("Linux", "aarch64", "ubuntu", "24.04", "0", "0",
                               gh, ros, rarch)
                self.assertNotEqual(r.returncode, 0)
                self.assertIn("GitHub Hosted Linux ARM64", r.stderr)

    def test_check_host_mode_refuses_this_developer_box(self):
        # Whatever this machine is, it is not an explicitly identified GitHub
        # Hosted Linux ARM64 runner: no sudo/network path may be reached.
        r = run_script(["--check-host"],
                       drop=("GITHUB_ACTIONS", "RUNNER_OS", "RUNNER_ARCH"))
        self.assertNotEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertIn("host gate", r.stderr)
        self.assertNotIn("elevating with sudo", r.stderr)

    def test_help_and_unknown_argument(self):
        r = run_script(["--help"])
        self.assertEqual(r.returncode, 0)
        self.assertIn("CI-only builder", r.stdout)
        r = run_script(["--bogus"])
        self.assertEqual(r.returncode, 2)
        self.assertIn("unknown argument", r.stderr)


class PackageStateTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="minis-toolset-test-")
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.status = self.root / "status"
        self.list = self.root / "packages.txt"

    def write_status(self, packages, status="install ok installed"):
        chunks = []
        for name, version in packages:
            chunks.append(
                f"Package: {name}\nStatus: {status}\nPriority: optional\n"
                f"Architecture: arm64\nVersion: {version}\n")
        self.status.write_text("\n".join(chunks) + "\n")

    def call(self, status_file):
        self.list.write_text("bash\ngit\n")
        return run_lib('verify_package_state "$1" "$2"', str(status_file), str(self.list))

    def test_synthetic_installed_state_is_accepted(self):
        self.write_status([("bash", "5.2.21-2ubuntu4"), ("git", "1:2.43.0-1ubuntu7.3")])
        r = self.call(self.status)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("bash\t5.2.21-2ubuntu4", r.stdout)
        self.assertIn("git\t1:2.43.0-1ubuntu7.3", r.stdout)

    def test_deinstalled_package_is_rejected(self):
        self.write_status([("bash", "5.2.21-2ubuntu4"), ("git", "1:2.43.0-1ubuntu7.3")])
        text = self.status.read_text().replace(
            "Status: install ok installed", "Status: deinstall ok config-files", 1)
        self.status.write_text(text)
        r = self.call(self.status)
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("not installed", r.stderr)

    def test_missing_package_is_rejected(self):
        self.write_status([("bash", "5.2.21-2ubuntu4")])
        r = self.call(self.status)
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("git is not installed", r.stderr)

    def test_missing_version_is_rejected(self):
        self.status.write_text(
            "Package: bash\nStatus: install ok installed\nArchitecture: arm64\n\n"
            "Package: git\nStatus: install ok installed\nArchitecture: arm64\n")
        r = self.call(self.status)
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("no dpkg Version", r.stderr)


class ManifestTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="minis-manifest-test-")
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name) / "rootfs"
        (self.root / "var/lib/dpkg").mkdir(parents=True)
        self.status = self.root / "var/lib/dpkg/status"
        names = read_packages()
        self.status.write_text("\n".join(
            f"Package: {name}\nStatus: install ok installed\nArchitecture: arm64\n"
            f"Version: 1.0-{i}\n" for i, name in enumerate(names, 1)) + "\n")
        self.commands = Path(self.tmp.name) / "commands.tsv"
        required = ["python3", "git", "curl", "wget", "ssh", "rg", "jq", "pip",
                    "venv", "bash", "less", "ps", "ip", "unzip", "xz", "ca-certificates"]
        self.commands.write_text("\n".join(
            f"{key}\t{key} --version\t0\t{key} version 1.0" for key in required) + "\n")
        self.out = self.root / "usr/local/share/minis/toolset-manifest.json"

    def render(self):
        return run_lib(
            'PACKAGE_LIST="$1"; render_manifest "$2" "$3" "$4" "$5"',
            str(PACKAGES), str(self.root), BASE_SHA, str(self.commands), str(self.out))

    def test_manifest_is_exact_schema_with_versions_and_evidence(self):
        r = self.render()
        self.assertEqual(r.returncode, 0, r.stderr)
        manifest = json.loads(self.out.read_text())
        self.assertEqual(set(manifest), SCHEMA_KEYS)
        self.assertIs(type(manifest["schema"]), int)
        self.assertEqual(manifest["schema"], 1)
        self.assertEqual(manifest["distro"], "ubuntu24.04")
        self.assertEqual(manifest["arch"], "arm64")
        self.assertEqual(manifest["official_base_sha256"], BASE_SHA)
        self.assertEqual(set(manifest["packages"]), set(read_packages()))
        self.assertEqual(manifest["packages"]["bash"], "1.0-1")
        for key in ("python3", "git", "curl", "wget", "ssh", "rg", "jq", "pip", "venv"):
            self.assertTrue(manifest["commands"].get(key), key)

    def test_manifest_escapes_quotes_and_backslashes(self):
        self.commands.write_text('python3\tpython3 --version\t0\ta"b\\c\n')
        r = self.render()
        self.assertEqual(r.returncode, 0, r.stderr)
        manifest = json.loads(self.out.read_text())
        self.assertEqual(manifest["commands"]["python3"], 'a"b\\c')

    def test_empty_command_evidence_fails(self):
        self.commands.write_text("python3\tpython3 --version\t0\t\n")
        r = self.render()
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("empty command output", r.stderr)


if __name__ == "__main__":
    unittest.main()
