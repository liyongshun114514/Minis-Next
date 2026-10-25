package com.openminis.app.sandbox

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FEAT-261025-CHROOT] Pure-JVM tests for the backend switch.
 *
 * What can be verified without a device: the decision policy (which is the
 * part that must never silently cut a running command), the mount allowlist,
 * the wire/handshake strings, and the argv shapes that keep the user's command
 * out of any privileged command line. Everything that needs root is reported
 * as unverified in docs/android-chroot.md instead of being faked here.
 */
class ChrootBackendTest {

    private fun task(id: String) = ActiveSandboxTask(id = id, label = "cmd $id", stop = {})

    private val available = ChrootAvailability(
        launcherInstalled = true,
        offloadClientInstalled = true,
        bridgePresent = true,
        rootManagerDetected = true,
        serverRunning = false,
    )

    // ── Backend selection ──────────────────────────────────────────────────

    @Test
    fun `proot remains the default and unknown stored values fall back to it`() {
        assertEquals(SandboxBackend.PROOT, SandboxBackend.DEFAULT)
        assertEquals(SandboxBackend.PROOT, SandboxBackend.fromStored(null))
        assertEquals(SandboxBackend.PROOT, SandboxBackend.fromStored("SOMETHING_ELSE"))
        assertEquals(SandboxBackend.CHROOT, SandboxBackend.fromStored("CHROOT"))
    }

    // ── Switch policy ──────────────────────────────────────────────────────

    @Test
    fun `switching to the current backend is a no-op`() {
        val d = SandboxSwitchPolicy.decide(
            current = SandboxBackend.PROOT,
            target = SandboxBackend.PROOT,
            activeTasks = listOf(task("a")),
            availability = available,
            authorizedThisProcess = false,
            stopActive = false,
        )
        assertTrue(d is BackendSwitchDecision.NoChange)
    }

    @Test
    fun `a running command refuses the switch instead of being cut`() {
        val tasks = listOf(task("a"), task("b"))
        val d = SandboxSwitchPolicy.decide(
            current = SandboxBackend.PROOT,
            target = SandboxBackend.CHROOT,
            activeTasks = tasks,
            availability = available,
            authorizedThisProcess = true,
            stopActive = false,
        )
        assertTrue(d is BackendSwitchDecision.RefusedActiveTasks)
        assertEquals(listOf("a", "b"), (d as BackendSwitchDecision.RefusedActiveTasks).tasks.map { it.id })
    }

    @Test
    fun `the same switch proceeds once the user confirms stopping those tasks`() {
        val tasks = listOf(task("a"), task("b"))
        val d = SandboxSwitchPolicy.decide(
            current = SandboxBackend.PROOT,
            target = SandboxBackend.CHROOT,
            activeTasks = tasks,
            availability = available,
            authorizedThisProcess = true,
            stopActive = true,
        )
        assertTrue(d is BackendSwitchDecision.Allowed)
        d as BackendSwitchDecision.Allowed
        assertEquals(SandboxBackend.CHROOT, d.backend)
        assertEquals(listOf("a", "b"), d.stopTasks.map { it.id })
        // Already granted in this process => no second root prompt.
        assertFalse(d.requiresRootGrant)
    }

    @Test
    fun `a chroot grant is per process and a new one needs the root bridge`() {
        val d = SandboxSwitchPolicy.decide(
            current = SandboxBackend.PROOT,
            target = SandboxBackend.CHROOT,
            activeTasks = emptyList(),
            availability = available,
            authorizedThisProcess = false,
            stopActive = false,
        ) as BackendSwitchDecision.Allowed
        assertTrue(d.requiresRootGrant)
    }

    @Test
    fun `chroot is refused when the helper is not packaged`() {
        val d = SandboxSwitchPolicy.decide(
            current = SandboxBackend.PROOT,
            target = SandboxBackend.CHROOT,
            activeTasks = emptyList(),
            availability = available.copy(launcherInstalled = false),
            authorizedThisProcess = true,
            stopActive = false,
        )
        assertTrue(d is BackendSwitchDecision.RefusedUnavailable)
        assertTrue((d as BackendSwitchDecision.RefusedUnavailable).reason.contains("libminis-chroot.so"))
    }

