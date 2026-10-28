#!/usr/bin/env python3
"""Ordinary file-handoff regression for shared mount roots (minis_shared_dirs.h).

Builds a throwaway C fixture against the real header, calls
minis_shared_prepare() on ordinary directories, and checks what the kernel
inherits. Host filesystem test only: no chroot/mount helper, no probing.

Local layer (this host's own uid): fresh App-owned root gets a named-user rwx
default ACL with group/other from the root bits and the root mode/access ACL
untouched; a second call is idempotent; an existing different default ACL is
preserved (EEXIST); uid/gid mismatch -> EPERM; missing path / non-directory ->
plain errno; inherited file 0664 and dir 0775, nested dir, grandchild; explicit
0600/0700 and later chmod stay restricted; pre-existing files unchanged;
concurrent task dirs; managed-path mapping.

Identity layer (cloud CI only: GITHUB_ACTIONS=true + passwordless sudo): App uid
creates a file -> root reads/appends; root creates a nested task dir -> App
writes; 0600/0700 and chmod respected; a second App uid/gid pair; concurrent
dirs. Skipped unless a probe proves the uid switch is real (a file created "as
2000" must be owned by 2000). This host never calls su/sudo/Shizuku and never
changes to another identity locally.

Usage: python3 scripts/test_chroot_shared_dirs.py [--cc gcc] [--keep DIR]
                                                  [--require-identities]
Exit: 0 all checked cases passed, 1 failure or identities required, 2 build
failed, 3 no default-ACL support, 4 the temporary tree could not be removed
(a cleanup failure on an otherwise passing run is never reported as success).
"""
import argparse, json, os, shutil, struct, subprocess, sys, tempfile, threading

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
HEADER = os.path.join(ROOT, "src/android/app/src/main/cpp/minis_shared_dirs.h")
UOBJ, USR, GOBJ, MASK, OTH = 1, 2, 4, 0x10, 0x20
NO_ID = 0xFFFFFFFF
APP_UID, APP_GID, ALT_UID, ALT_GID = 2000, 2000, 2002, 2003
EEXIST, EPERM, ENOENT, ENOTDIR = 17, 1, 2, 20

PROBE_C = r'''
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "minis_shared_dirs.h"
int main(int argc, char **argv) {
    if (argc >= 2 && !strcmp(argv[1], "managed")) {
        if (argc != 5) return 64;
        printf("managed=%d\n", minis_shared_is_managed(argv[2], argv[3], argv[4]));
        return 0;
    }
    if (argc != 4) return 64;
    errno = 0;
    int rc = minis_shared_prepare(argv[1], (uid_t)strtoul(argv[2], NULL, 10),
                                  (gid_t)strtoul(argv[3], NULL, 10));
    printf("rc=%d errno=%d msg=%s\n", rc, errno, rc == 0 ? "-" : strerror(errno));
    return 0;
}
'''
LAUNCHER_PY = r'''import os, sys
os.setgroups([]); os.setgid(int(sys.argv[2])); os.setuid(int(sys.argv[1]))
os.execv(sys.argv[3], sys.argv[3:])
'''
HELPER_PY = r'''import json, os, sys
os.umask(0o022)
def out(ok=True, **kw):
    kw.update(ok=ok, uid=os.getuid(), gid=os.getgid()); print(json.dumps(kw)); sys.exit(0)
try:
    c, a = sys.argv[1], sys.argv[2:]
    if c == "whoami": out()
    elif c == "read": out(text=open(a[0], "rb").read().decode("utf-8", "replace"))
    elif c == "write":
        fd = os.open(a[0], os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o666)
        os.write(fd, a[1].encode()); os.close(fd); out()
    elif c == "overwrite":
        with open(a[0], "wb") as f: f.write(a[1].encode())
        out()
    elif c == "mkfile":
        fd = os.open(a[0], os.O_WRONLY | os.O_CREAT | os.O_EXCL, int(a[1], 8))
        if len(a) > 2: os.write(fd, a[2].encode())
        os.close(fd); out()
    elif c == "mkdir": os.mkdir(a[0], int(a[1], 8)); out()
    elif c == "chmod": os.chmod(a[0], int(a[1], 8)); out()
    elif c == "chown": os.chown(a[0], int(a[1]), int(a[2])); out()
    elif c == "stat":
        st = os.stat(a[0]); out(mode=st.st_mode & 0o7777, owner=st.st_uid, group=st.st_gid)
    elif c == "rmtree":
        import shutil; shutil.rmtree(a[0]); out(removed=not os.path.exists(a[0]))
    out(False, error="unknown command")
except OSError as e:
    out(False, error=e.strerror, errno=e.errno)
'''


