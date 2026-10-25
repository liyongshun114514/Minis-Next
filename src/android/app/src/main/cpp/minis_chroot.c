/*
 * minis_chroot — privileged helper + unprivileged client for the Minis chroot
 * execution backend (FEAT-261025-CHROOT).  Android/bionic source.
 *
 * BUILD (main agent integrates; this file must not be added to the shared
 * CMakeLists by the backend patch):
 *
 *   add_library(minis-chroot SHARED minis_chroot.c)
 *   target_link_libraries(minis-chroot log)
 *   # packaged as libminis-chroot.so in jniLibs -> nativeLibraryDir
 *
 * Two modes in one binary, because both sides must agree byte-for-byte on the
 * wire format:
 *
 *   libminis-chroot.so --serve --root <rootfs> --socket <name> \
 *       --uid <appUid> --gid <appGid> --idle-ttl <sec> --state <dir> \
 *       --allow-root <dir> [--allow-root <dir> ...]
 *
 *       Started ONCE per app run through the Minis root bridge (sorts under
 *       user confirmation). Binds an abstract unix socket, prints
 *       "MINIS-CHROOT-READY <generation> <pid>" on stdout, then detaches.
 *       Every request is served as the APP uid inside a private mount
 *       namespace. It never runs a user command as root.
 *
 *   libminis-chroot.so --client --socket <name> --root <rootfs> --cwd <dir> \
 *       [--tty] [--login] [--mount guest=host | --ro-mount guest=host]... \
 *       [--control stop] -- <argv...>
 *
 *       Unprivileged. Passes its own stdio fds (SCM_RIGHTS) to the server and
 *       exits with the command's real status, so ProcessBuilder / PTY / Stop /
 *       timeouts behave exactly as they do under PRoot.
 *
 * SAFETY PROPERTIES (deliberate, see docs/android-chroot.md):
 *   - no SELinux change, no remount outside the private namespace, no write
 *     to any real filesystem except the rootfs's own mount points;
 *   - the bind list is checked against --allow-root in BOTH Kotlin and here;
 *   - `--` separates options from the command, and the command is never
 *     passed to su or a shell of the host;
 *   - the client's fds are dup2'ed into the child, so the host's pipe dies
 *     with it and `Stop` (SIGKILL on the client) reaches the child's group.
 */

#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <grp.h>
#include <limits.h>
#include <poll.h>
#include <signal.h>
#include <stdarg.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mount.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sched.h>
#include <sys/sysmacros.h>
#include <sys/types.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define PROTO_MAGIC   0x5248434dU /* 'MCHR' LE */
#define PROTO_VERSION 1U
#define KIND_EXEC 0U
#define KIND_STOP 1U
#define FLAG_TTY  1U
#define FLAG_LOGIN 2U
#define MAX_STR (1U << 20)
#define MAX_ARGV 256U
#define MAX_ENV 4096U
#define MAX_MOUNTS 64U

#define EXIT_NO_HELPER 97
#define EXIT_REJECTED  98

struct str { char *p; size_t n; };
struct mount_req { struct str guest, host; int ro; };

static void say(const char *fmt, ...) __attribute__((format(printf, 1, 2)));
static void say(const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    vfprintf(stderr, fmt, ap);
    va_end(ap);
}

static int write_full(int fd, const void *buf, size_t n) {
    const char *p = buf;
    while (n) {
        ssize_t w = write(fd, p, n);
        if (w < 0) { if (errno == EINTR) continue; return -1; }
        p += w; n -= (size_t)w;
    }
    return 0;
}

static int read_full(int fd, void *buf, size_t n) {
    char *p = buf;
    while (n) {
        ssize_t r = read(fd, p, n);
        if (r == 0) return 1;              /* EOF */
        if (r < 0) { if (errno == EINTR) continue; return -1; }
        p += r; n -= (size_t)r;
    }
    return 0;
}

static int put_u32(int fd, uint32_t v) { return write_full(fd, &v, 4); }

static int get_u32(int fd, uint32_t *v) {
    int rc = read_full(fd, v, 4);
    return rc;
}

static int put_str(int fd, const char *s) {
    uint32_t n = (uint32_t)strlen(s);
    if (put_u32(fd, n) < 0) return -1;
    return n ? write_full(fd, s, n) : 0;
}

