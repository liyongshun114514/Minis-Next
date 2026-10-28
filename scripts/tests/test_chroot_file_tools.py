"""Behavioural tests for the restricted CHROOT text-file helper.

The helper is exercised exactly the way the Android side will call it:
/usr/bin/python3 -I HELPER REQUEST_JSON RESPONSE_JSON, with the response file
pre-created (owned by the caller) and written in place.

All work happens in a throw-away temporary directory; the helper is never run
with privileges and never asked to touch anything outside it.
"""

import json
import os
import re
import stat
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HELPER = ROOT / "src/android/app/src/main/assets/file-tools/chroot_file_tools.py"
PYTHON = "/usr/bin/python3"
if not os.access(PYTHON, os.X_OK):
    PYTHON = sys.executable


class HelperTestCase(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory(prefix="chroot-file-tools-")
        self.tmp = Path(self._tmp.name)
        self._counter = 0

    def tearDown(self):
        self._tmp.cleanup()

    # -- harness -----------------------------------------------------------

    def call(self, operation, path, args=None, raw_request=None):
        """Run the helper; return (process, header/to details dict)."""
        self._counter += 1
        request_path = self.tmp / ("request-%d.json" % self._counter)
        response_path = self.tmp / ("response-%d.json" % self._counter)
        # The App pre-creates the response file so it owns it.
        response_path.write_text("", encoding="utf-8")
        if raw_request is None:
            request = {"operation": operation, "path": path, "args": args or {}}
            request_path.write_text(
                json.dumps(request, ensure_ascii=False), encoding="utf-8")
        else:
            request_path.write_text(raw_request, encoding="utf-8")
        process = subprocess.run(
            [PYTHON, "-I", str(HELPER), str(request_path), str(response_path)],
            capture_output=True, text=True, timeout=180)
        result = json.loads(response_path.read_text(encoding="utf-8"))
        self.assertIsInstance(result, dict)
        self.assertIsInstance(result.get("success"), bool)
        self.assertIsInstance(result.get("text"), str)
        return process, result

    def ok(self, operation, path, args=None):
        process, result = self.call(operation, path, args)
        self.assertTrue(result["success"], result["text"])
        self.assertEqual(process.stderr, "")
        self.assertEqual(process.stdout, "")
        return result["text"]

    def blocked(self, operation, path, args=None):
        process, result = self.call(operation, path, args)
        self.assertFalse(result["success"], result["text"])
        self.assertEqual(process.stdout, "")
        return result["text"]

    def write_bytes(self, name, data):
        target = self.tmp / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        return target

    def header(self, text):
        return text.split("\n", 1)[0]

    def body(self, text):
        return text.split("\n", 1)[1] if "\n" in text else ""


class WriteTests(HelperTestCase):
    def test_creates_all_parents_and_reports_real_bytes(self):
        target = self.tmp / "a" / "b" / "c" / "\u4e2d\u6587.txt"
        content = "\u4f60\u597d\U0001f642\n\n"
        text = self.ok("write", str(target), {"content": content})
        self.assertEqual(target.read_bytes(), content.encode("utf-8"))
        self.assertEqual(
            text, "Wrote to %s (%d bytes)" % (target, len(content.encode("utf-8"))))
        self.assertTrue((self.tmp / "a/b/c").is_dir())

    def test_append_preserves_existing_bytes_and_identity(self):
        target = self.write_bytes("log.txt", "\u4e00\n".encode("utf-8"))
        os.chmod(target, 0o640)
        before = os.stat(target)
        self.ok("write", str(target), {"content": "\U0001f642\n", "append": True})
        after = os.stat(target)
        self.assertEqual(target.read_bytes(),
                         "\u4e00\n\U0001f642\n".encode("utf-8"))
        self.assertEqual(before.st_ino, after.st_ino)
        self.assertEqual(before.st_mode, after.st_mode)
        self.assertEqual(before.st_uid, after.st_uid)
        self.assertEqual(before.st_gid, after.st_gid)

    def test_overwrite_truncates_in_place(self):
        target = self.write_bytes("notes.txt", b"x" * 5000)
        os.chmod(target, 0o600)
        before = os.stat(target)
        text = self.ok("write", str(target), {"content": "short\n"})
        after = os.stat(target)
        self.assertEqual(target.read_bytes(), b"short\n")
        self.assertEqual(text, "Wrote to %s (6 bytes)" % target)
        self.assertEqual(before.st_ino, after.st_ino)
        self.assertEqual(before.st_mode, after.st_mode)

    def test_empty_content_and_no_trailing_newline(self):
        target = self.tmp / "empty.txt"
        text = self.ok("write", str(target), {"content": ""})
        self.assertEqual(target.read_bytes(), b"")
        self.assertEqual(text, "Wrote to %s (0 bytes)" % target)
        self.ok("write", str(target), {"content": "no-newline"})
        self.assertEqual(target.read_bytes(), b"no-newline")

    def test_parent_that_is_a_file_stops_before_opening(self):
        blocker = self.write_bytes("blocker", b"keep-me")
        target = self.tmp / "blocker" / "child" / "x.txt"
        text = self.blocked("write", str(target), {"content": "hi"})
        self.assertIn("cannot create parent directory", text)
        self.assertIn("failed path:", text)
        self.assertIn("Not a directory [ENOTDIR]", text)
        self.assertIn("Nothing was written", text)
        self.assertEqual(blocker.read_bytes(), b"keep-me")
        self.assertFalse(target.exists())
        self.assertFalse((self.tmp / "blocker" / "child").exists())

    def test_failed_parent_keeps_existing_file_bytes(self):
        existing = self.write_bytes("dir/file.txt", b"original")
        # file.txt is not a directory, so dir/file.txt/child cannot be created.
        target = self.tmp / "dir" / "file.txt" / "child"
        text = self.blocked("write", str(target), {"content": "clobber"})
        self.assertIn("Nothing was written", text)
        self.assertEqual(existing.read_bytes(), b"original")

    @unittest.skipIf(os.geteuid() == 0,
                     "root ignores directory write permission; ENOTDIR covers the stop path")
    def test_unwritable_parent_reports_permission_denied(self):
        locked = self.tmp / "locked"
        locked.mkdir()
        os.chmod(locked, 0o555)
        try:
            target = locked / "sub" / "x.txt"
            text = self.blocked("write", str(target), {"content": "hi"})
            self.assertIn("cannot create parent directory", text)
            self.assertIn("Permission denied [EACCES]", text)
            self.assertIn("Nothing was written", text)
            self.assertFalse(target.exists())
        finally:
            os.chmod(locked, 0o755)

    def test_directory_target_fails_without_clobbering(self):
        target = self.tmp / "adir"
        target.mkdir()
        text = self.blocked("write", str(target), {"content": "hi"})
        self.assertIn("cannot write", text)
        self.assertTrue(target.is_dir())


class EditTests(HelperTestCase):
    def test_unique_replacement_keeps_other_bytes_and_identity(self):
        original = "head \u4e2d\u6587 \U0001f642 tail\nsecond\n"
        target = self.write_bytes("doc.txt", original.encode("utf-8"))
        os.chmod(target, 0o640)
        before = os.stat(target)
        text = self.ok("edit", str(target), {
            "old_string": "\u4e2d\u6587 \U0001f642", "new_string": "emoji \U0001f642\U0001f642",
        })
        expected = original.replace("\u4e2d\u6587 \U0001f642", "emoji \U0001f642\U0001f642")
        after = os.stat(target)
        self.assertEqual(target.read_bytes(), expected.encode("utf-8"))
        self.assertEqual(
            text, "Edited %s (1 replacement(s), %d bytes)"
            % (target, len(expected.encode("utf-8"))))
        self.assertEqual(before.st_ino, after.st_ino)
        self.assertEqual(before.st_mode, after.st_mode)
        self.assertEqual(before.st_uid, after.st_uid)

    def test_multiple_occurrences_require_replace_all(self):
        target = self.write_bytes("multi.txt", b"alpha beta alpha gamma\n")
        text = self.blocked("edit", str(target), {
            "old_string": "alpha", "new_string": "A"})
        self.assertIn("found 2 times", text)
        self.assertIn("replace_all=true", text)
        self.assertEqual(target.read_bytes(), b"alpha beta alpha gamma\n")

        text = self.ok("edit", str(target), {
            "old_string": "alpha", "new_string": "\u7532", "replace_all": True})
        self.assertEqual(target.read_bytes(), "\u7532 beta \u7532 gamma\n".encode("utf-8"))
        self.assertTrue(text.startswith("Edited %s (2 replacement(s), " % target))

    def test_delete_with_empty_new_string(self):
        target = self.write_bytes("del.txt", b"keepDROPkeep\n")
        self.ok("edit", str(target), {"old_string": "DROP", "new_string": ""})
        self.assertEqual(target.read_bytes(), b"keepkeep\n")

    def test_edit_errors_leave_file_untouched(self):
        target = self.write_bytes("safe.txt", b"only once\n")
        cases = [
            ({"old_string": "", "new_string": "x"}, "'old_string' is required"),
            ({"old_string": "absent", "new_string": "x"}, "old_string not found"),
            ({"old_string": "only", "new_string": "x", "replace_all": False}, None),
        ]
        for args, expected in cases:
            with self.subTest(args=args):
                process, result = self.call("edit", str(target), args)
                if expected is None:
                    self.assertTrue(result["success"], result["text"])
                    target.write_bytes(b"only once\n")
                else:
                    self.assertFalse(result["success"])
                    self.assertIn(expected, result["text"])
                    self.assertEqual(target.read_bytes(), b"only once\n")
                self.assertEqual(process.stdout, "")

        missing = self.tmp / "nope.txt"
        text = self.blocked("edit", str(missing), {"old_string": "a", "new_string": "b"})
        self.assertIn("File not found", text)
        text = self.blocked("edit", str(self.tmp), {"old_string": "a", "new_string": "b"})
        self.assertIn("Path is a directory", text)

    def test_edit_rejects_file_over_limit(self):
        limit = 8 * 1024 * 1024
        target = self.tmp / "big.txt"
        with open(target, "wb") as handle:
            handle.write(b"a" * (limit + 1))
        text = self.blocked("edit", str(target), {"old_string": "a", "new_string": "b"})
        self.assertIn("handles files up to %d bytes" % limit, text)
        self.assertEqual(os.stat(target).st_size, limit + 1)

    def test_edit_rejects_invalid_utf8(self):
        target = self.write_bytes("latin1.txt", b"caf\xe9\n")
        text = self.blocked("edit", str(target), {"old_string": "caf", "new_string": "CAF"})
        self.assertIn("not valid UTF-8", text)
        self.assertEqual(target.read_bytes(), b"caf\xe9\n")

    def test_edit_no_trailing_newline_appended(self):
        target = self.write_bytes("tail.txt", b"a\nb")
        self.ok("edit", str(target), {"old_string": "b", "new_string": "B"})
        self.assertEqual(target.read_bytes(), b"a\nB")


class ReadTests(HelperTestCase):
    def test_very_long_single_line_head_keeps_start_and_tail_keeps_end(self):
        target = self.write_bytes("long-line", b"BEGIN" + b"x" * 600000 + b"END")
        head = self.ok("read", str(target), {"max_length": 20})
        tail = self.ok("read", str(target), {"max_length": 20, "direction": "tail"})
        self.assertTrue(self.body(head).startswith("BEGIN"))
        self.assertTrue(self.body(tail).endswith("END"))


    def make_lines(self, count, ending=True):
        data = "\n".join("line%02d" % i for i in range(1, count + 1))
        if ending:
            data += "\n"
        return data

    def test_head_offset_and_lines(self):
        path = self.write_bytes("lines.txt", self.make_lines(5).encode())
        text = self.ok("read", str(path), {"offset": 3, "lines": 2})
        self.assertEqual(self.header(text), "[%s | %d bytes | 5 lines | showing 3-4 of 5]"
                         % (path, os.stat(path).st_size))
        self.assertEqual(self.body(text), "line03\nline04")

    def test_missing_trailing_newline_has_no_extra_line(self):
        path = self.write_bytes("nonl.txt", b"a\nb\nc")
        text = self.ok("read", str(path), {})
        self.assertIn("3 lines | showing 1-3 of 3", text)
        self.assertEqual(self.body(text), "a\nb\nc")

    def test_crlf_and_lone_cr_are_line_breaks(self):
        path = self.write_bytes("crlf.txt", b"a\r\nb\rc\n")
        text = self.ok("read", str(path), {})
        self.assertIn("3 lines | showing 1-3 of 3", text)
        self.assertEqual(self.body(text), "a\nb\nc")

    def test_empty_file(self):
        path = self.write_bytes("zero.txt", b"")
        text = self.ok("read", str(path), {})
        self.assertEqual(text, "[%s | 0 bytes | 0 lines | showing 1-0 of 0]\n" % path)

    def test_trailing_newline_only_file(self):
        path = self.write_bytes("blank.txt", b"\n")
        text = self.ok("read", str(path), {})
        self.assertEqual(text, "[%s | 1 bytes | 1 lines | showing 1-1 of 1]\n" % path)

    def test_binary_detection(self):
        path = self.write_bytes("bin.dat", b"PNG\x00\x01\x02")
        text = self.ok("read", str(path), {})
        self.assertEqual(
            text, "[%s | 6 bytes | binary file \u2014 cannot display contents]" % path)

    def test_tail_direction(self):
        path = self.write_bytes("tail.txt", self.make_lines(10).encode())
        text = self.ok("read", str(path), {"direction": "tail", "lines": 3})
        self.assertEqual(self.header(text).split("|")[-1].strip(),
                         "showing 8-10 of 10]")
        self.assertEqual(self.body(text), "line08\nline09\nline10")

    def test_tail_truncation_keeps_the_end_and_offers_no_offset(self):
        path = self.write_bytes("longtail.txt", self.make_lines(30).encode())
        text = self.ok("read", str(path), {"direction": "tail", "max_length": 20})
        header = self.header(text)
        self.assertIn("truncated at 20 chars", header)
        self.assertIn("retry with a smaller lines value", header)
        self.assertNotIn("next_offset", header)
        self.assertTrue(self.body(text).endswith("line30"))
        self.assertLessEqual(len(self.body(text)), 20)

    def test_head_pagination_walks_every_line_once(self):
        expected = ["line%02d" % i for i in range(1, 41)]
        path = self.write_bytes("page.txt", ("\n".join(expected) + "\n").encode())
        collected = []
        offset = 1
        for _ in range(100):
            text = self.ok("read", str(path), {"offset": offset, "max_length": 15})
            collected.extend(self.body(text).split("\n"))
            header = self.header(text)
            match = re.search(r"next_offset=(\d+)", header)
            if not match:
                break
            offset = int(match.group(1))
        self.assertEqual(collected, expected)

    def test_max_length_defaults_and_hard_cap(self):
        path = self.write_bytes("one-line.txt", (b"a" * 200000) + b"\n")
        text = self.ok("read", str(path), {"max_length": 999999})
        header = self.header(text)
        self.assertIn("truncated at 80000 chars", header)
        self.assertEqual(len(self.body(text)), 80000)
        self.assertLess(len(text), 80600)

    def test_max_length_default_is_15000(self):
        path = self.write_bytes("wide.txt", (b"a" * 40000) + b"\n")
        text = self.ok("read", str(path), {})
        self.assertIn("truncated at 15000 chars", self.header(text))
        self.assertEqual(len(self.body(text)), 15000)

    def test_read_errors(self):
        missing = self.tmp / "missing.txt"
        self.assertIn("File not found", self.blocked("read", str(missing), {}))
        self.assertIn("Path is a directory", self.blocked("read", str(self.tmp), {}))

        path = self.write_bytes("ok.txt", b"a\n")
        self.assertIn("'lines' must not be negative",
                      self.blocked("read", str(path), {"lines": -1}))
        self.assertIn("'path' is required", self.blocked("read", "", {}))
        self.assertIn("must be an absolute guest path",
                      self.blocked("read", "relative.txt", {}))

    def test_offset_beyond_end_clamps_like_the_java_tool(self):
        path = self.write_bytes("few.txt", b"a\nb\n")
        text = self.ok("read", str(path), {"offset": 5})
        self.assertEqual(self.header(text), "[%s | 4 bytes | 2 lines | showing 5-4 of 2]"
                         % path)
        self.assertEqual(self.body(text), "")


class HandoffTests(HelperTestCase):
    def module(self):
        import importlib.util
        spec = importlib.util.spec_from_file_location("file_helper", HELPER)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def test_failed_new_file_handoff_never_writes_content(self):
        from unittest.mock import patch
        helper = self.module()
        target = self.tmp / "new.txt"
        policy = helper.AppHandoff({"uid": 12345, "gid": 12345, "roots": [str(self.tmp)]})
        with patch.object(helper.os, "fchown", side_effect=PermissionError(1, "handoff refused")):
            with self.assertRaises(helper.ToolError) as err:
                helper.op_write(str(target), {"content": "must not land"}, policy)
        self.assertIn("no content was written", str(err.exception))
        self.assertEqual(target.read_bytes(), b"")

    def test_failed_new_parent_handoff_stops_before_target_open(self):
        from unittest.mock import patch
        helper = self.module()
        target = self.tmp / "first" / "second" / "new.txt"
        policy = helper.AppHandoff({"uid": 12345, "gid": 12345, "roots": [str(self.tmp)]})
        with patch.object(helper.os, "fchown", side_effect=PermissionError(1, "handoff refused")):
            with self.assertRaises(helper.ParentDirFailure):
                helper.op_write(str(target), {"content": "must not land"}, policy)
        self.assertFalse(target.exists())
        self.assertFalse(target.parent.exists())

    def test_existing_target_does_not_call_ownership_handoff(self):
        from unittest.mock import patch
        helper = self.module()
        target = self.write_bytes("existing.txt", b"old")
        before = target.stat()
        policy = helper.AppHandoff({"uid": 12345, "gid": 12345, "roots": [str(self.tmp)]})
        with patch.object(helper.os, "fchown", side_effect=AssertionError("existing target must not be changed")):
            self.assertTrue(helper.op_write(str(target), {"content": "new"}, policy)[0])
        after = target.stat()
        self.assertEqual((before.st_ino, before.st_uid, before.st_gid, before.st_mode),
                         (after.st_ino, after.st_uid, after.st_gid, after.st_mode))

    def test_output_symlink_to_new_file_uses_the_new_inode_handoff(self):
        from unittest.mock import patch
        helper = self.module()
        target = self.tmp / "actual.txt"
        alias = self.tmp / "output-link"
        alias.symlink_to(target.name)
        policy = helper.AppHandoff({"uid": 12345, "gid": 12345, "roots": [str(self.tmp)]})
        with patch.object(policy, "uid", os.getuid()), patch.object(policy, "gid", os.getgid()):
            self.assertTrue(helper.op_write(str(alias), {"content": "through link"}, policy)[0])
        self.assertEqual(target.read_text(), "through link")
        self.assertTrue(alias.is_symlink())
        self.assertEqual(policy.count, 1)

    def test_same_app_identity_creates_parents_without_changing_modes(self):
        helper = self.module()
        policy = helper.AppHandoff({"uid": 12345, "gid": 12345, "roots": [str(self.tmp)]})
        target = self.tmp / "dir" / "new"
        from unittest.mock import patch
        with patch.object(policy, "uid", os.getuid()), patch.object(policy, "gid", os.getgid()):
            self.assertTrue(helper.op_write(str(target), {"content": "new"}, policy)[0])
        self.assertEqual(target.read_bytes(), b"new")
        self.assertEqual(policy.count, 2)


class ProtocolTests(HelperTestCase):
    def test_response_file_is_written_in_place(self):
        target = self.write_bytes("proto.txt", b"x\n")
        response = self.tmp / "response-fixed.json"
        response.write_text("", encoding="utf-8")
        os.chmod(response, 0o600)
        before = os.stat(response)
        request = self.tmp / "request-fixed.json"
        request.write_text(json.dumps({
            "operation": "read", "path": str(target), "args": {}}), encoding="utf-8")
        process = subprocess.run(
            [PYTHON, "-I", str(HELPER), str(request), str(response)],
            capture_output=True, text=True, timeout=120)
        after = os.stat(response)
        result = json.loads(response.read_text(encoding="utf-8"))
        self.assertEqual(process.returncode, 0)
        self.assertEqual(process.stdout, "")
        self.assertEqual(process.stderr, "")
        self.assertTrue(result["success"])
        self.assertEqual(before.st_ino, after.st_ino)
        self.assertEqual(before.st_mode, after.st_mode)

    def test_malformed_request_gets_a_response_and_no_content_leak(self):
        process, result = self.call("read", "", raw_request="{not json")
        self.assertFalse(result["success"])
        self.assertTrue(result["text"].startswith("Error: invalid request"))
        self.assertEqual(process.returncode, 0)
        self.assertEqual(process.stdout, "")
        self.assertNotIn("not json", process.stderr)

    def test_unknown_operation(self):
        text = self.blocked("delete", str(self.tmp / "x"), {})
        self.assertIn("unsupported operation", text)

    def test_ignores_original_tool_metadata(self):
        target = self.write_bytes("meta.txt", b"body\n")
        text = self.ok("read", str(target), {
            "tool_title": "Read Python script contents", "path": str(target)})
        self.assertIn("1 lines", text)

    def test_helper_contains_no_shell_eval_or_permission_calls(self):
        source = HELPER.read_text(encoding="utf-8")
        for needle in ("import subprocess", "os.system(", "os.popen(",
                       "eval(", "exec(", "os.chmod", "os.chown(", "os.setuid"):
            self.assertNotIn(needle, source, needle)
        self.assertIn("os.fchown(fd, self.uid, self.gid)", source)


if __name__ == "__main__":
    unittest.main(verbosity=2)