def enc(entries):
    return struct.pack("<I", 2) + b"".join(struct.pack("<HHI", t, p, i) for t, p, i in entries)


def acl(path, attr="system.posix_acl_default"):
    """{tag: (perm, id)} for an ACL xattr, or None when absent."""
    try:
        raw = os.getxattr(path, attr)
    except OSError as e:
        if e.errno == getattr(os, "ENODATA", 61):
            return None
        raise
    return {t: (p, i) for t, p, i in (struct.unpack("<HHI", raw[k:k + 8])
                                      for k in range(4, len(raw), 8))}


def want_acl(mode, uid):
    return {UOBJ: ((mode >> 6) & 7, NO_ID), USR: (7, uid), GOBJ: ((mode >> 3) & 7, NO_ID),
            MASK: (7, NO_ID), OTH: (mode & 7, NO_ID)}


def eff(path):
    m = acl(path, "system.posix_acl_access")
    return None if not m else m[USR][0] & m.get(MASK, (7, 0))[0]


def mode_of(path):
    return os.stat(path).st_mode & 0o7777


def mdir(base, name, mode):
    path = os.path.join(base, name)
    os.mkdir(path, mode)
    return path


def mkfile(path, mode, data=b""):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, mode)
    os.write(fd, data)
    os.close(fd)


def snap(path):
    st = os.stat(path)
    return (st.st_mode & 0o7777, st.st_uid, st.st_gid, st.st_size, st.st_mtime_ns,
            {n: os.getxattr(path, n) for n in sorted(os.listxattr(path))},
            open(path, "rb").read() if os.path.isfile(path) else None)


class Report:
    def __init__(self):
        self.ok = self.bad = 0
        self.unverified = []

    def check(self, name, cond, detail=""):
        self.ok += bool(cond)
        self.bad += not cond
        print("[%s] %s%s" % ("PASS" if cond else "FAIL", name,
                             "" if cond or not detail else "  | %s" % detail))
        return cond

    def skip(self, name, why):
        self.unverified.append(name)
        print("[UNVERIFIED] %s  | %s" % (name, why))

    def case(self, name, fn):
        try:
            fn(self)
        except Exception as e:  # noqa: BLE001
            self.bad += 1
            print("[FAIL] %s  | crashed: %r" % (name, e))


def build(tmp, cc):
    src = os.path.join(tmp, "probe.c")
    with open(src, "w") as f:
        f.write(PROBE_C)
    binary = os.path.join(tmp, "shared-dirs-probe")
    cmd = [cc, "-std=c11", "-D_GNU_SOURCE", "-O1", "-Wall", "-Wextra",
           "-Wno-unused-function", "-I", os.path.dirname(HEADER), src, "-o", binary]
    print("[BUILD] %s" % " ".join(cmd))
    proc = subprocess.run(cmd, capture_output=True, text=True)
    if proc.returncode:
        print(proc.stdout + proc.stderr)
        return None
    if proc.stderr.strip():
        print("[BUILD] warnings:\n" + proc.stderr.strip())
    return binary


