/*
 * minis-offload-client — guest-side native_offload client for the CHROOT
 * backend (FEAT-261025-CHROOT).  Runs INSIDE the rootfs.
 *
 * Why it exists: under PRoot, `execve("/usr/local/bin/minis-config", ...)` is
 * intercepted by proot's native_offload extension and answered by the app.
 * With a real chroot there is no ptrace layer, so the stub would just print
 * "unavailable". This client speaks the SAME wire protocol to the SAME
 * NativeOffloadServer over the SAME abstract socket, as the app's own uid —
 * which is exactly what NativeOffloadServer's peer-uid check requires.
 *
 * [FEAT-261027-CHROOT-ROOT] When the guest command is a REAL uid 0, the
 * offload client still connects as the app uid: it switches euid only for the
 * connect(2), then restores uid 0. The server's check is unchanged (only the
 * app uid is accepted); the guest command itself never drops to the app uid.
 *
 * BUILD (main agent integrates; NOT part of the shared CMakeLists):
 *   aarch64-linux-gnu-gcc -static -O2 -s -o minis-offload-client \
 *       minis_offload_client.c
 *   # Ubuntu 24.04 rootfs is glibc: build against glibc, statically, so the
 *   # binary does not depend on the guest's libc version.
 *   # Install as assets/chroot/minis-offload-client; ChrootKernel copies it to
 *   # /usr/local/lib/minis/minis-offload-client at boot (0755).
 *
 * Wire format (must match NativeOffload.kt readRequest/writeReply):
 *   req: u32 magic 0x46464F4E, u32 version=1, u32 pid, u32 argc,
 *        argc*(u32 len+bytes), u32 envc, envc*(u32 len+bytes "K=V"), str cwd
 *   rsp: u32 magic 0x52464F4E, u32 exitCode, str guestTmpPath
 * All integers little-endian.
 *
 * On any failure it exits 69 (EX_UNAVAILABLE) with a message on stderr — the
 * same contract as HandlerStub, so a user never sees a silent success.
 */

#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/un.h>
#include <unistd.h>

#define MAGIC_REQ 0x46464F4EU
#define MAGIC_RSP 0x52464F4EU
#define VERSION   1U
#define EX_UNAVAILABLE 69
#define MAX_STR (1U << 20)

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
        if (r == 0) return 1;
        if (r < 0) { if (errno == EINTR) continue; return -1; }
        p += r; n -= (size_t)r;
    }
    return 0;
}

static int put_u32(int fd, uint32_t v) {
    unsigned char b[4] = { (unsigned char)(v), (unsigned char)(v >> 8),
                           (unsigned char)(v >> 16), (unsigned char)(v >> 24) };
    return write_full(fd, b, 4);
}

static int get_u32(int fd, uint32_t *v) {
    unsigned char b[4];
    if (read_full(fd, b, 4) != 0) return -1;
    *v = (uint32_t)b[0] | ((uint32_t)b[1] << 8) | ((uint32_t)b[2] << 16) | ((uint32_t)b[3] << 24);
    return 0;
}

static int put_str(int fd, const char *s) {
    size_t n = strlen(s);
    if (n > MAX_STR) return -1;
    if (put_u32(fd, (uint32_t)n) < 0) return -1;
    return n ? write_full(fd, s, n) : 0;
}

static int connect_abstract(const char *name) {
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    size_t n = strlen(name);
    if (n + 1 > sizeof(addr.sun_path)) { close(fd); return -1; }
    addr.sun_path[0] = '\0';
    memcpy(addr.sun_path + 1, name, n);
    socklen_t len = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + n);
    if (connect(fd, (struct sockaddr *)&addr, len) != 0) {
        int e = errno;
        close(fd);
        errno = e;
        return -1;
    }
    return fd;
}

static int parse_uid_env(const char *s, uid_t *out) {
    if (!s || !*s) return -1;
    char *end = NULL;
    errno = 0;
    unsigned long v = strtoul(s, &end, 10);
    if (errno != 0 || !end || *end != '\0' || v == 0 || v > 0x7fffffffUL) return -1;
    *out = (uid_t)v;
    return 0;
}

/*
 * Connect to NativeOffloadServer with the IPC TRANSFER identity only.
 *
 * A uid-0 guest command is intentional (apt/dpkg need real root), but
 * NativeOffloadServer deliberately accepts ONLY this app's uid: abstract
 * sockets have no filesystem permissions, so widening that check to "uid 0 is
 * fine too" would let any root process on the device drive contacts/photos/
 * model calls. Instead this client - and only this short connect step - uses
 * the app uid that ChrootKernel exported in MINIS_CHROOT_APP_UID, then
 * restores euid 0 before it does anything else. The guest command itself NEVER
 * runs with the app uid, and the server still performs its unchanged check.
 *
 * A non-uid0 caller ignores the variable: it already is the app uid. A uid0
 * caller with no valid variable fails loudly instead of connecting as 0.
 */
