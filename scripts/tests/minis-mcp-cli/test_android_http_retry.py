"""Android HTTP timeout handling: mock sessions only, no sockets or servers."""
import importlib.util
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
PKG = ROOT / 'src/android/app/src/main/assets/default_mount/usr/local/lib/minis-mcp-cli'
sys.path.insert(0, str(PKG))
spec = importlib.util.spec_from_file_location('android_mcp_daemon_retry', PKG / 'daemon.py')
daemon = importlib.util.module_from_spec(spec)
spec.loader.exec_module(daemon)


class HttpTimeoutRetryTest(unittest.TestCase):
    def setUp(self):
        self.pool = daemon.MCPPool(on_empty=lambda: None)
        self.addCleanup(self.pool.shutdown)

    def test_http_timeout_is_not_replayed_and_session_is_discarded(self):
        made = []
        def make(name, cfg):
            session = daemon.MCPHTTPSession(name, cfg)
            made.append(session)
            return session
        calls = []
        def invoke(session):
            calls.append(session)
            raise daemon.MCPError('TIMEOUT', 'synthetic timeout')
        with patch.object(daemon.config, 'get_server', return_value={'url': 'https://example.invalid/mcp'}), \
             patch.object(self.pool, '_make_session', side_effect=make):
            with self.assertRaises(daemon.MCPError) as caught:
                self.pool.call_with_retry('sample', invoke)
        self.assertEqual(caught.exception.code, 'TIMEOUT')
        self.assertEqual(len(calls), 1)
        self.assertEqual(len(made), 1)
        self.assertNotIn('sample', self.pool._pool)
        self.assertEqual(made[0].inflight, 0)

    def test_http_success_returns_result_once(self):
        with patch.object(daemon.config, 'get_server', return_value={'url': 'https://example.invalid/mcp'}):
            calls = []
            def invoke(session):
                calls.append(session)
                return {'content': []}
            self.assertEqual(self.pool.call_with_retry('sample', invoke), {'content': []})
            self.assertEqual(len(calls), 1)
            self.assertEqual(calls[0].inflight, 0)

    def test_stdio_timeout_keeps_existing_single_retry_contract(self):
        made = []
        def make(name, cfg):
            session = daemon.MCPServerProcess(name, cfg)
            made.append(session)
            return session
        calls = []
        def invoke(session):
            calls.append(session)
            if len(calls) == 1:
                raise daemon.MCPError('TIMEOUT', 'synthetic stdio timeout')
            return 'done'
        with patch.object(daemon.config, 'get_server', return_value={'command': 'synthetic-not-executed'}), \
             patch.object(self.pool, '_make_session', side_effect=make), \
             patch.object(daemon.MCPServerProcess, 'stop'):
            self.assertEqual(self.pool.call_with_retry('sample', invoke), 'done')
        self.assertEqual(len(calls), 2)
        self.assertEqual(len(made), 2)


if __name__ == '__main__':
    unittest.main()