def prepare(binary, path, uid, gid):
    proc = subprocess.run([binary, path, str(uid), str(gid)],
                          capture_output=True, text=True, timeout=30)
    fields = dict(p.split("=", 1) for p in proc.stdout.split() if "=" in p)
    if "rc" not in fields:
        raise RuntimeError("fixture failed: %r %r" % (proc.stdout, proc.stderr))
    return int(fields["rc"]), int(fields["errno"]), proc.stdout.strip()


def managed(binary, guest, host, files_dir):
    proc = subprocess.run([binary, "managed", guest, host, files_dir],
                          capture_output=True, text=True, timeout=30)
    return proc.stdout.strip() == "managed=1"


# --------------------------------------------------------------------------
# local ACL / mode layer
# --------------------------------------------------------------------------

def local_cases(r, bin, base, uid, gid):
    def P(path, u=uid, g=gid):
        return prepare(bin, path, u, g)

    root = mdir(base, "fresh", 0o755)
    had = set(os.listxattr(root))
    rc, _, raw = P(root)
    r.check("fresh: 0, named-user rwx, group/other keep 0755, root untouched",
            rc == 0 and acl(root) == want_acl(0o755, uid) and mode_of(root) == 0o755
            and acl(root, "system.posix_acl_access") is None, "%s | %s" % (raw, acl(root)))
    first = os.getxattr(root, "system.posix_acl_default")
    r.check("fresh: only the default ACL xattr is added, second call 0 and unchanged",
            sorted(set(os.listxattr(root)) - had) == ["system.posix_acl_default"]
            and P(root)[0] == 0 and os.getxattr(root, "system.posix_acl_default") == first)

    for name, num, mask, note in (("policy", 3999, 7, "foreign named user"),
                                  ("policy-narrow", uid, 5, "narrower mask")):
        path = mdir(base, name, 0o755)
        os.setxattr(path, "system.posix_acl_default",
                    enc([(UOBJ, 7, NO_ID), (USR, 7, num), (GOBJ, 5, NO_ID),
                         (MASK, mask, NO_ID), (OTH, 5, NO_ID)]))
        original = os.getxattr(path, "system.posix_acl_default")
        rc2, err2, raw2 = P(path)
        r.check("existing policy (%s): -1/EEXIST, bytes kept" % note,
                (rc2, err2) == (-1, EEXIST)
                and os.getxattr(path, "system.posix_acl_default") == original, raw2)

    guard = mdir(base, "guard", 0o755)
    r.check("guard: uid/gid mismatch -> -1/EPERM and nothing written, owner accepted",
            P(guard, uid + 1, gid)[:2] == (-1, EPERM)
            and P(guard, uid, gid + 1)[:2] == (-1, EPERM) and acl(guard) is None
            and P(guard)[0] == 0)
    r.check("path: missing -> -1/ENOENT", P(os.path.join(base, "missing"))[:2] == (-1, ENOENT))
    plain = os.path.join(base, "plain.txt")
    mkfile(plain, 0o640, b"plain\n")
    old = snap(plain)
    rc3, err3, raw3 = P(plain)
    r.check("path: regular file -> -1/ENOTDIR and unchanged",
            (rc3, err3) == (-1, ENOTDIR) and snap(plain) == old, raw3)

    inh = mdir(base, "inherit", 0o755)
    P(inh)
    f = os.path.join(inh, "app-file.txt")
    mkfile(f, 0o666)
    task = mdir(inh, "task", 0o777)
    grand = os.path.join(task, "grandchild.txt")
    mkfile(grand, 0o666)
    r.check("inherit: file 0666 is 0664, named user rwx masked to rw",
            mode_of(f) == 0o664 and acl(f, "system.posix_acl_access")[USR] == (7, uid)
            and eff(f) == 6, acl(f, "system.posix_acl_access"))
    r.check("inherit: dir 0777 is 0775, named user rwx, default ACL kept",
            mode_of(task) == 0o775 and eff(task) == 7 and acl(task) == want_acl(0o755, uid),
            "%s | %s" % (acl(task, "system.posix_acl_access"), acl(task)))
    r.check("inherit: grandchild file reaches the named user", eff(grand) == 6,
            acl(grand, "system.posix_acl_access"))

    priv = os.path.join(inh, "private.txt")
    mkfile(priv, 0o600)
    pdir = mdir(inh, "private-dir", 0o700)
    r.check("mode 0600/0700: kept exactly, named user effective 0",
            (mode_of(priv), eff(priv)) == (0o600, 0) and (mode_of(pdir), eff(pdir)) == (0o700, 0))
    shared = os.path.join(inh, "shared.txt")
    mkfile(shared, 0o666)
    os.chmod(shared, 0o640)
    mid = (mode_of(shared), eff(shared))
    os.chmod(shared, 0o600)
    r.check("chmod 0640 -> 0600 keeps shrinking access",
            mid == (0o640, 4) and (mode_of(shared), eff(shared)) == (0o600, 0),
            "%s -> %s" % (mid, (mode_of(shared), eff(shared))))

    legacy = mdir(base, "legacy", 0o755)
    lf = os.path.join(legacy, "legacy.txt")
    mkfile(lf, 0o640, b"keep me\n")
    ld = mdir(legacy, "legacy-dir", 0o750)
    child = os.path.join(ld, "child.txt")
    mkfile(child, 0o644, b"child\n")
    old_all = {p: snap(p) for p in (lf, ld, child)}
    r.check("legacy: prepare returns 0, nothing changed, nothing recursed",
            P(legacy)[0] == 0 and all(snap(p) == old_all[p] for p in old_all)
            and acl(ld) is None and acl(child) is None
            and acl(child, "system.posix_acl_access") is None,
            [p for p in old_all if snap(p) != old_all[p]])

    par = mdir(base, "parallel", 0o755)
    P(par)
    errors = []

    def worker(i):
        try:
            mkfile(os.path.join(mdir(par, "task-%d" % i, 0o777), "out.txt"), 0o666, b"%d\n" % i)
        except OSError as e:
            errors.append("task-%d: %s" % (i, e))

    threads = [threading.Thread(target=worker, args=(i,)) for i in range(6)]
    for t in threads:
        t.start()
    for t in threads:
        t.join(60)
    r.check("concurrent: 6 task dirs, all inherit the named user",
            not errors and not any(t.is_alive() for t in threads)
            and all(acl(os.path.join(par, "task-%d" % i)) == want_acl(0o755, uid)
                    and eff(os.path.join(par, "task-%d" % i, "out.txt")) == 6
                    for i in range(6)), errors)

    files = "/data/user/0/com.openminis.app/files"
    r.check("managed: session workspace and global shared map to the App tree",
            managed(bin, "/var/minis/workspace", files + "/minis-sessions/s1/workspace", files)
            and managed(bin, "/var/minis/shared", files + "/minis-global/shared", files))
    r.check("managed: other session dir, other files dir, unmanaged guest path rejected",
            not managed(bin, "/var/minis/workspace", files + "/minis-sessions/s1/offloads", files)
            and not managed(bin, "/var/minis/workspace", "/tmp/minis-sessions/s1/workspace", files)
            and not managed(bin, "/var/minis/rootfs", files + "/minis-sessions/s1/rootfs", files))