static int get_str(int fd, char **out) {
    uint32_t n = 0;
    if (get_u32(fd, &n) != 0) return -1;
    if (n > MAX_STR) return -1;
    char *s = calloc(1, (size_t)n + 1);
    if (!s) return -1;
    if (n && read_full(fd, s, n) != 0) { free(s); return -1; }
    /* A NUL inside the frame would truncate a path at validation time while
     * the following bytes stayed part of the wire message. Refuse it. */
    if (n != strlen(s)) { free(s); return -1; }
    *out = s;
    return 0;
}

/* Absolute, no "..", no control chars, no ':' (option smuggling). */
static int path_ok(const char *p, int allow_root) {
    if (!p || p[0] != '/') return 0;
    if (!allow_root && (p[1] == '\0')) return 0;
    for (const unsigned char *c = (const unsigned char *)p; *c; c++) {
        if (*c < 0x20 || *c == 0x7f || *c == ':') return 0;
    }
    char buf[PATH_MAX];
    if (strlen(p) >= sizeof(buf)) return 0;
    strcpy(buf, p);
    char *save = NULL;
    for (char *tok = strtok_r(buf, "/", &save); tok; tok = strtok_r(NULL, "/", &save)) {
        if (strcmp(tok, "..") == 0) return 0;
    }
    return 1;
}

static int under_root(const char *path, const char *root) {
    size_t n = strlen(root);
    if (strcmp(path, root) == 0) return 1;
    if (strncmp(path, root, n) != 0) return 0;
    return path[n] == '/' || (n > 0 && root[n - 1] == '/');
}

struct allow_roots { const char *v[16]; int n; };

static int allowed_host(const struct allow_roots *a, const char *p) {
    char real[PATH_MAX];
    if (!realpath(p, real)) return 0;
    for (int i = 0; i < a->n; i++) {
        char rr[PATH_MAX];
        if (!realpath(a->v[i], rr)) continue;
        if (under_root(real, rr)) return 1;
    }
    return 0;
}

/* ───────────────────────────── request ───────────────────────────── */

struct request {
    uint32_t kind, flags, uid, gid;
    char *cwd;
    char **argv; uint32_t argc;
    char **envp; uint32_t envc;
    struct mount_req mounts[MAX_MOUNTS]; uint32_t nmount;
};

static void free_request(struct request *r) {
    free(r->cwd);
    for (uint32_t i = 0; i < r->argc; i++) free(r->argv[i]);
    free(r->argv);
    for (uint32_t i = 0; i < r->envc; i++) free(r->envp[i]);
    free(r->envp);
    for (uint32_t i = 0; i < r->nmount; i++) { free(r->mounts[i].guest.p); free(r->mounts[i].host.p); }
}

static int read_request(int fd, struct request *r) {
    memset(r, 0, sizeof(*r));
    uint32_t magic = 0, ver = 0;
    if (get_u32(fd, &magic) || get_u32(fd, &ver)) return -1;
    if (magic != PROTO_MAGIC || ver != PROTO_VERSION) return -1;
    if (get_u32(fd, &r->kind) || get_u32(fd, &r->flags) ||
        get_u32(fd, &r->uid) || get_u32(fd, &r->gid)) return -1;
    if (get_str(fd, &r->cwd)) return -1;
    if (get_u32(fd, &r->argc) || r->argc > MAX_ARGV) return -1;
    /* argc + 1: execvpe() requires a NULL terminator. A calloc failure must be
     * an error, not a NULL pointer that exec would treat as an empty argv. */
    r->argv = calloc((size_t)r->argc + 1, sizeof(char *));
    if (!r->argv) return -1;
    for (uint32_t i = 0; i < r->argc; i++) if (get_str(fd, &r->argv[i])) return -1;
    if (get_u32(fd, &r->envc) || r->envc > MAX_ENV) return -1;
    r->envp = calloc((size_t)r->envc + 1, sizeof(char *));
    if (!r->envp) return -1;
    for (uint32_t i = 0; i < r->envc; i++) if (get_str(fd, &r->envp[i])) return -1;
    if (get_u32(fd, &r->nmount) || r->nmount > MAX_MOUNTS) return -1;
    for (uint32_t i = 0; i < r->nmount; i++) {
        uint32_t ro = 0;
        if (get_str(fd, &r->mounts[i].guest.p) || get_str(fd, &r->mounts[i].host.p) ||
            get_u32(fd, &ro)) return -1;
        r->mounts[i].guest.n = strlen(r->mounts[i].guest.p);
        r->mounts[i].host.n = strlen(r->mounts[i].host.p);
        r->mounts[i].ro = ro ? 1 : 0;
    }
    return 0;
}