    @Test
    fun `chroot is refused when the root bridge is missing`() {
        val d = SandboxSwitchPolicy.decide(
            current = SandboxBackend.PROOT,
            target = SandboxBackend.CHROOT,
            activeTasks = emptyList(),
            availability = available.copy(bridgePresent = false),
            authorizedThisProcess = true,
            stopActive = false,
        )
        assertTrue(d is BackendSwitchDecision.RefusedUnavailable)
    }

    @Test
    fun `falling back to proot never needs a root grant and never refuses on availability`() {
        val d = SandboxSwitchPolicy.decide(
            current = SandboxBackend.CHROOT,
            target = SandboxBackend.PROOT,
            activeTasks = emptyList(),
            availability = available.copy(launcherInstalled = false, bridgePresent = false),
            authorizedThisProcess = false,
            stopActive = false,
        ) as BackendSwitchDecision.Allowed
        assertFalse(d.requiresRootGrant)
        assertEquals(SandboxBackend.PROOT, d.backend)
    }

    // ── Mount allowlist ────────────────────────────────────────────────────

    private val roots = listOf("/data/user/0/com.openminis.app/files", "/storage/emulated/0")

    @Test
    fun `mount pairs inside the app roots are accepted and sorted`() {
        val v = ChrootKernel.validateMounts(
            mapOf(
                "/var/minis/workspace" to "/data/user/0/com.openminis.app/files/minis-sessions/s1/workspace",
                "/var/minis/mounts/SD" to "/storage/emulated/0/Minis",
            ),
            roots,
        )
        assertEquals(listOf("/var/minis/mounts/SD", "/var/minis/workspace"), v.accepted.map { it.first })
        assertTrue(v.rejected.isEmpty())
    }

    @Test
    fun `host sources outside the app's roots are rejected`() {
        val v = ChrootKernel.validateMounts(
            mapOf(
                "/var/minis/workspace" to "/data/user/0/com.openminis.app/files/../../other/x",
                "/var/minis/etc" to "/system/etc",
                "/var/minis/data" to "/data",
            ),
            roots,
        )
        // "..": normalisation refuses it outright; /system and /data are
        // outside the allowlist even though they are absolute.
        assertEquals(0, v.accepted.size)
        assertEquals(3, v.rejected.size)
    }

    @Test
    fun `a guest target cannot shadow the root or escape the sandbox paths`() {
        val v = ChrootKernel.validateMounts(
            mapOf(
                "/" to "/data/user/0/com.openminis.app/files",
                "/etc/passwd" to "/data/user/0/com.openminis.app/files/x",
                "/var/minis/./workspace/" to "/data/user/0/com.openminis.app/files/w",
            ),
            roots,
        )
        assertEquals(listOf("/var/minis/workspace"), v.accepted.map { it.first })
        assertEquals(2, v.rejected.size)
    }

    @Test
    fun `option-smuggling characters are refused in both sides of a mount`() {
        assertEquals(0, ChrootKernel.validateMounts(mapOf("/var/minis/a:b" to "/data/x"), roots).accepted.size)
        assertEquals(0, ChrootKernel.validateMounts(mapOf("/var/minis/a" to "/data/x:ro"), roots).accepted.size)
    }

    @Test
    fun `relative paths never normalise`() {
        assertNull(ChrootKernel.normaliseAbsolute("var/minis"))
        assertNull(ChrootKernel.normaliseAbsolute("/var/../etc"))
        assertEquals("/var/minis", ChrootKernel.normaliseAbsolute("/var//minis/."))
        assertTrue(ChrootKernel.isUnder("/data/user/0/pkg/files", "/data/user/0/pkg/files"))
        assertFalse(ChrootKernel.isUnder("/data/user/0/pkg/files2", "/data/user/0/pkg/files"))
    }

    // ── Command lines ──────────────────────────────────────────────────────

