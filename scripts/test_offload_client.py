#!/usr/bin/env python3
"""Protocol regression harness for the CHROOT guest offload client.

Builds `minis_offload_client.c` natively, then drives the REAL binary against a
mock server that speaks the frame in OffloadWire.kt / NativeOffloadServer, and
asserts what the user sees. This is a host protocol test, not a device test: it
proves framing and diagnostics, not root, su or chroot.

Cases
  1. status: normal reply            -> output copied, exit 0
  2. exec:   handler exit 7          -> output copied, exit 7
  3. bad magic reply                 -> exit 69, stderr names the magic
  4. server closes without a frame   -> exit 69, stderr says "without a reply"
  5. peer-refusal frame (exit 77)    -> message printed, exit 77
  6. refusal frame with no body      -> exit 77, stderr says "no reply body"
  7. truncated reply path            -> exit 69, stderr says "truncated"

The build defines geteuid() -> a non-zero uid so the client skips the
app-uid switch: the host PRoot fakes uid 0 and cannot seteuid. The frame,
reply and diagnostics code under test is byte-identical to the shipped one.

Usage:
  python3 scripts/test_offload_client.py [--cc gcc] [--keep DIR]
"""
import argparse
import os
import socket
import struct
import subprocess
import sys
import tempfile
import threading

MAGIC_REQ = 0x46464F4E
MAGIC_RSP = 0x52464F4E
VERSION = 1
EX_UNAVAILABLE = 69
EX_DENIED = 77

SHIM_H = """#include <sys/types.h>
uid_t minis_test_geteuid(void);
"""
SHIM_C = """#include <sys/types.h>
/* Harness only: a non-zero euid makes the client skip the app-uid switch,
 * which the host PRoot cannot perform (it fakes uid 0). */
uid_t minis_test_geteuid(void) { return 2000; }
"""


def rd_u32(conn):
    return struct.unpack("<I", read_exact(conn, 4))[0]


def read_exact(conn, n):
    buf = b""
    while len(buf) < n:
        chunk = conn.recv(n - len(buf))
        if not chunk:
            raise EOFError("eof")
        buf += chunk
    return buf


def rd_str(conn):
    return read_exact(conn, rd_u32(conn)).decode()


def wr_u32(conn, v):
    conn.sendall(struct.pack("<I", v & 0xFFFFFFFF))


def wr_str(conn, s):
    b = s.encode()
    wr_u32(conn, len(b))
    conn.sendall(b)


def parse_request(conn):
    magic = rd_u32(conn)
    assert magic == MAGIC_REQ, "client sent magic 0x%08x" % magic
    version = rd_u32(conn)
    assert version == VERSION, "client sent version %d" % version
    pid = rd_u32(conn)
    argv = [rd_str(conn) for _ in range(rd_u32(conn))]
    env = {}
    for _ in range(rd_u32(conn)):
        k, _, v = rd_str(conn).partition("=")
        env[k] = v
    cwd = rd_str(conn)
    return pid, argv, env, cwd


class MockServer(threading.Thread):
    def __init__(self, name, mode, reply_dir):
        super().__init__(daemon=True)
        self.name = name
        self.mode = mode
        self.reply_dir = reply_dir
        self.received = None
        self.error = None
        self.srv = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.srv.bind(b"\0" + name.encode())
        self.srv.listen(4)
        self.srv.settimeout(10)

    def run(self):
        try:
            conn, _ = self.srv.accept()
        except OSError as e:
            self.error = e
            return
        try:
            self.received = parse_request(conn)
            if self.mode == "silent-close":
                return
            if self.mode == "bad-magic":
                wr_u32(conn, 0xDEADBEEF)
                wr_u32(conn, 0)
                wr_str(conn, "")
                return
            pid, argv, _, _ = self.received
            if self.mode == "deny":
                path = os.path.join(self.reply_dir, ".native-offload-%d-1" % pid)
                with open(path, "w") as f:
                    f.write("native_offload: refused - peer uid 0 is not this app's uid 2000.\n")
                wr_u32(conn, MAGIC_RSP)
                wr_u32(conn, EX_DENIED)
                wr_str(conn, path)
                return
            if self.mode == "deny-empty":
                wr_u32(conn, MAGIC_RSP)
                wr_u32(conn, EX_DENIED)
                wr_str(conn, "")
                return
            if self.mode == "truncated-path":
                path = os.path.join(self.reply_dir, ".native-offload-%d-1" % pid)
                with open(path, "w") as f:
                    f.write("x")
                wr_u32(conn, MAGIC_RSP)
                wr_u32(conn, 0)
                wr_u32(conn, 4096)  # declares 4096 bytes, sends 2
                conn.sendall(b"/t")
                return
            # normal reply
            code = 0 if self.mode == "ok0" else 7
            path = os.path.join(self.reply_dir, ".native-offload-%d-1" % pid)
            with open(path, "w") as f:
                f.write("mock output for %s\n" % os.path.basename(argv[0]))
            wr_u32(conn, MAGIC_RSP)
            wr_u32(conn, code)
            wr_str(conn, path)
        except Exception as e:  # noqa: BLE001
            self.error = e
        finally:
            try:
                conn.close()
            except OSError:
                pass