/* Receive 3 payload bytes + the client's stdio fds in one sendmsg(). */
static int recv_fds(int fd, int out[3]) {
    char byte = 0;
    struct iovec iov = { .iov_base = &byte, .iov_len = 1 };
    char cbuf[CMSG_SPACE(sizeof(int) * 3)];
    struct msghdr msg = {0};
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = cbuf;
    msg.msg_controllen = sizeof(cbuf);
    ssize_t n;
    do { n = recvmsg(fd, &msg, 0); } while (n < 0 && errno == EINTR);
    if (n <= 0) return -1;
    out[0] = out[1] = out[2] = -1;
    int idx = 0;
    for (struct cmsghdr *c = CMSG_FIRSTHDR(&msg); c; c = CMSG_NXTHDR(&msg, c)) {
        if (c->cmsg_level != SOL_SOCKET || c->cmsg_type != SCM_RIGHTS) continue;
        size_t bytes = c->cmsg_len - CMSG_LEN(0);
        int *fds = (int *)CMSG_DATA(c);
        for (size_t i = 0; i < bytes / sizeof(int) && idx < 3; i++) out[idx++] = fds[i];
    }
    return idx == 3 ? 0 : -1;
}

/* ─────────────────────────── namespaces ─────────────────────────── */

static int bind_one(const char *src, const char *dst, int ro) {
    if (access(dst, F_OK) != 0) return -1;
    if (mount(src, dst, NULL, MS_BIND | MS_REC, NULL) != 0) return -1;
    if (ro) {
        if (mount(NULL, dst, NULL, MS_BIND | MS_REMOUNT | MS_RDONLY, NULL) != 0) return -2;
    }
    return 0;
}

static int bind_dev_node(const char *rootfs, const char *name) {
    char dst[PATH_MAX], src[PATH_MAX];
    snprintf(dst, sizeof(dst), "%s/dev/%s", rootfs, name);
    snprintf(src, sizeof(src), "/dev/%s", name);
    int fd = open(dst, O_CREAT | O_RDWR | O_CLOEXEC, 0666);
    if (fd < 0) return -1;
    close(fd);
    return bind_one(src, dst, 0);
}

/*
 * Minimal /dev: tmpfs + four device nodes + devpts. Binds individual nodes
 * instead of the whole host /dev so nothing else is exposed.
 */
static int setup_dev(const char *rootfs) {
    char dev[PATH_MAX], pts[PATH_MAX];
    snprintf(dev, sizeof(dev), "%s/dev", rootfs);
    snprintf(pts, sizeof(pts), "%s/dev/pts", rootfs);
    mkdir(dev, 0755);
    if (mount("tmpfs", dev, "tmpfs", MS_NOSUID | MS_STRICTATIME, "mode=755") != 0) return -1;
    const char *nodes[] = { "null", "zero", "full", "random", "urandom", "tty" };
    for (size_t i = 0; i < sizeof(nodes) / sizeof(nodes[0]); i++) bind_dev_node(rootfs, nodes[i]);
    mkdir(pts, 0755);
    if (mount("devpts", pts, "devpts", MS_NOSUID | MS_NOEXEC | MS_RDONLY,
              "newinstance,ptmxmode=0666,mode=0620") != 0) {
        /* Older kernels: fall back to a plain bind of the host devpts. */
        if (mount("/dev/pts", pts, NULL, MS_BIND | MS_REC, NULL) != 0) {
            say("minis-chroot: devpts unavailable: %s\n", strerror(errno));
        }
    }
    char ptmx[PATH_MAX], target[PATH_MAX];
    snprintf(ptmx, sizeof(ptmx), "%s/dev/ptmx", rootfs);
    snprintf(target, sizeof(target), "pts/ptmx");
    unlink(ptmx);
    if (symlink(target, ptmx) != 0 && errno != EEXIST) {
        say("minis-chroot: /dev/ptmx symlink failed: %s\n", strerror(errno));
    }
    return 0;
}

/*
 * Runs in the forked child, as the last root code before exec.
 * Every mount is private to this process's namespace (unshare(CLONE_NEWNS)
 * by the caller), so nothing here can leak to the rest of the device.
 */