    @Test
    fun `the privileged argv carries no user data`() {
        val argv = ChrootKernel.serverArgv(
            launcher = "/data/app/lib/libminis-chroot.so",
            rootfs = "/data/user/0/pkg/files/alpine-rootfs",
            generation = "gabc123",
            allowedHostRoots = listOf("/data/user/0/pkg/files", "relative-ignored"),
            uid = 10421,
            gid = 10421,
        )
        assertEquals("--serve", argv[1])
        assertTrue(argv.contains("--root"))
        assertTrue(argv.contains("gabc123".let { ChrootKernel.socketName(it) }))
        // A relative allow-root would mean the helper's checks depend on its
        // cwd; it must be dropped, not passed through.
        assertFalse(argv.contains("relative-ignored"))
        assertFalse(argv.any { it.contains("su") && it.startsWith("-") })
    }

    @Test
    fun `the guest command always sits after the option separator`() {
        val argv = ChrootKernel.clientArgv(
            launcher = "/l",
            generation = "g1",
            rootfs = "/r",
            guestArgv = listOf("/bin/sh", "-c", "rm -rf --no-preserve-root /"),
            mounts = listOf(ChrootKernel.ChrootMount("/var/minis/workspace", "/host/ws", readOnly = true)),
        )
        val sep = argv.indexOf("--")
        assertEquals(sep + 1, argv.indexOf("/bin/sh"))
        assertTrue(argv.indexOf("/bin/sh") > argv.indexOf("--ro-mount"))
        assertEquals("/var/minis/workspace=/host/ws", argv[argv.indexOf("--ro-mount") + 1])
        assertTrue(argv.take(sep).none { it.contains("rm -rf") })
    }

    @Test
    fun `the handshake must name this generation`() {
        val gen = "g42"
        val sock = ChrootKernel.socketName(gen)
        assertEquals(4242, ChrootKernel.parseHandshake("noise\nMINIS-CHROOT-READY $sock 4242\n", gen))
        assertNull(ChrootKernel.parseHandshake("MINIS-CHROOT-READY minis-chroot-gold 99\n", gen))
        assertNull(ChrootKernel.parseHandshake("", gen))
    }

    @Test
    fun `generations are random and distinct`() {
        val a = ChrootKernel.newGeneration()
        val b = ChrootKernel.newGeneration()
        assertNotEquals(a, b)
        assertTrue(a.startsWith("g") && a.length == 25)
        assertTrue(a.drop(1).all { it.isLetterOrDigit() })
    }

    @Test
    fun `availability never claims tools work without the guest client`() {
        val blocked = available.copy(offloadClientInstalled = false)
        assertTrue(blocked.summary().contains("BLOCKED"))
        assertTrue(available.summary().contains("guest offload client"))
    }

    // ── Source facts: the parts that only a device could otherwise verify ──

    private fun src(path: String): String {
        val f = File(path)
        assertTrue("missing ${f.absolutePath}", f.exists())
        return f.readText()
    }

    private val sandboxDir = "src/main/java/com/openminis/app/sandbox"
    private val cppDir = "src/main/cpp"

    @Test
    fun `all four spawn sites route through the backend selector`() {
        for (name in listOf(
            "ShellExecutor.kt", "FreshProcessShell.kt", "PersistentShell.kt", "TerminalSession.kt",
        )) {
            val text = src("$sandboxDir/$name")
            assertTrue("$name does not use SandboxRouter", text.contains("SandboxRouter.build("))
        }
    }

    @Test
    fun `proot's public command builder keeps its call shape`() {
        val kernel = src("$sandboxDir/PRootKernel.kt")
        assertTrue(kernel.contains("fun buildProotCommand(shellCommand: String): List<String>"))
        assertTrue(kernel.contains("fun buildProotCommand(shellCommand: String, mounts: Map<String, String>)"))
        assertTrue(kernel.contains("fun buildProotInteractiveCommand("))
        assertTrue(kernel.contains("fun refreshOffloadStubs()"))
    }

    @Test
    fun `the chroot path never builds an su dash-c string`() {
        for (name in listOf("ChrootKernel.kt", "ChrootPrivilege.kt", "SandboxBackendStore.kt")) {
            val text = src("$sandboxDir/$name")
            assertFalse("$name contains a su -c line", Regex("\\bsu\\b\\s*-c").containsMatchIn(text))
            assertFalse("$name shells out", text.contains("Runtime.getRuntime().exec"))
        }
    }