def case(index, mode, tool_args, expect_code, stderr_has, stdout_has=None):
    name = "noff-test-%d" % index
    with tempfile.TemporaryDirectory() as reply_dir:
        srv = MockServer(name, mode, reply_dir)
        srv.start()
        # Wait for the listener without sleeping: bind() already happened in the
        # constructor, so connect succeeds immediately.
        env = dict(os.environ)
        env.update({
            "MINIS_NOFF_SOCKET": name,
            "MINIS_SANDBOX_BACKEND": "CHROOT",
            "MINIS_CHAT_SESSION_ID": "session-1",
        })
        proc = subprocess.run(
            [CLIENT] + tool_args, env=env, capture_output=True, text=True, timeout=20
        )
        srv.join(10)
        problems = []
        if proc.returncode != expect_code:
            problems.append("exit %s != %s" % (proc.returncode, expect_code))
        for needle in stderr_has:
            if needle not in proc.stderr:
                problems.append("stderr missing %r (got %r)" % (needle, proc.stderr))
        if stdout_has is not None and stdout_has not in proc.stdout:
            problems.append("stdout missing %r (got %r)" % (stdout_has, proc.stdout))
        if srv.received is None and srv.error is not None:
            problems.append("server error: %s: %s" % (type(srv.error).__name__, srv.error))
        if srv.received is not None:
            pid, argv, envv, cwd = srv.received
            if argv != tool_args:
                problems.append("argv %r != %r" % (argv, tool_args))
            if envv.get("MINIS_CHAT_SESSION_ID") != "session-1":
                problems.append("session env lost: %r" % (envv.get("MINIS_CHAT_SESSION_ID"),))
            if not cwd.startswith("/"):
                problems.append("cwd %r" % (cwd,))
        print("[%s] %-28s exit=%s %s" % (
            "PASS" if not problems else "FAIL", mode, proc.returncode,
            "" if not problems else "| " + "; ".join(problems)))
        if problems:
            print("    stdout=%r\n    stderr=%r" % (proc.stdout, proc.stderr))
        return not problems


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--cc", default=os.environ.get("CC", "gcc"))
    ap.add_argument("--keep", default=None)
    args = ap.parse_args()

    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    src = os.path.join(root, "src/android/app/src/main/cpp/minis_offload_client.c")
    tmp = args.keep or tempfile.mkdtemp(prefix="noff-client-")
    os.makedirs(tmp, exist_ok=True)
    shim_h = os.path.join(tmp, "shim.h")
    shim_c = os.path.join(tmp, "shim.c")
    with open(shim_h, "w") as f:
        f.write(SHIM_H)
    with open(shim_c, "w") as f:
        f.write(SHIM_C)
    CLIENT = os.path.join(tmp, "minis-offload-client")
    build = subprocess.run(
        [args.cc, "-static", "-O2", "-Wall", "-Wextra",
         "-Dgeteuid=minis_test_geteuid", "-include", shim_h, "-Isrc",
         src, shim_c, "-o", CLIENT],
        capture_output=True, text=True,
    )
    print("build: %s" % ("ok" if build.returncode == 0 else "FAILED"))
    if build.returncode != 0:
        print(build.stdout + build.stderr)
        sys.exit(2)
    if build.stderr.strip():
        print("compiler warnings:\n" + build.stderr)

    ok = True
    ok &= case(1, "ok0", ["/usr/local/bin/android-chroot-cli", "status"], 0,
               [], "mock output for android-chroot-cli")
    ok &= case(2, "ok7", ["/usr/local/bin/android-chroot-cli", "exec", "--command", "id -u"], 7,
               [], "mock output for android-chroot-cli")
    ok &= case(3, "bad-magic", ["/usr/local/bin/android-chroot-cli", "status"], EX_UNAVAILABLE,
               ["malformed reply", "0xdeadbeef"])
    ok &= case(4, "silent-close", ["/usr/local/bin/android-chroot-cli", "status"], EX_UNAVAILABLE,
               ["closed the connection without a reply"])
    ok &= case(5, "deny", ["/usr/local/bin/android-chroot-cli", "status"], EX_DENIED,
               [], "refused - peer uid 0")
    ok &= case(6, "deny-empty", ["/usr/local/bin/android-chroot-cli", "status"], EX_DENIED,
               ["no reply body"])
    ok &= case(7, "truncated-path", ["/usr/local/bin/android-chroot-cli", "status"], EX_UNAVAILABLE,
               ["truncated reply path"])
    print("\nRESULT: %s" % ("all cases passed" if ok else "FAILURES PRESENT"))
    sys.exit(0 if ok else 1)