static int connect_as_app_identity(const char *name) {
    if (geteuid() != 0) return connect_abstract(name);
    uid_t app = 0;
    if (parse_uid_env(getenv("MINIS_CHROOT_APP_UID"), &app) != 0) {
        errno = EPERM;
        return -1;
    }
    if (seteuid(app) != 0) return -1;
    int fd = connect_abstract(name);
    int e = errno;
    if (seteuid(0) != 0) {
        fprintf(stderr,
                "minis-offload-client: cannot restore uid 0 after the IPC connect (%s)\n",
                strerror(errno));
        if (fd >= 0) close(fd);
        errno = EPERM;
        return -1;
    }
    errno = e;
    return fd;
}

int main(int argc, char **argv) {
    /* Wrapper invokes: client <original tool path> <tool arguments...>.
     * NativeOffloadServer dispatches by request argv[0], not this executable. */
    if (argc < 2) {
        fprintf(stderr, "usage: minis-offload-client TOOL [ARGS...]\n");
        return EX_UNAVAILABLE;
    }
    argc--;
    argv++;
    const char *sock = getenv("MINIS_NOFF_SOCKET");
    if (!sock || !*sock) {
        fprintf(stderr,
                "%.*s: unavailable - MINIS_NOFF_SOCKET is not set (the chroot backend "
                "did not export the offload socket).\n",
                (int)strcspn(argv[0], "\0"), argv[0]);
        return EX_UNAVAILABLE;
    }
    /* Handler name = basename(argv[0]); the server dispatches on it. */
    int fd = connect_as_app_identity(sock);
    if (fd < 0) {
        fprintf(stderr,
                "%.*s: unavailable - Minis offload service is not reachable (@%s: %s).\n",
                (int)strcspn(argv[0], "\0"), argv[0], sock, strerror(errno));
        return EX_UNAVAILABLE;
    }

    extern char **environ;
    uint32_t envc = 0;
    while (environ && environ[envc]) envc++;

    char cwd[4096];
    if (!getcwd(cwd, sizeof(cwd))) strcpy(cwd, "/");

    uint32_t pid = (uint32_t)getpid();
    int ok = put_u32(fd, MAGIC_REQ) == 0 &&
             put_u32(fd, VERSION) == 0 &&
             put_u32(fd, pid) == 0 &&
             put_u32(fd, (uint32_t)argc) == 0;
    for (int i = 0; ok && i < argc; i++) ok = put_str(fd, argv[i]) == 0;
    ok = ok && put_u32(fd, envc) == 0;
    for (uint32_t i = 0; ok && i < envc; i++) ok = put_str(fd, environ[i]) == 0;
    ok = ok && put_str(fd, cwd) == 0;
    if (!ok) {
        fprintf(stderr, "%s: unavailable - could not send the offload request\n", argv[0]);
        close(fd);
        return EX_UNAVAILABLE;
    }

    uint32_t magic = 0, code = 0, len = 0;
    if (get_u32(fd, &magic) != 0 || magic != MAGIC_RSP ||
        get_u32(fd, &code) != 0 || get_u32(fd, &len) != 0 || len > MAX_STR) {
        fprintf(stderr, "%s: unavailable - malformed reply from the Minis offload service\n", argv[0]);
        close(fd);
        return EX_UNAVAILABLE;
    }
    char *path = calloc(1, (size_t)len + 1);
    if (!path) { close(fd); return EX_UNAVAILABLE; }
    if (len && read_full(fd, path, len) != 0) {
        fprintf(stderr, "%s: unavailable - truncated reply\n", argv[0]);
        free(path);
        close(fd);
        return EX_UNAVAILABLE;
    }
    close(fd);

    /* The server wrote the handler's combined output to a file inside the
     * rootfs (/tmp/.native-offload-*), exactly as it does for proot's
     * extension, which then rewrites the execve into `cat <file>`. Same file,
     * same contract: copy it to stdout and keep the handler's exit code. */
    int in = open(path, O_RDONLY | O_CLOEXEC);
    if (in >= 0) {
        char buf[8192];
        ssize_t n;
        while ((n = read(in, buf, sizeof(buf))) > 0) {
            if (write_full(STDOUT_FILENO, buf, (size_t)n) != 0) break;
        }
        close(in);
        unlink(path);   /* ours now; the proot path relies on an age-gated sweep */
    } else {
        fprintf(stderr, "%s: offload reply file missing (%s)\n", argv[0], path);
        free(path);
        return EX_UNAVAILABLE;
    }
    free(path);
    return (int)code;
}
