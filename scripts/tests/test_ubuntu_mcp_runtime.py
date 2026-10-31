"""Android Ubuntu MCP package resolution; mocked commands, no installation."""
import importlib.util
import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / 'src/android/app/src/main/assets/default_mount'
DEPS = ASSETS / 'usr/local/lib/minis-mcp-cli/utils/deps.py'


class UbuntuMcpRuntimeTest(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location('ubuntu_mcp_deps', DEPS)
        self.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.module)

    def test_node_resolution_uses_ubuntu_packages_and_verifies_path(self):
        with patch.object(self.module.shutil, 'which', side_effect=[None, '/usr/bin/npx']), \
             patch.object(self.module, '_run', return_value=0) as run, \
             patch.object(self.module, '_log'):
            self.module.ensure_command('npx')
        command = run.call_args.args[0]
        self.assertIn('apt-get -qq update &&', command)
        self.assertIn('install -y --no-install-recommends nodejs npm', command)
        self.assertNotIn('apk', command)

    def test_present_command_does_not_install(self):
        with patch.object(self.module.shutil, 'which', return_value='/usr/bin/node'), \
             patch.object(self.module, '_run') as run:
            self.module.ensure_command('node')
        run.assert_not_called()

    def test_failed_resolution_is_not_reported_as_success(self):
        with patch.object(self.module.shutil, 'which', return_value=None), \
             patch.object(self.module, '_run', return_value=100), \
             patch.object(self.module, '_log') as log:
            self.module.ensure_command('node')
        self.assertTrue(any('could not resolve' in call.args[0] for call in log.call_args_list))
        self.assertFalse(any(call.args[0].startswith('resolved ') for call in log.call_args_list))

    def test_python_resolvers_use_ubuntu_names(self):
        for name in ('python3', 'pip', 'pip3'):
            command = self.module._RESOLVERS[name][0]
            self.assertIn('python3 python3-pip', command)
            self.assertNotIn('py3-pip', command)

    def test_unknown_command_never_becomes_shell_input(self):
        with patch.object(self.module.shutil, 'which', return_value=None), \
             patch.object(self.module, '_run') as run, \
             patch.object(self.module, '_log'):
            self.module.ensure_command('unknown; echo unexpected')
        run.assert_not_called()

    def test_wrapper_uses_system_httpx_not_global_pip(self):
        text = (ASSETS / 'usr/local/bin/minis-mcp-cli').read_text()
        self.assertIn('python3-httpx', text)
        self.assertNotIn('apk add', text)
        self.assertNotIn('pip install', text)
        self.assertIn('exit 1', text)
        self.assertIn('PYTHON=/usr/bin/python3', text)
        self.assertIn('import httpx, httpcore, socksio', text)
        self.assertIn('exec "$PYTHON" -I /usr/local/lib/minis-mcp-cli/main.py', text)

    def test_mcp_packages_do_not_invalidate_existing_rootfs(self):
        sandbox = ROOT / 'src/android/app/src/main/java/com/openminis/app/sandbox'
        text = (sandbox / 'UbuntuToolsetStatus.kt').read_text()
        self.assertIn('SandboxDistribution.BOOTSTRAP_PACKAGES', text)
        self.assertNotIn('MCP_RUNTIME_PACKAGES', text)