# --------------------------------------------------------------------------
# identity layer -- cloud CI only, App vs root, proven uid separation
# --------------------------------------------------------------------------

class Ids:
    def __init__(self, prefix, launcher, helper):
        self.prefix, self.launcher, self.helper = prefix, launcher, helper
        self.proven = False

    def act(self, uid, gid, *args):
        cmd = self.prefix + [sys.executable, self.launcher, str(uid), str(gid),
                             sys.executable, self.helper] + [str(a) for a in args]
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=120)
        lines = [l for l in proc.stdout.splitlines() if l.strip()]
        return json.loads(lines[-1]) if lines else {"ok": False, "error": proc.stderr.strip()}

    def prepare(self, bin, path, uid, gid):
        proc = subprocess.run(self.prefix + [sys.executable, self.launcher, "0", "0",
                                             bin, path, str(uid), str(gid)],
                              capture_output=True, text=True, timeout=120)
        f = dict(p.split("=", 1) for p in proc.stdout.split() if "=" in p)
        return (int(f["rc"]), proc.stdout.strip()) if "rc" in f else (-1, proc.stdout + proc.stderr)


def cloud_identities(tmp):
    """(Ids|None, why). Cloud CI only; this host never switches identity."""
    if os.environ.get("GITHUB_ACTIONS") != "true":
        return None, "cloud CI only (GITHUB_ACTIONS=true); local run has no identity layer"
    if os.geteuid() != 0:
        if not shutil.which("sudo") or subprocess.run(
                ["sudo", "-n", "true"], capture_output=True).returncode != 0:
            return None, "passwordless sudo unavailable"
        prefix = ["sudo", "-n", "--"]
    else:
        prefix = []
    launcher, helper = os.path.join(tmp, "runas.py"), os.path.join(tmp, "actor.py")
    for path, body in ((launcher, LAUNCHER_PY), (helper, HELPER_PY)):
        with open(path, "w") as f:
            f.write(body)
        os.chmod(path, 0o755)
    ids = Ids(prefix, launcher, helper)

    # Proof of real separation, ordinary files only: the App must be able to
    # create in a root-provided App-owned dir, the created inode must belong to
    # the App, and an unrelated root-owned 0600 file must stay unwritable.
    probe = mdir(tmp, "identity-probe", 0o755)
    ids.act(0, 0, "chown", probe, APP_UID, APP_GID)
    handed = ids.act(0, 0, "stat", probe)
    if (handed.get("owner"), handed.get("group")) != (APP_UID, APP_GID):
        return ids, "cannot hand the probe dir to the App: %s" % handed
    owned = os.path.join(probe, "owned.txt")
    secret = os.path.join(mdir(tmp, "identity-deny", 0o755), "secret.txt")
    ids.act(0, 0, "mkfile", secret, "0600", "secret\n")

    who = ids.act(APP_UID, APP_GID, "whoami")
    if (who.get("uid"), who.get("gid")) != (APP_UID, APP_GID):
        return ids, "cannot take uid %d/gid %d: %s" % (APP_UID, APP_GID, who)
    made = ids.act(APP_UID, APP_GID, "mkfile", owned, "0644", "x")
    if not made.get("ok") or not os.path.exists(owned) or os.stat(owned).st_uid != APP_UID:
        return ids, "uid switch is emulated (created file is not owned by %d, %s)" % (APP_UID, made)
    refused = ids.act(APP_UID, APP_GID, "write", secret, "x")
    if refused.get("ok"):
        return ids, "uid switch is emulated (App appended to another uid's 0600 file)"
    if ids.act(0, 0, "whoami").get("uid") != 0:
        return ids, "cannot act as uid 0"
    ids.proven = True
    return ids, "uid/gid separation proven (%s)" % ("sudo -n" if prefix else "direct root")