static int enter_sandbox(const char *rootfs, const struct request *r,
                         const struct allow_roots *allow) {
    if (mount(NULL, "/", NULL, MS_REC | MS_PRIVATE, NULL) != 0) {
        say("minis-chroot: cannot make / private: %s\n", strerror(errno));
        return -1;
    }
    char proc[PATH_MAX], sys[PATH_MAX];
    snprintf(proc, sizeof(proc), "%s/proc", rootfs);
    snprintf(sys, sizeof(sys), "%s/sys", rootfs);
    mkdir(proc, 0555);
    if (mount("proc", proc, "proc", MS_NOSUID | MS_NODEV | MS_NOEXEC, NULL) != 0) {
        say("minis-chroot: proc mount failed: %s\n", strerror(errno));
        return -1;
    }
    setup_dev(rootfs);
    mkdir(sys, 0555);
    if (mount("/sys", sys, NULL, MS_BIND | MS_REC, NULL) == 0) {
        mount(NULL, sys, NULL, MS_BIND | MS_REMOUNT | MS_RDONLY | MS_REC, NULL);
    } else {
        say("minis-chroot: /sys bind failed: %s\n", strerror(errno));
    }

    for (uint32_t i = 0; i < r->nmount; i++) {
        const struct mount_req *m = &r->mounts[i];
        /* Second gate: Kotlin already filtered; a compromised client does not
         * get a second chance here. */
        if (!path_ok(m->guest.p, 0) || !path_ok(m->host.p, 1) ||
            !allowed_host(allow, m->host.p)) {
            say("minis-chroot: mount rejected: %s -> %s\n", m->guest.p, m->host.p);
            continue;
        }
        char dst[PATH_MAX], real_root[PATH_MAX], real_dst[PATH_MAX];
        snprintf(dst, sizeof(dst), "%s%s", rootfs, m->guest.p);
        if (!realpath(rootfs, real_root)) {
            say("minis-chroot: rootfs disappeared\n");
            return -1;
        }
        /* A symlink inside the rootfs (user-writable!) could point at /data or
         * /system; bind-mounting through it would place a mount OUTSIDE the
         * rootfs, on the host. lstat first, then require the resolved path to
         * still be inside the rootfs. */
        struct stat st;
        if (lstat(dst, &st) != 0) {
            say("minis-chroot: mount target missing: %s\n", dst);
            continue;
        }
        if (S_ISLNK(st.st_mode)) {
            say("minis-chroot: refusing symlink mount target: %s\n", dst);
            continue;
        }
        if (!(S_ISDIR(st.st_mode) || S_ISCHR(st.st_mode))) {
            say("minis-chroot: refusing non-directory mount target: %s\n", dst);
            continue;
        }
        if (!realpath(dst, real_dst) || !under_root(real_dst, real_root)) {
            say("minis-chroot: mount target escapes the rootfs: %s\n", dst);
            continue;
        }
        if (bind_one(m->host.p, dst, m->ro) != 0) {
            say("minis-chroot: bind failed %s -> %s: %s\n", m->host.p, dst, strerror(errno));
        }
    }

    if (chdir(rootfs) != 0 || chroot(".") != 0) {
        say("minis-chroot: chroot failed: %s\n", strerror(errno));
        return -1;
    }
    if (chdir(r->cwd && r->cwd[0] ? r->cwd : "/root") != 0) {
        say("minis-chroot: chdir %s failed: %s\n", r->cwd, strerror(errno));
        if (chdir("/") != 0) return -1;
    }
    umask(022);
    return 0;
}

/* ───────────────────────────── server ───────────────────────────── */

/*
 * SO_PEERCRED: the only client allowed is this app's own uid.
 *
 * Abstract sockets have no filesystem permissions, so without this any app on
 * the device could drive the helper. The client runs unprivileged (and the
 * helper drops its children to the same uid), so the check is exactly
 * "peer uid == --uid".
 */
static int peer_uid(int conn) {
    struct ucred cr;
    socklen_t len = sizeof(cr);
    if (getsockopt(conn, SOL_SOCKET, SO_PEERCRED, &cr, &len) != 0) return -1;
    return (int)cr.uid;
}

struct server {
    const char *rootfs, *socket_name, *state_dir;
    int uid, gid, idle_ttl;
    struct allow_roots allow;
    int listen_fd;
};

