/* Ordinary App/uid-0 file handoff. No tree walk, chown, or command rewriting. */
#ifndef MINIS_SHARED_DIRS_H
#define MINIS_SHARED_DIRS_H
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/xattr.h>
#include <unistd.h>

/* Only App-managed mount roots qualify. User mounts and the guest tree do not.
 * host and files_dir are canonical absolute paths supplied by the caller. */
static int minis_shared_is_managed(const char *guest, const char *host, const char *files_dir) {
    static const char *session[] = {"attachments", "offloads", "workspace", "browser"};
    static const char *global[] = {"memory", "skills", "shared", "mcp-servers"};
    char expected[4096], target[128];
    if (!files_dir || !files_dir[0]) return 0;
    for (unsigned i = 0; i < sizeof(global) / sizeof(global[0]); ++i) {
        snprintf(target, sizeof(target), "/var/minis/%s", global[i]);
        if (snprintf(expected, sizeof(expected), "%s/minis-global/%s", files_dir, global[i]) >= (int)sizeof(expected)) return 0;
        if (!strcmp(guest, target) && !strcmp(host, expected)) return 1;
    }
    int n = snprintf(expected, sizeof(expected), "%s/minis-sessions/", files_dir);
    if (n < 0 || n >= (int)sizeof(expected) || strncmp(host, expected, (size_t)n)) return 0;
    const char *id = host + n, *slash = strchr(id, '/');
    if (!slash || slash == id || (slash - id == 1 && id[0] == '.') ||
        (slash - id == 2 && id[0] == '.' && id[1] == '.')) return 0;
    for (unsigned i = 0; i < sizeof(session) / sizeof(session[0]); ++i) {
        snprintf(target, sizeof(target), "/var/minis/%s", session[i]);
        if (!strcmp(guest, target) && !strcmp(slash + 1, session[i])) return 1;
    }
    return 0;
}

static void minis_acl_u16(unsigned char *p, unsigned v) {
    p[0] = (unsigned char)v; p[1] = (unsigned char)(v >> 8);
}
static void minis_acl_u32(unsigned char *p, uint32_t v) {
    for (unsigned i = 0; i < 4; ++i) p[i] = (unsigned char)(v >> (i * 8));
}

/* Linux POSIX ACL xattr ABI: version + sorted entries (tag, perm, id), LE.
 * New children inherit a named App user, constrained by the creation mode and
 * later chmod's ACL mask. The root directory's access ACL/mode is untouched.
 * An existing different default ACL is an explicit policy and is preserved.
 * Only the one mount root is opened; no descendants are inspected or changed.
 * app_gid verifies the actual App identity; no gid == uid assumption is made. */
static int minis_shared_prepare(const char *path, uid_t app_uid, gid_t app_gid) {
    int fd = open(path, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (fd < 0) return -1;
    struct stat st;
    int rc = -1, saved;
    if (fstat(fd, &st) != 0) goto done;
    if (st.st_uid != app_uid || st.st_gid != app_gid) { errno = EPERM; goto done; }
    unsigned char acl[44];
    minis_acl_u32(acl, 2);
    const unsigned tags[] = {1, 2, 4, 16, 32};
    const unsigned perms[] = {(unsigned)(st.st_mode >> 6) & 7U, 7,
                              (unsigned)(st.st_mode >> 3) & 7U, 7,
                              (unsigned)st.st_mode & 7U};
    for (unsigned i = 0; i < 5; ++i) {
        minis_acl_u16(acl + 4 + i * 8, tags[i]);
        minis_acl_u16(acl + 6 + i * 8, perms[i]);
        minis_acl_u32(acl + 8 + i * 8, i == 1 ? (uint32_t)app_uid : UINT32_MAX);
    }
    unsigned char current[44];
    ssize_t n = fgetxattr(fd, "system.posix_acl_default", current, sizeof(current));
    if (n >= 0) {
        if (n == (ssize_t)sizeof(acl) && !memcmp(current, acl, sizeof(acl))) rc = 0;
        else errno = EEXIST;
        goto done;
    }
    if (errno == ERANGE) { errno = EEXIST; goto done; }
    if (errno != ENODATA) goto done;
    rc = fsetxattr(fd, "system.posix_acl_default", acl, sizeof(acl), XATTR_CREATE);
    if (rc != 0 && errno == EEXIST) {
        n = fgetxattr(fd, "system.posix_acl_default", current, sizeof(current));
        if (n == (ssize_t)sizeof(acl) && !memcmp(current, acl, sizeof(acl))) rc = 0;
        else errno = EEXIST;
    }
done:
    saved = errno;
    close(fd);
    errno = saved;
    return rc;
}
#endif