def identity_cases(r, idn, bin, base):
    def st(path):
        return idn.act(0, 0, "stat", path)

    def app_root(name, uid, gid):
        path = mdir(base, name, 0o755)
        idn.act(0, 0, "chown", path, uid, gid)
        got = st(path)
        return path, (got.get("owner"), got.get("group")) == (uid, gid)

    root, owned = app_root("app-root", APP_UID, APP_GID)
    rc, raw = idn.prepare(bin, root, APP_UID, APP_GID)
    r.check("identity: App-owned root, prepare by root returns 0", owned and rc == 0, raw)

    note = os.path.join(root, "app-note.txt")
    r.check("identity: App creates a file, root reads and appends, App reads back",
            idn.act(APP_UID, APP_GID, "mkfile", note, "0644", "from-app\n").get("ok")
            and idn.act(0, 0, "read", note).get("text") == "from-app\n"
            and idn.act(0, 0, "write", note, "from-root\n").get("ok")
            and "from-root" in idn.act(APP_UID, APP_GID, "read", note).get("text", ""))

    t1 = os.path.join(root, "tasks", "t1")
    idn.act(0, 0, "mkdir", os.path.join(root, "tasks"), "0777")
    idn.act(0, 0, "mkdir", t1, "0777")
    inside = os.path.join(t1, "app.txt")
    r.check("identity: root-created task dir inherits the App entry, App writes inside",
            acl(t1) == want_acl(0o755, APP_UID)
            and idn.act(APP_UID, APP_GID, "mkfile", inside, "0644", "nested\n").get("ok")
            and os.stat(inside).st_uid == APP_UID
            and idn.act(0, 0, "read", inside).get("text") == "nested\n", acl(t1))

    shared_file = os.path.join(t1, "root-file.txt")
    made = idn.act(0, 0, "mkfile", shared_file, "0666", "from-root\n")
    ino = os.stat(shared_file).st_ino if os.path.exists(shared_file) else -1
    seen = idn.act(APP_UID, APP_GID, "read", shared_file)
    appended = idn.act(APP_UID, APP_GID, "write", shared_file, "from-app\n")
    ino_append = os.stat(shared_file).st_ino if os.path.exists(shared_file) else -2
    rewrote = idn.act(APP_UID, APP_GID, "overwrite", shared_file, "app-rewrite\n")
    ino_rewrite = os.stat(shared_file).st_ino if os.path.exists(shared_file) else -3
    back = idn.act(0, 0, "read", shared_file)
    r.check("identity: root-created 0666 file is read/appended/overwritten by the App in place",
            made.get("ok") and os.path.exists(shared_file) and mode_of(shared_file) == 0o664
            and seen.get("text") == "from-root\n" and appended.get("ok")
            and rewrote.get("ok") and ino == ino_append == ino_rewrite
            and back.get("text") == "app-rewrite\n",
            "%s | %s | ino %s/%s/%s | %s" % (made, seen, ino, ino_append, ino_rewrite, back))

    priv, pdir = os.path.join(root, "private.txt"), os.path.join(root, "private-dir")
    idn.act(APP_UID, APP_GID, "mkfile", priv, "0600", "secret\n")
    idn.act(APP_UID, APP_GID, "mkdir", pdir, "0700")
    r.check("identity: App 0600 file / 0700 dir kept exactly",
            st(priv).get("mode") == 0o600 and st(pdir).get("mode") == 0o700,
            "%s / %s" % (st(priv), st(pdir)))
    idn.act(APP_UID, APP_GID, "chmod", priv, "0644")
    r.check("identity: explicit chmod 0644 respected", st(priv).get("mode") == 0o644, st(priv))

    alt, alt_owned = app_root("alt-root", ALT_UID, ALT_GID)
    rc2, raw2 = idn.prepare(bin, alt, ALT_UID, ALT_GID)
    alt_file = os.path.join(alt, "tasks", "alt.txt")
    idn.act(0, 0, "mkdir", os.path.join(alt, "tasks"), "0777")
    r.check("identity: second App uid/gid pair prepared and writable",
            alt_owned and rc2 == 0 and acl(alt) == want_acl(0o755, ALT_UID)
            and idn.act(ALT_UID, ALT_GID, "mkfile", alt_file, "0644", "alt\n").get("ok")
            and (st(alt_file).get("owner"), st(alt_file).get("group")) == (ALT_UID, ALT_GID),
            "%s | %s" % (raw2, acl(alt)))

    par = os.path.join(root, "parallel")
    idn.act(0, 0, "mkdir", par, "0777")
    errors = []

    def shell_dir(i):
        if not idn.act(0, 0, "mkdir", os.path.join(par, "p%d" % i), "0777").get("ok"):
            errors.append("mkdir p%d" % i)

    def app_write(i):
        if not idn.act(APP_UID, APP_GID, "mkfile", os.path.join(par, "p%d" % i, "f.txt"),
                       "0644", "task %d\n" % i).get("ok"):
            errors.append("write p%d" % i)

    for fn in (shell_dir, app_write):
        threads = [threading.Thread(target=fn, args=(i,)) for i in range(4)]
        for t in threads:
            t.start()
        for t in threads:
            t.join(120)
    r.check("identity: 4 concurrent task dirs, all App-writable",
            not errors and all(acl(os.path.join(par, "p%d" % i)) == want_acl(0o755, APP_UID)
                               and os.stat(os.path.join(par, "p%d" % i, "f.txt")).st_uid == APP_UID
                               for i in range(4)), errors)