    @Test
    fun `exit codes agree between Kotlin and the C helper`() {
        val kernel = src("$sandboxDir/ChrootKernel.kt")
        val helper = src("$cppDir/minis_chroot.c")
        assertTrue(kernel.contains("EXIT_NO_HELPER = 97"))
        assertTrue(helper.contains("#define EXIT_NO_HELPER 97"))
        assertTrue(kernel.contains("EXIT_REJECTED = 98"))
        assertTrue(helper.contains("#define EXIT_REJECTED  98"))
    }

    @Test
    fun `the helper checks peer credentials and never ignores SIGCHLD`() {
        val helper = src("$cppDir/minis_chroot.c")
        assertTrue(helper.contains("SO_PEERCRED"))
        assertTrue(helper.contains("peer != s->uid"))
        // SIG_IGN on SIGCHLD makes waitpid() fail, and a killed command would
        // then be reported as success.
        assertFalse(helper.contains("signal(SIGCHLD, SIG_IGN)"))
        // One handler per connection: an interactive shell must not block all.
        assertTrue(helper.contains("serve_one(s, conn, self)"))
        // argv/envp must be NULL terminated.
        assertTrue(helper.contains("calloc((size_t)r->argc + 1"))
        assertTrue(helper.contains("calloc((size_t)r->envc + 1"))
        // A symlink inside the rootfs must not be able to host a mount.
        assertTrue(helper.contains("S_ISLNK(st.st_mode)"))
        assertTrue(helper.contains("mount target escapes the rootfs"))
        // Handshake names the daemon, and its stdio is detached.
        assertTrue(helper.contains("MINIS-CHROOT-READY %s %d\n\", s->socket_name, (int)daemon)"))
    }

    @Test
    fun `the stop path does not ask for root`() {
        val kernel = src("$sandboxDir/ChrootKernel.kt")
        val shutdown = kernel.substringAfter("suspend fun shutdown").substringBefore("fun onRootfsReady")
        assertTrue(shutdown.contains("ProcessBuilder("))
        assertFalse(shutdown.contains("ChrootPrivilege.run("))
    }

    @Test
    fun `a handshake without a zero exit is a failure`() {
        val kernel = src("$sandboxDir/ChrootKernel.kt")
        assertTrue(kernel.contains("outcome.exitCode == 0) parseHandshake"))
        assertTrue(kernel.contains("File(root).isAbsolute"))
    }

    @Test
    fun `the guest offload client is installed from assets atomically`() {
        val kernel = src("$sandboxDir/ChrootKernel.kt")
        assertTrue(kernel.contains("context.assets.open(assetName)"))
        assertTrue(kernel.contains(".minis-offload-client.tmp"))
        assertTrue(kernel.contains("tmp.renameTo(target)"))
        // Must not route through the rootfs manager this patch may not change.
        assertFalse(kernel.contains("RootfsManager.getInstance(context).install"))
    }

    @Test
    fun `the helper never touches selinux or remounts a real filesystem`() {
        val helper = src("$cppDir/minis_chroot.c")
        for (banned in listOf("setenforce", "MS_REMOUNT | MS_RDONLY", "system(\"mount", "/system/bin/su")) {
            assertFalse("helper contains $banned", helper.contains(banned))
        }
        // Its only remount is inside the request's private namespace.
        assertTrue(helper.contains("unshare(CLONE_NEWNS)"))
        assertTrue(helper.contains("MS_REC | MS_PRIVATE"))
        assertTrue(helper.contains("setuid((uid_t)r.uid)"))
    }

    @Test
    fun `the offload client speaks the same protocol the host reads`() {
        val client = src("$cppDir/minis_offload_client.c")
        val host = src("$sandboxDir/NativeOffload.kt")
        // 0x46464F4E request / 0x52464F4E reply, little-endian both sides.
        assertTrue(client.contains("MAGIC_REQ 0x46464F4EU"))
        assertTrue(client.contains("MAGIC_RSP 0x52464F4EU"))
        assertTrue(host.contains("MAGIC_REQ = 0x46464F4E"))
        assertTrue(host.contains("MAGIC_RSP = 0x52464F4E"))
        assertTrue(host.contains("private const val VERSION = 1"))
        assertTrue(client.contains("#define VERSION   1U"))
        // The guest client must connect as the app uid, which is what the
        // server's peer check accepts.
        assertTrue(client.contains("connect_abstract"))
    }
}