class McpWrapperBehaviorTest(unittest.TestCase):
    """Execute the real POSIX wrapper, replacing only absolute fixture paths.

    Fake Python/apt record argv; no packages, servers or network are touched.
    Missing host log mount, active venv and broken imports are functional
    startup conditions, not real configuration or account data.
    """
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='mcp-wrapper-')
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        self.events = self.root / 'events'
        self.state = self.root / 'installed'
        self.env = os.environ.copy()
        self.env.update(PATH=str(self.bin) + os.pathsep + os.defpath,
                        EVENTS=str(self.events), STATE=str(self.state))
        self.env.pop('FAIL_APT', None)
        self.env.pop('STILL_MISSING', None)
        # This Python stub stands in for /usr/bin/python3, not PATH python3.
        import sys
        self.python = self.bin / 'system-python'
        self.python.write_text('#!' + sys.executable + '\n' + '''import json, os, sys
with open(os.environ['EVENTS'], 'a') as f:
    f.write(json.dumps(sys.argv[1:]) + '\\n')
if '-c' in sys.argv:
    sys.exit(0 if os.path.exists(os.environ['STATE']) and not os.environ.get('STILL_MISSING') else 1)
print('{"ok": true}')
''')
        self.python.chmod(0o755)
        apt = self.bin / 'apt-get'
        apt.write_text('''#!/bin/sh
printf 'apt %s\\n' "$*" >> "$EVENTS"
[ "${FAIL_APT:-0}" = 0 ] || exit 100
case "$*" in *install*) touch "$STATE";; esac
''')
        apt.chmod(0o755)
        wrong_python = self.bin / 'python3'
        wrong_python.write_text('#!/bin/sh\necho wrong-PATH-python >&2\nexit 99\n')
        wrong_python.chmod(0o755)
        self.wrapper = self.root / 'minis-mcp-cli'

    def run_wrapper(self, installed=True, missing_log=False, fail_apt=False, still_missing=False):
        if installed:
            self.state.touch()
        if fail_apt:
            self.env['FAIL_APT'] = '1'
        if still_missing:
            self.env['STILL_MISSING'] = '1'
        log = self.root / ('absent-mount/log' if missing_log else 'log')
        text = (ASSETS / 'usr/local/bin/minis-mcp-cli').read_text()
        self.assertIn('PYTHON=/usr/bin/python3', text)
        text = text.replace('PYTHON=/usr/bin/python3', 'PYTHON=' + str(self.python))
        text = text.replace('LOG="/var/minis/mcp-servers/mcp-cli.log"', 'LOG="' + str(log) + '"')
        self.wrapper.write_text(text)
        result = subprocess.run(['/bin/sh', str(self.wrapper), 'tools', 'sample'],
                                env=self.env, text=True, capture_output=True, timeout=10)
        events = self.events.read_text().splitlines() if self.events.exists() else []
        return result, events

    def test_ready_runtime_does_not_install_and_preserves_arguments(self):
        result, events = self.run_wrapper()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout), {'ok': True})
        self.assertFalse(any(e.startswith('apt ') for e in events))
        self.assertEqual(json.loads(events[-1]), ['-I', '/usr/local/lib/minis-mcp-cli/main.py', 'tools', 'sample'])
        self.assertEqual(json.loads(events[0]), ['-I', '-c', 'import httpx, httpcore, socksio; httpx.Client(trust_env=False).close()'])

    def test_absent_log_mount_does_not_block_apt_repair(self):
        result, events = self.run_wrapper(installed=False, missing_log=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        apt = [e for e in events if e.startswith('apt ')]
        self.assertEqual(len(apt), 2)
        for package in ('python3-httpx', 'python3-httpcore', 'python3-socksio'):
            self.assertIn(package, apt[-1])
        self.assertFalse((self.root / 'absent-mount').exists())
        self.assertEqual(json.loads(result.stdout), {'ok': True})

    def test_failed_install_exits_before_main_without_polluting_stdout(self):
        result, events = self.run_wrapper(installed=False, fail_apt=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, '')
        self.assertIn('installation failed', result.stderr)
        self.assertFalse(any('main.py' in e for e in events))

    def test_import_is_rechecked_after_successful_apt(self):
        result, events = self.run_wrapper(installed=False, still_missing=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('after installation', result.stderr)
        self.assertEqual(result.stdout, '')
        self.assertFalse(any('main.py' in e for e in events))

    def test_present_runtime_works_without_log_mount(self):
        result, events = self.run_wrapper(missing_log=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(any(e.startswith('apt ') for e in events))
        self.assertFalse((self.root / 'absent-mount').exists())


if __name__ == '__main__':
    unittest.main()
