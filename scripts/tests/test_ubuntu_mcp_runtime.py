"""Android Ubuntu MCP package resolution; mocked commands, no installation."""
import importlib.util
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
        self.assertIn('exec python3 /usr/local/lib/minis-mcp-cli/main.py', text)


if __name__ == '__main__':
    unittest.main()