static volatile sig_atomic_t g_stop = 0;
static void on_signal(int sig) { (void)sig; g_stop = 1; }

static void reap_children(void) {
    int st;
    while (waitpid(-1, &st, WNOHANG) > 0) { /* best effort */ }
}

static void kill_group(pid_t pid, pid_t pgid) {
    if (pgid > 1) kill(-pgid, SIGKILL);
    if (pid > 1) kill(pid, SIGKILL);
}

static void serve_one(struct server *s, int conn, pid_t server_pid) {
    int fds[3];
    struct request r;

    /* [security] Abstract sockets are device-global; only our own app uid may
     * talk to the helper. Without this, any installed app could ask a root
     * process to chroot+exec for it. */
    int peer = peer_uid(conn);
    if (peer != s->uid) {
        say("minis-chroot: refused client uid=%d (expected %d)\n", peer, s->uid);
        close(conn);
        return;
    }

    if (recv_fds(conn, fds) != 0) { close(conn); return; }
    if (read_request(conn, &r) != 0) {
        say("minis-chroot: bad request frame\n");
        for (int i = 0; i < 3; i++) close(fds[i]);
        close(conn);
        return;
    }
    if (r.kind == KIND_STOP) {
        /* This runs in a per-connection handler child, so `g_stop` here would
         * stop nothing. Signal the process that OWNS the listening socket. */
        say("minis-chroot: stop requested\n");
        put_u32(conn, 0);
        close(conn);
        free_request(&r);
        if (server_pid > 1) kill(server_pid, SIGTERM);
        return;
    }
    /* The client must not be able to ask for another identity. */
    if ((int)r.uid != s->uid || (int)r.gid != s->gid || r.argc == 0) {
        say("minis-chroot: request rejected (uid/gid/argv)\n");
        for (int i = 0; i < 3; i++) close(fds[i]);
        put_u32(conn, (uint32_t)EXIT_REJECTED);
        close(conn);
        free_request(&r);
        return;
    }

    pid_t pid = fork();
    if (pid == 0) {
        /* child */
        setsid();
        dup2(fds[0], 0); dup2(fds[1], 1); dup2(fds[2], 2);
        for (int i = 0; i < 3; i++) if (fds[i] > 2) close(fds[i]);
        close(conn);
        if (unshare(CLONE_NEWNS) != 0) {
            say("minis-chroot: unshare(CLONE_NEWNS): %s\n", strerror(errno));
            _exit(126);
        }
        if (enter_sandbox(s->rootfs, &r, &s->allow) != 0) _exit(126);
        if (setgroups(0, NULL) != 0) { /* root-only; ignore failure */ }
        if (setgid((gid_t)r.gid) != 0 || setuid((uid_t)r.uid) != 0) {
            say("minis-chroot: drop to %u:%u failed: %s\n", r.uid, r.gid, strerror(errno));
            _exit(126);
        }
        execvpe(r.argv[0], r.argv, r.envp);
        /* The guest shell may be a busybox predating execvpe; retry PATH walk. */
        execvp(r.argv[0], r.argv);
        say("minis-chroot: execve %s failed: %s\n", r.argv[0], strerror(errno));
        _exit(127);
    }
    for (int i = 0; i < 3; i++) close(fds[i]);
    free_request(&r);
    if (pid < 0) {
        say("minis-chroot: fork failed: %s\n", strerror(errno));
        put_u32(conn, 126);
        close(conn);
        return;
    }
    /* The child is its own group leader (setsid), so a dead client kills the
     * whole command tree, including anything it forked. */
    int status = 0;
    for (;;) {
        pid_t w = waitpid(pid, &status, WNOHANG);
        if (w == pid) break;
        if (w < 0) break;
        struct pollfd p = { .fd = conn, .events = POLLIN | POLLHUP };
        int pr = poll(&p, 1, 200);
        if (pr > 0 && (p.revents & (POLLHUP | POLLERR | POLLIN))) {
            char tmp;
            ssize_t n = recv(conn, &tmp, 1, MSG_PEEK | MSG_DONTWAIT);
            if (n == 0 || (n < 0 && errno != EAGAIN && errno != EINTR)) {
                kill_group(pid, pid);
                waitpid(pid, &status, 0);
                break;
            }
        }
        if (g_stop) { kill_group(pid, pid); waitpid(pid, &status, 0); break; }
    }
    int code = WIFEXITED(status) ? WEXITSTATUS(status) : (WIFSIGNALED(status) ? 128 + WTERMSIG(status) : 126);
    put_u32(conn, (uint32_t)code);
    close(conn);
}

