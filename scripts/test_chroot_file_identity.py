#!/usr/bin/env python3
"""Cloud identity-handoff regression for the CHROOT text-file helper.
Runs only with GITHUB_ACTIONS=true and root (or passwordless sudo -n). The App
identity is reached by a Python launcher using setgroups([])/setgid()/setuid(),
never su/setuid binaries/Shizuku. Work stays in one random tempfile tree: no
chroot, mount or repository writes. Asserted: App-created uid, EACCES on root
0600 files and a root:root 0755 no-ACL directory, production helper
write/append/read/edit keeps inode/mode/uid/gid/ACL, the App-owned 0600
response is written in place and readable, root-created subdir, root 0600
read/edit, and per-session path isolation. Permission boundaries are expected behaviour, not a vulnerability.
Usage: python3 scripts/test_chroot_file_identity.py [--keep]
Exit 0 all assertions, 1 failure, 2 refused environment, 4 cleanup failure.
"""
import argparse, json, os, shutil, subprocess, sys, tempfile
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
HELPER = os.path.join(ROOT, "src/android/app/src/main/assets/file-tools/chroot_file_tools.py")
PYTHON = "/usr/bin/python3" if os.access("/usr/bin/python3", os.X_OK) else sys.executable
APP_UID = APP_GID = 2000
EACCES = 13
TOTAL, PASSED, FAILS, COUNTER = [], [], [], [0]
class Failure(Exception):
    pass

def check(ok, label, detail=""):
    TOTAL.append(1)
    if ok:
        PASSED.append(1)
        return True
    FAILS.append(label + ((": " + str(detail)) if detail else ""))
    return False
def need(ok, label, detail=""):
    if not check(ok, label, detail):
        raise Failure(label)
LAUNCH = ("import os, sys\n"
          "os.setgroups([]); os.setgid(int(sys.argv[2])); os.setuid(int(sys.argv[1]))\n"
          "os.execv(sys.argv[3], sys.argv[3:])")
APP = r'''import json, os, sys
try:
    m, a = sys.argv[1], sys.argv[2:]
    if m == "prep":
        with os.fdopen(os.open(a[0], os.O_WRONLY|os.O_CREAT|os.O_TRUNC, 0o600), "w") as f: f.write(a[1])
        os.close(os.open(a[2], os.O_WRONLY|os.O_CREAT|os.O_TRUNC, 0o600)); out = {"ok": True}
    elif m == "read":
        out = {"ok": True, "text": open(a[0], "rb").read().decode("utf-8", "replace")}
    elif m == "create":
        fd = os.open(a[0], os.O_WRONLY|os.O_CREAT|os.O_EXCL, int(a[1], 8))
        os.write(fd, a[2].encode()); os.close(fd); out = {"ok": True}
    elif m == "overwrite":
        with open(a[0], "wb") as f: f.write(a[1].encode())
        out = {"ok": True}
    else:
        out = {"ok": False, "error": "bad mode"}
except OSError as e:
    out = {"ok": False, "errno": e.errno, "error": e.strerror}
print(json.dumps(out))
'''

def app(expect_ok, *args):
    cmd = [PYTHON, "-I", "-c", LAUNCH, str(APP_UID), str(APP_GID),
           PYTHON, "-I", "-c", APP] + list(map(str, args))
    proc = subprocess.run(cmd, capture_output=True, text=True, timeout=120)
    need(proc.returncode == 0, "App worker exited 0 (%s)" % args[0], proc.stderr[-300:])
    try:
        out = json.loads(proc.stdout.strip().splitlines()[-1])
    except (IndexError, ValueError):
        need(False, "App worker output is JSON", proc.stdout[-300:])
    if expect_ok:
        need(out.get("ok") is True, "App %s succeeded" % args[0], json.dumps(out))
    else:
        need(out.get("ok") is False and out.get("errno") == EACCES,
             "App %s denied with EACCES" % args[0], json.dumps(out))
    return out