# --------------------------------------------------------------------------

def cleanup(tmp, ids, keep):
    """Remove only this run's random tree; root-owned entries need the root actor."""
    if keep:
        return True
    if not os.path.basename(tmp).startswith("minis-shared-dirs-"):
        print("[WARN] refusing to remove unexpected path %s" % tmp)
        return False
    if ids is not None:
        ids.act(0, 0, "rmtree", tmp)
        if not os.path.exists(tmp):
            return True
        print("[WARN] root cleanup left %s behind" % tmp)
        return False
    try:
        shutil.rmtree(tmp)
    except OSError as e:
        print("[WARN] cleanup of %s failed: %s" % (tmp, e))
        return False
    return True


def run(args, tmp, state):
    os.makedirs(tmp, exist_ok=True)
    os.chmod(tmp, 0o755)
    base = os.path.join(tmp, "fs")
    os.makedirs(base, exist_ok=True)
    os.umask(0o022)
    print("[INFO] header=%s\n[INFO] euid=%d egid=%d tree=%s"
          % (HEADER, os.geteuid(), os.getegid(), tmp))
    bin = build(tmp, args.cc)
    if bin is None:
        return 2
    try:
        os.setxattr(mdir(base, "acl-support", 0o755), "system.posix_acl_default",
                    enc([(UOBJ, 7, NO_ID), (GOBJ, 5, NO_ID), (OTH, 5, NO_ID)]))
    except OSError as e:
        print("[FAIL] no POSIX default ACL support here: %d %s" % (e.errno, e.strerror))
        return 3
    r = Report()
    r.case("local ACL/mode layer",
           lambda rep: local_cases(rep, bin, base, os.geteuid(), os.getegid()))
    state["ids"], why = cloud_identities(tmp)
    ids = state["ids"]
    if ids is None or not ids.proven:
        r.skip("identity layer", why)
    else:
        print("[INFO] identity transport: %s" % why)
        r.case("identity layer", lambda rep: identity_cases(rep, ids, bin, base))
    print("\nSUMMARY: %d passed, %d failed, %d unverified"
          % (r.ok, r.bad, len(r.unverified)))
    if r.bad:
        print("RESULT: FAILURES PRESENT")
        return 1
    if args.require_identities and r.unverified:
        print("RESULT: identities required but not available")
        return 1
    print("RESULT: %s" % ("all checked cases passed" if not r.unverified
                          else "all local cases passed; identity layer unverified"))
    return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--cc", default=os.environ.get("CC", "gcc"))
    ap.add_argument("--keep", default=None, help="keep the fixture tree here")
    ap.add_argument("--require-identities", action="store_true",
                    help="fail unless the cloud identity layer really ran")
    args = ap.parse_args()
    if not os.path.exists(HEADER):
        print("[FAIL] header not found: %s" % HEADER)
        return 2
    tmp = args.keep or tempfile.mkdtemp(prefix="minis-shared-dirs-")
    state = {"ids": None}
    code = 4
    try:
        code = run(args, tmp, state)
    finally:
        if not cleanup(tmp, state["ids"], bool(args.keep)):
            print("[WARN] temporary tree not removed: %s" % tmp)
            if code == 0:
                code = 4
    return code


if __name__ == "__main__":
    sys.exit(main())