static int run_server(struct server *s) {
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) { say("minis-chroot: socket: %s\n", strerror(errno)); return 1; }
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    /* Abstract namespace: leading NUL, then the name. Android's abstract
     * sockets are not filesystem objects, so nothing is left behind. */
    size_t name_len = strlen(s->socket_name);
    if (name_len + 1 > sizeof(addr.sun_path)) { say("minis-chroot: socket name too long\n"); return 1; }
    addr.sun_path[0] = '\0';
    memcpy(addr.sun_path + 1, s->socket_name, name_len);
    socklen_t alen = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + name_len);
    if (bind(fd, (struct sockaddr *)&addr, alen) != 0) {
        say("minis-chroot: bind @%s: %s\n", s->socket_name, strerror(errno));
        return 1;
    }
    if (listen(fd, 8) != 0) { say("minis-chroot: listen: %s\n", strerror(errno)); return 1; }
    s->listen_fd = fd;

    /* Detach: the root bridge's capture (and the user's dialog) must return
     * immediately once the socket exists. The line names the SOCKET (which
     * contains the generation nonce) and the DAEMON's pid, so the Kotlin side
     * accepts this process and not a stale one. */
    pid_t daemon = fork();
    if (daemon < 0) {
        say("minis-chroot: daemon fork failed: %s\n", strerror(errno));
        close(fd);
        return 1;
    }
    if (daemon != 0) {
        printf("MINIS-CHROOT-READY %s %d\n", s->socket_name, (int)daemon);
        fflush(stdout);
        close(fd);           /* the child owns the listening socket now */
        return 0;
    }
    setsid();
    /* All three stdio ends are detached: fd 0 from /dev/null, 1/2 to a log in
     * the backend's state dir (or /dev/null when --state was not given). */
    int devnull = open("/dev/null", O_RDWR);
    if (devnull >= 0) { dup2(devnull, 0); dup2(devnull, 1); if (devnull > 2) close(devnull); }
    if (s->state_dir) {
        char logpath[PATH_MAX];
        snprintf(logpath, sizeof(logpath), "%s/helper.log", s->state_dir);
        int logfd = open(logpath, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0600);
        if (logfd >= 0) { dup2(logfd, 1); dup2(logfd, 2); if (logfd > 2) close(logfd); }
    }
    chdir("/");

    if (s->state_dir) {
        char pidfile[PATH_MAX];
        snprintf(pidfile, sizeof(pidfile), "%s/helper.pid", s->state_dir);
        FILE *f = fopen(pidfile, "w");
        if (f) { fprintf(f, "%d\n", (int)getpid()); fclose(f); }
    }

    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = on_signal;
    sigaction(SIGTERM, &sa, NULL);
    sigaction(SIGINT, &sa, NULL);
    /* SIGCHLD stays at its default: SIG_IGN would make waitpid() fail with
     * ECHILD and every command would look like a success. */
    signal(SIGPIPE, SIG_IGN);

    pid_t self = getpid();
    time_t last = time(NULL);
    while (!g_stop) {
        struct pollfd p = { .fd = fd, .events = POLLIN };
        int pr = poll(&p, 1, 1000);
        if (pr > 0 && (p.revents & POLLIN)) {
            int conn = accept4(fd, NULL, NULL, SOCK_CLOEXEC);
            if (conn >= 0) {
                /* One handler per connection: a long-lived interactive shell
                 * must not block every other command behind it. */
                pid_t h = fork();
                if (h == 0) {
                    close(fd);
                    serve_one(s, conn, self);
                    _exit(0);
                }
                if (h < 0) {
                    say("minis-chroot: handler fork failed: %s\n", strerror(errno));
                    close(conn);
                } else {
                    close(conn);
                }
                last = time(NULL);
                continue;
            }
        }
        reap_children();
        if (s->idle_ttl > 0 && time(NULL) - last > s->idle_ttl) {
            say("minis-chroot: idle ttl reached, exiting\n");
            break;
        }
    }
    close(fd);
    say("minis-chroot: server exiting\n");
    return 0;
}