def acl_absent(path):
    for attr in ("system.posix_acl_access", "system.posix_acl_default"):
        try:
            os.getxattr(path, attr); return False, attr + " present"
        except OSError as exc:
            if exc.errno != getattr(os, "ENODATA", 61):
                return False, "%s: %s" % (attr, exc)
    return True, ""
def state(path):
    st = os.stat(path)
    return (st.st_ino, st.st_mode & 0o7777, st.st_uid, st.st_gid)
def assert_ident(path, ref, label):
    st = os.stat(path)
    for name, got, want in (("inode", st.st_ino, ref[0]), ("mode", st.st_mode & 0o7777, ref[1]),
                            ("uid", st.st_uid, ref[2]), ("gid", st.st_gid, ref[3])):
        need(got == want, "%s %s kept" % (label, name), got)
    ok, detail = acl_absent(path)
    need(ok, label + " ACL kept", detail)

def helper_call(app_home, op, path, args, policy=None):
    """Call the production helper as root; the App pre-creates request/response."""
    COUNTER[0] += 1
    req = os.path.join(app_home, "req-%d.json" % COUNTER[0])
    resp = os.path.join(app_home, "resp-%d.json" % COUNTER[0])
    request = {"operation": op, "path": path, "args": args}
    if policy is not None:
        request["app_handoff"] = policy
    app(True, "prep", req, json.dumps(request), resp)
    pre = os.stat(resp)
    need((pre.st_uid, pre.st_gid, pre.st_mode & 0o7777) == (APP_UID, APP_GID, 0o600),
         "response pre-created by App 0600",
         "%o %d:%d" % (pre.st_mode & 0o7777, pre.st_uid, pre.st_gid))
    proc = subprocess.run([PYTHON, "-I", HELPER, req, resp],
                          capture_output=True, text=True, timeout=180)
    need(proc.returncode == 0, "helper %s exited 0" % op, proc.stderr[-300:])
    need(state(resp) == (pre.st_ino, pre.st_mode & 0o7777, pre.st_uid, pre.st_gid),
         "helper wrote response in place (inode/mode/owner)")
    out = app(True, "read", resp)
    try:
        result = json.loads(out["text"])
    except ValueError:
        need(False, "helper response is JSON", out["text"][-300:])
    need(isinstance(result, dict) and isinstance(result.get("success"), bool)
         and isinstance(result.get("text"), str), "response JSON shape")
    return result
def helper_ok(app_home, op, path, args, label):
    result = helper_call(app_home, op, path, args)
    need(result["success"] is True, "helper %s %s" % (op, label), result["text"])
    return result
