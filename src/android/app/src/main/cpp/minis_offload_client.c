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
    int fd = connect_abstract(sock);
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