/* ───────────────────────────── client ───────────────────────────── */

struct client_args {
    const char *socket_name, *rootfs, *cwd;
    int tty, login, control_stop;
    struct mount_req mounts[MAX_MOUNTS];
    uint32_t nmount;
    char **cmd; uint32_t cmd_n;
};

static int connect_socket(const char *name) {
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    size_t n = strlen(name);
    if (n + 1 > sizeof(addr.sun_path)) { close(fd); return -1; }
    addr.sun_path[0] = '\0';
    memcpy(addr.sun_path + 1, name, n);
    socklen_t alen = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + n);
    if (connect(fd, (struct sockaddr *)&addr, alen) != 0) { close(fd); return -1; }
    return fd;
}

static int run_client(struct client_args *a) {
    int conn = connect_socket(a->socket_name);
    if (conn < 0) {
        fprintf(stderr,
                "minis-chroot: the chroot helper is not running (socket @%s: %s).\n"
                "Open Settings → Shell container → Execution backend and press "
                "\"Enable chroot\" again; the previous grant does not survive a restart.\n",
                a->socket_name, strerror(errno));
        return EXIT_NO_HELPER;
    }

    /* Payload byte + our stdio fds: the server's child becomes the process
     * this host-side handle talks to, so exit status and pipes are exact. */
    char payload = 'F';
    struct iovec iov = { .iov_base = &payload, .iov_len = 1 };
    int fds[3] = { 0, 1, 2 };
    char cbuf[CMSG_SPACE(sizeof(fds))];
    memset(cbuf, 0, sizeof(cbuf));
    struct msghdr msg = {0};
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = cbuf;
    msg.msg_controllen = sizeof(cbuf);
    struct cmsghdr *c = CMSG_FIRSTHDR(&msg);
    c->cmsg_level = SOL_SOCKET;
    c->cmsg_type = SCM_RIGHTS;
    c->cmsg_len = CMSG_LEN(sizeof(fds));
    memcpy(CMSG_DATA(c), fds, sizeof(fds));
    ssize_t sent;
    do { sent = sendmsg(conn, &msg, 0); } while (sent < 0 && errno == EINTR);
    if (sent < 0) {
        fprintf(stderr, "minis-chroot: sendmsg failed: %s\n", strerror(errno));
        close(conn);
        return EXIT_REJECTED;
    }

    char **envp = NULL;
    uint32_t envc = 0;
    if (!a->control_stop) {
        extern char **environ;
        for (envp = environ; envp && envp[envc]; envc++) { /* count */ }
    }
    uint32_t flags = (a->tty ? FLAG_TTY : 0) | (a->login ? FLAG_LOGIN : 0);

    put_u32(conn, PROTO_MAGIC);
    put_u32(conn, PROTO_VERSION);
    put_u32(conn, a->control_stop ? KIND_STOP : KIND_EXEC);
    put_u32(conn, flags);
    put_u32(conn, (uint32_t)getuid());
    put_u32(conn, (uint32_t)getgid());
    put_str(conn, a->cwd ? a->cwd : "/root");
    if (a->control_stop) {
        put_u32(conn, 0);
        put_u32(conn, 0);
        put_u32(conn, 0);
    } else {
        put_u32(conn, a->cmd_n);
        for (uint32_t i = 0; i < a->cmd_n; i++) put_str(conn, a->cmd[i]);
        put_u32(conn, envc);
        for (uint32_t i = 0; i < envc; i++) put_str(conn, envp[i]);
        put_u32(conn, a->nmount);
        for (uint32_t i = 0; i < a->nmount; i++) {
            put_str(conn, a->mounts[i].guest.p);
            put_str(conn, a->mounts[i].host.p);
            put_u32(conn, a->mounts[i].ro ? 1U : 0U);
        }
    }

    /* The command's stdio is already ours; only the status frame comes back.
     * If the server dies, EOF here means "no status" — 126, not 0. */
    uint32_t status = 0;
    int rc = get_u32(conn, &status);
    close(conn);
    if (rc != 0) {
        fprintf(stderr, "minis-chroot: helper closed the connection without a status\n");
        return 126;
    }
    if (status == EXIT_REJECTED) {
        fprintf(stderr, "minis-chroot: helper rejected the request (see logcat)\n");
    }
    return (int)status;
}

/* ───────────────────────────── main ───────────────────────────── */