def main(argv):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--keep", action="store_true", help="keep the temporary tree")
    opts = parser.parse_args(argv)
    if os.environ.get("GITHUB_ACTIONS") != "true":
        sys.stderr.write("refused: cloud only; set GITHUB_ACTIONS=true "
                         "(local identity switching is forbidden)\n")
        return 2
    if os.geteuid() != 0:
        sudo = shutil.which("sudo")
        if sudo and subprocess.run([sudo, "-n", "id", "-u"],
                                   capture_output=True).returncode == 0:
            os.execv(sudo, [sudo, "-n", "--preserve-env=GITHUB_ACTIONS",
                            PYTHON, os.path.abspath(__file__)] + argv)
        sys.stderr.write("refused: needs root or passwordless sudo -n\n")
        return 2
    base = tempfile.mkdtemp(prefix="chroot-file-identity-")
    rc = 0
    try:
        os.chmod(base, 0o755)
        need(os.path.isfile(HELPER), "production helper exists", HELPER)
        app_home = os.path.join(base, "app")
        os.mkdir(app_home); os.chown(app_home, APP_UID, APP_GID); os.chmod(app_home, 0o700)
        rootdir = os.path.join(base, "rootdir")
        os.mkdir(rootdir); os.chmod(rootdir, 0o755); st = os.stat(rootdir)
        need((st.st_uid, st.st_gid, st.st_mode & 0o7777) == (0, 0, 0o755), "rootdir is root:root 0755", "%o %d:%d" % (st.st_mode & 0o7777, st.st_uid, st.st_gid))
        ok, detail = acl_absent(rootdir); need(ok, "rootdir has no access/default ACL", detail)

        created = os.path.join(app_home, "app-created.txt")
        app(True, "create", created, "600", "app-owned\n"); st = os.stat(created)
        need((st.st_uid, st.st_gid) == (APP_UID, APP_GID),
             "App-created file is owned by App", "%d:%d" % (st.st_uid, st.st_gid))
        secret = os.path.join(rootdir, "secret.txt")
        with open(secret, "wb") as handle: handle.write(b"top-secret\n")
        os.chmod(secret, 0o600); sref = state(secret)
        app(False, "read", secret); app(False, "overwrite", secret, "hijack\n")
        app(False, "create", os.path.join(rootdir, "app.txt"), "600", "x")
        managed = os.path.join(rootdir, "managed.txt")
        with open(managed, "wb") as handle: handle.write(b"alpha\nbeta\n")
        os.chmod(managed, 0o640); mref = state(managed)
        helper_ok(app_home, "write", managed, {"content": "one\n"}, "root target")
        need(open(managed, "rb").read() == b"one\n", "write replaced target content")
        assert_ident(managed, mref, "after write")
        helper_ok(app_home, "write", managed, {"content": "two\n", "append": True}, "root target append")
        need(open(managed, "rb").read() == b"one\ntwo\n", "append kept prefix")
        assert_ident(managed, mref, "after append")
        result = helper_ok(app_home, "read", managed, {}, "root target")
        need(result["text"].endswith("one\ntwo"), "helper read returned content")
        assert_ident(managed, mref, "after read")
        helper_ok(app_home, "edit", managed, {"old_string": "two", "new_string": "three"}, "root target edit")
        need(open(managed, "rb").read() == b"one\nthree\n", "edit rewrote target")
        assert_ident(managed, mref, "after edit")
        sub = os.path.join(rootdir, "newsub"); os.mkdir(sub); os.chmod(sub, 0o755)
        app(False, "create", os.path.join(sub, "app.txt"), "600", "x")
        child = os.path.join(sub, "child.txt")
        helper_ok(app_home, "write", child, {"content": "child\n"}, "root-created subdir")
        st = os.stat(child)
        need((st.st_uid, st.st_gid) == (0, 0), "helper file in new subdir is root-owned")
        result = helper_ok(app_home, "read", child, {}, "root-created subdir")
        need(result["text"].endswith("child"), "new subdir file readable by root helper")
        os.chmod(child, 0o600); app(False, "overwrite", child, "hijack\n")
        need(state(secret) == sref, "root 0600 metadata unchanged by App attempts")
        result = helper_ok(app_home, "read", secret, {}, "root 0600 file")
        need("top-secret" in result["text"], "root helper read 0600 file")
        helper_ok(app_home, "edit", secret, {"old_string": "top-secret", "new_string": "edited-secret"}, "root 0600 edit")
        need(open(secret, "rb").read() == b"edited-secret\n", "root helper edited 0600 file")
        need(state(secret) == sref, "root 0600 inode/mode/uid/gid kept")
        ok, detail = acl_absent(secret); need(ok, "root 0600 ACL kept", detail)
        app(False, "read", secret)

        sess_a, sess_b = os.path.join(base, "sessA"), os.path.join(base, "sessB")
        for directory, payload in ((sess_a, b"A0\n"), (sess_b, b"B0\n")):
            os.mkdir(directory); os.chmod(directory, 0o755)
            with open(os.path.join(directory, "target.txt"), "wb") as handle: handle.write(payload)
            os.chmod(os.path.join(directory, "target.txt"), 0o600)
        ta, tb = os.path.join(sess_a, "target.txt"), os.path.join(sess_b, "target.txt")
        aref, bref = state(ta), state(tb)
        helper_ok(app_home, "write", ta, {"content": "A1\n"}, "session A")
        need(open(tb, "rb").read() == b"B0\n", "session B unchanged by session A request")
        assert_ident(tb, bref, "session B after A")
        helper_ok(app_home, "write", tb, {"content": "B1\n"}, "session B")
        need(open(ta, "rb").read() == b"A1\n", "session A unchanged by session B request")
        assert_ident(ta, aref, "session A after B")
        # New shared output must remain usable after the Root operation ends.
        policy = {"uid": APP_UID, "gid": APP_GID, "roots": [rootdir]}
        for name, mode in (("plain", 0o755), ("masked", 0o750)):
            directory = os.path.join(rootdir, name)
            os.mkdir(directory); os.chmod(directory, mode)
            if name == "masked":
                import struct
                acl = struct.pack("<I", 2) + b"".join(struct.pack("<HHI", tag, perm, ident)
                    for tag, perm, ident in ((1, 7, 0xffffffff), (2, 7, APP_UID),
                        (4, 7, 0xffffffff), (16, 5, 0xffffffff), (32, 0, 0xffffffff)))
                os.setxattr(directory, "system.posix_acl_access", acl)
            before_dir = state(directory)
            target = os.path.join(directory, "new.txt")
            result = helper_call(app_home, "write", target, {"content": "shared\n"}, policy)
            need(result["success"], "handoff write in " + name, result["text"])
            st = os.stat(target)
            need((st.st_uid, st.st_gid) == (APP_UID, APP_GID), "new file belongs to App in " + name)
            need(result["execution"]["euid"] == 0 and result["execution"]["uid"] == APP_UID,
                 "result distinguishes Root writer and App owner")
            app(True, "read", target); app(True, "overwrite", target, "app rewrite\n")
            need(state(directory) == before_dir, "existing parent metadata kept in " + name)
            if name == "masked":
                need(os.getxattr(directory, "system.posix_acl_access") == acl, "parent mask ACL unchanged")
        auto = os.path.join(rootdir, "auto", "nested", "out.txt")
        result = helper_call(app_home, "write", auto, {"content": "auto\n"}, policy)
        need(result["success"], "auto parent handoff", result["text"])
        for path in (os.path.dirname(os.path.dirname(auto)), os.path.dirname(auto), auto):
            st = os.stat(path)
            need((st.st_uid, st.st_gid) == (APP_UID, APP_GID), "new ancestor/file owned by App: " + path)
        app(True, "create", os.path.join(os.path.dirname(auto), "app-sibling"), "600", "sibling")
        app(True, "overwrite", auto, "app rewrite\n")
        # Policy never repairs existing root-only files or out-of-root objects.
        result = helper_call(app_home, "write", secret, {"content": "preserved owner\n"}, policy)
        need(result["success"] and state(secret) == sref, "existing root 0600 metadata retained with policy")
        app(False, "read", secret)
        outside = os.path.join(base, "outside.txt")
        result = helper_call(app_home, "write", outside, {"content": "root\n"}, policy)
        need(result["success"] and os.stat(outside).st_uid == 0, "outside-root creation stays Root-owned")

    except Failure:
        rc = 1
    except Exception as exc:
        check(False, "unexpected error", "%s: %s" % (type(exc).__name__, exc)); rc = 1
    finally:
        removed = opts.keep
        if not opts.keep:
            try:
                shutil.rmtree(base); removed = not os.path.exists(base)
            except Exception as exc:
                sys.stderr.write("cleanup failed for %s: %s\n" % (base, exc))
        if not removed: rc = 4
    print("RESULT: %d/%d assertions passed, %d failed" % (len(PASSED), len(TOTAL), len(FAILS)))
    print("command: python3 scripts/test_chroot_file_identity.py" + (" --keep" if opts.keep else ""))
    print("tree: %s (%s)" % (base, "kept" if opts.keep else "removed"))
    for failure in FAILS: print("FAIL:", failure)
    return rc
if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