static void usage(void) {
    fprintf(stderr,
            "usage: libminis-chroot.so --serve --root DIR --socket NAME --uid N --gid N\n"
            "                            [--idle-ttl SEC] [--state DIR] [--allow-root DIR]...\n"
            "       libminis-chroot.so --client --socket NAME --root DIR [--cwd DIR]\n"
            "                            [--tty] [--login] [--mount g=h|--ro-mount g=h]...\n"
            "                            [--control stop] -- argv...\n");
}

int main(int argc, char **argv) {
    if (argc < 2) { usage(); return 2; }
    if (strcmp(argv[1], "--serve") == 0) {
        struct server s;
        memset(&s, 0, sizeof(s));
        s.uid = -1; s.gid = -1; s.idle_ttl = 900;
        for (int i = 2; i < argc; i++) {
            const char *a = argv[i];
            const char *v = (i + 1 < argc) ? argv[i + 1] : NULL;
            if (!v && strcmp(a, "--serve") != 0) { usage(); return 2; }
            if (!strcmp(a, "--root")) { s.rootfs = v; i++; }
            else if (!strcmp(a, "--socket")) { s.socket_name = v; i++; }
            else if (!strcmp(a, "--uid")) { s.uid = atoi(v); i++; }
            else if (!strcmp(a, "--gid")) { s.gid = atoi(v); i++; }
            else if (!strcmp(a, "--idle-ttl")) { s.idle_ttl = atoi(v); i++; }
            else if (!strcmp(a, "--state")) { s.state_dir = v; i++; }
            else if (!strcmp(a, "--allow-root")) {
                if (s.allow.n < (int)(sizeof(s.allow.v) / sizeof(s.allow.v[0]))) s.allow.v[s.allow.n++] = v;
                i++;
            } else { usage(); return 2; }
        }
        if (!s.rootfs || !s.socket_name || s.uid < 0 || s.gid < 0) { usage(); return 2; }
        if (geteuid() != 0) {
            say("minis-chroot: --serve requires root (uid=%d)\n", (int)geteuid());
            return 1;
        }
        if (!path_ok(s.rootfs, 1)) { say("minis-chroot: bad --root\n"); return 2; }
        return run_server(&s);
    }
    if (strcmp(argv[1], "--client") == 0) {
        struct client_args a;
        memset(&a, 0, sizeof(a));
        int i = 2;
        for (; i < argc; i++) {
            if (!strcmp(argv[i], "--")) { i++; break; }
            const char *a1 = argv[i];
            const char *v = (i + 1 < argc) ? argv[i + 1] : NULL;
            if (!strcmp(a1, "--socket") && v) { a.socket_name = v; i++; }
            else if (!strcmp(a1, "--root") && v) { a.rootfs = v; i++; }
            else if (!strcmp(a1, "--cwd") && v) { a.cwd = v; i++; }
            else if (!strcmp(a1, "--tty")) { a.tty = 1; }
            else if (!strcmp(a1, "--login")) { a.login = 1; }
            else if (!strcmp(a1, "--control") && v) { a.control_stop = !strcmp(v, "stop"); i++; }
            else if ((!strcmp(a1, "--mount") || !strcmp(a1, "--ro-mount")) && v) {
                if (a.nmount >= MAX_MOUNTS) { say("minis-chroot: too many mounts\n"); return 2; }
                char *eq = strchr(v, '=');
                if (!eq) { say("minis-chroot: bad --mount %s\n", v); return 2; }
                struct mount_req *m = &a.mounts[a.nmount++];
                m->guest.p = strndup(v, (size_t)(eq - v));
                m->host.p = strdup(eq + 1);
                m->ro = !strcmp(a1, "--ro-mount");
                i++;
            } else { usage(); return 2; }
        }
        if (!a.socket_name || !a.rootfs) { usage(); return 2; }
        a.cmd = &argv[i];
        a.cmd_n = (uint32_t)(argc - i);
        if (!a.control_stop && a.cmd_n == 0) { usage(); return 2; }
        /* The client refuses to smuggle an option-looking argv[0]. */
        if (!a.control_stop && a.cmd[0][0] == '-' && strcmp(a.cmd[0], "--") != 0) {
            say("minis-chroot: command expected after --\n");
            return 2;
        }
        return run_client(&a);
    }
    usage();
    return 2;
}
