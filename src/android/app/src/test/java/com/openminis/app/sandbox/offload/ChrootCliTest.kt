package com.openminis.app.sandbox.offload

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FEAT-261027-CHROOT-CLI] The approval/refusal contract, on a plain JVM.
 *
 * These tests never touch Android, Root, `su` or a real chroot: the
 * coordinator takes lambdas, so every privileged step is a recorded fake.
 */
class ChrootCliTest {

    private class Recorder {
        val calls = mutableListOf<String>()
        var ensureFailure: String? = null
        var run = ChrootCliRun(exitCode = 0, stdout = "ok\n")
        var executedRequest: ChrootCliExecRequest? = null
    }

    private fun coordinator(
        recorder: Recorder,
        rootAllowed: () -> Boolean,
        confirm: suspend (ChrootCliExecRequest) -> Boolean,
    ) = ChrootCliCoordinator(
        rootSwitchAllowed = rootAllowed,
        confirm = { req ->
            recorder.calls += "confirm"
            confirm(req)
        },
        ensureServer = {
            recorder.calls += "ensure"
            recorder.ensureFailure?.let { Result.failure(IllegalStateException(it)) }
                ?: Result.success(Unit)
        },
        execute = { req ->
            recorder.calls += "execute"
            recorder.executedRequest = req
            recorder.run
        },
    )

    private val request = ChrootCliExecRequest(
        command = "printf '%s\\n' \"-x\"",
        timeoutSeconds = 30,
        sessionId = "session-42",
    )

    // ── 申请前 / 拒绝 / 超时 ───────────────────────────────────────────────

    @Test
    fun rootDisabledRefusesBeforePromptingOrStartingAnything() = runBlocking {
        val recorder = Recorder()
        var prompted = false
        val outcome = coordinator(
            recorder,
            rootAllowed = { false },
            confirm = { prompted = true; true },
        ).exec(request)

        val refused = outcome as ChrootCliOutcome.Refused
        assertEquals(ChrootCliRefusal.ROOT_DISABLED, refused.reason)
        assertTrue(refused.message.contains("设置"))
        assertTrue(refused.message.contains("不允许"))
        assertFalse(prompted)
        assertEquals(emptyList<String>(), recorder.calls)
        assertNull(recorder.executedRequest)
    }

    @Test
    fun denialStopsBeforeHelperAndProcess() = runBlocking {
        val recorder = Recorder()
        val outcome = coordinator(recorder, { true }, { false }).exec(request)

        assertEquals(ChrootCliRefusal.DENIED, (outcome as ChrootCliOutcome.Refused).reason)
        assertEquals(listOf("confirm"), recorder.calls)
        assertNull(recorder.executedRequest)
    }

    @Test
    fun confirmationTimeoutIsARefusalAndStartsNothing() = runBlocking {
        val recorder = Recorder()
        val startedAt = System.nanoTime()
        val outcome = coordinator(
            recorder,
            rootAllowed = { true },
            // Same shape as confirmPrivilegedOperation's internal timeout:
            // no answer -> false, not an exception and not an approval.
            confirm = { withTimeoutOrNull(50L) { CompletableDeferred<Boolean>().await() } ?: false },
        ).exec(request)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertEquals(ChrootCliRefusal.DENIED, (outcome as ChrootCliOutcome.Refused).reason)
        assertEquals(listOf("confirm"), recorder.calls)
        assertTrue("timeout path should return promptly, took ${elapsedMs}ms", elapsedMs < 5_000)
        assertNull(recorder.executedRequest)
    }

    // ── 批准后的顺序与状态 ─────────────────────────────────────────────────

    @Test
    fun approvalOrderIsConfirmThenEnsureThenExecute() = runBlocking {
        val recorder = Recorder()
        val outcome = coordinator(recorder, { true }, { true }).exec(request)

        assertEquals(listOf("confirm", "ensure", "execute"), recorder.calls)
        assertEquals(request, recorder.executedRequest)
        assertEquals(request.command, recorder.executedRequest!!.command)
        assertEquals(30, recorder.executedRequest!!.timeoutSeconds)
        assertEquals("session-42", recorder.executedRequest!!.sessionId)
        assertEquals(0, (outcome as ChrootCliOutcome.Completed).run.exitCode)
    }

    @Test
    fun rootRevokedAfterConfirmationDoesNotStartHelper() = runBlocking {
        var allowed = true
        val recorder = Recorder()
        val outcome = coordinator(
            recorder,
            rootAllowed = { allowed },
            confirm = {
                // The user had TWO minutes to answer; a revocation during the
                // dialog must be honoured before any privilege is requested.
                allowed = false
                true
            },
        ).exec(request)

        assertEquals(ChrootCliRefusal.ROOT_REVOKED, (outcome as ChrootCliOutcome.Refused).reason)
        assertEquals(listOf("confirm"), recorder.calls)
        assertNull(recorder.executedRequest)
    }

    @Test
    fun rootRevokedWhileStartingHelperDoesNotExecute() = runBlocking {
        var checks = 0
        val recorder = Recorder()
        val outcome = coordinator(
            recorder,
            // 1st: before confirm; 2nd: after confirm; 3rd: after ensureServer.
            rootAllowed = { checks++; checks <= 2 },
            confirm = { true },
        ).exec(request)

        val refused = outcome as ChrootCliOutcome.Refused
        assertEquals(ChrootCliRefusal.ROOT_REVOKED, refused.reason)
        assertTrue(refused.message.contains("未执行用户命令"))
        assertEquals(listOf("confirm", "ensure"), recorder.calls)
        assertNull(recorder.executedRequest)
    }

    @Test
    fun helperFailureDoesNotExecuteTheCommand() = runBlocking {
        val recorder = Recorder().apply { ensureFailure = "Root 权限为“不允许”" }
        val outcome = coordinator(recorder, { true }, { true }).exec(request)

        assertEquals(ChrootCliRefusal.HELPER_FAILED, (outcome as ChrootCliOutcome.Refused).reason)
        assertTrue(outcome.message.contains("命令未执行"))
        assertEquals(listOf("confirm", "ensure"), recorder.calls)
        assertNull(recorder.executedRequest)
    }

    @Test
    fun completedRunCarriesTheRealExitCode() = runBlocking {
        val recorder = Recorder().apply { run = ChrootCliRun(exitCode = 42, stdout = "x\n") }
        val outcome = coordinator(recorder, { true }, { true }).exec(request)

        assertEquals(42, (outcome as ChrootCliOutcome.Completed).run.exitCode)
    }

    @Test
    fun executorThrowBecomesStartFailureNotApproval() = runBlocking {
        val recorder = Recorder()
        val c = ChrootCliCoordinator(
            rootSwitchAllowed = { true },
            confirm = { true },
            ensureServer = { Result.success(Unit) },
            execute = { throw IllegalStateException("spawn exploded") },
        )
        val outcome = c.exec(request)

        val run = (outcome as ChrootCliOutcome.Completed).run
        assertEquals(125, run.exitCode)
        assertTrue(run.stderr.contains("spawn exploded"))
    }

    // ── 参数与提示文案 ─────────────────────────────────────────────────────

    @Test
    fun parserKeepsTheCommandVerbatimAndDefaultsTo60Seconds() {
        val parsed = ChrootCliArgs.parseExec(
            listOf("--command", "printf '%s\\n' \"-x\""),
            sessionId = "s",
        )
        val exec = parsed as ChrootCliParse.Exec
        assertEquals("printf '%s\\n' \"-x\"", exec.request.command)
        assertEquals(CHROOT_CLI_DEFAULT_TIMEOUT_SECONDS, exec.request.timeoutSeconds)
        assertEquals("s", exec.request.sessionId)
    }

    @Test
    fun parserAcceptsEqualsFormAndACommandStartingWithDash() {
        val parsed = ChrootCliArgs.parseExec(
            listOf("--command=-x --timeout=5", "--timeout", "5"),
            sessionId = null,
        )
        val exec = parsed as ChrootCliParse.Exec
        assertEquals("-x --timeout=5", exec.request.command)
        assertEquals(5, exec.request.timeoutSeconds)
    }

    @Test
    fun parserRejectsBadTimeoutsAndMissingCommand() {
        for (bad in listOf("0", "-1", "601", "abc")) {
            val parsed = ChrootCliArgs.parseExec(listOf("--command", "true", "--timeout", bad), null)
            assertTrue("timeout '$bad' must fail", parsed is ChrootCliParse.Failure)
            assertTrue((parsed as ChrootCliParse.Failure).message.contains("1..600"))
        }
        assertTrue(ChrootCliArgs.parseExec(listOf("--timeout", "5"), null) is ChrootCliParse.Failure)
        assertTrue(ChrootCliArgs.parseExec(listOf("--command", "   "), null) is ChrootCliParse.Failure)
        assertTrue(ChrootCliArgs.parseExec(listOf("--bogus"), null) is ChrootCliParse.Failure)
        assertTrue(ChrootCliArgs.parseExec(listOf("--command", "true", "--help"), null) is ChrootCliParse.Help)
    }

    @Test
    fun confirmationTextStatesCommandTimeoutIdentityNamespaceAndHelperPolicy() {
        val text = ChrootCliPresentation.describe(
            request,
            ChrootCliIdentity(
                uid = 10379,
                gid = 10379,
                rootfs = "/data/user/0/com.openminis.app.next/files/rootfs",
                socketName = "minis-chroot-gabcdef",
                idleTtlSeconds = 900,
            ),
        )
        assertTrue(text.contains(request.command))
        assertTrue(text.contains("30 秒"))
        assertTrue(text.contains("uid=10379"))
        assertTrue(text.contains("gid=10379"))
        assertTrue(text.contains("PR_SET_NO_NEW_PRIVS"))
        // [FEAT-261027-CHROOT-ROOT] The command child is a REAL uid0 when the
        // tri-state allows it; a refusal is never an app-uid downgrade.
        assertTrue(text.contains("uid0"))
        assertTrue(text.contains("不会静默降级为 app uid"))
        assertTrue(text.contains("私有挂载命名空间"))
        assertTrue(text.contains("unshare(CLONE_NEWNS)"))
        assertTrue(text.contains("临时 helper"))
        assertTrue(text.contains("minis-chroot-gabcdef"))
        assertTrue(text.contains("root token"))
        assertTrue(text.contains("永远不会出现在 su 的命令行中"))
        assertTrue(text.contains("拒绝、取消或确认超时"))
        assertFalse(text.contains("BYPASS"))
    }

    @Test
    fun helpAdvertisesThePerCallConfirmationAndTimeoutBounds() {
        val help = ChrootCliPresentation.help()
        // [FEAT-261027-CHROOT-ROOT] The CLI follows the unified tri-state.
        assertTrue(help.contains("原生 Root 三态"))
        assertTrue(help.contains("每次询问"))
        assertTrue(help.contains("--timeout 60"))
        assertTrue(help.contains("1..600"))
        assertTrue(help.contains("不调用 su"))
        assertFalse(help.contains("su -c"))
    }

    // ── status: probes only, no "helper is alive" claim ────────────────────

    private class FakeStatus(
        override val rootLevel: String = "BYPASS",
        override val runtimeGrantPresent: Boolean = true,
        override val launcherExecutable: Boolean = true,
    ) : ChrootCliStatusSource {
        override val launcherPath = "/data/app/lib/arm64/libminis-chroot.so"
        override val launcherInstalled = true
        override val rootfsPath = "/data/user/0/com.openminis.app.next/files/rootfs"
        override val rootfsPresent = true
        override val offloadClientPath = "/rootfs/usr/local/lib/minis/minis-offload-client"
        override val offloadClientInstalled = true
        override val selectedBackend = "PROOT"
        override val generation = "gtest"
        override val grantMayBeExpired = false
        override val autostart = true
        override val helperStateMarker: String? = "generation=gtest\npid=4242"
        override val lastError: String? = null
        override val rootSwitchAllowed: Boolean get() = rootLevel != "NOT_ALLOWED"
        override val uid = 10379
        override val gid = 10379
    }

    @Test
    fun statusKeepsTheSelectedBackendAndNeverClaimsTheHelperIsAlive() {
        val snap = ChrootCliStatus.snapshot(FakeStatus())
        assertEquals("PROOT", snap.selectedBackend)
        assertTrue(snap.helper.grantRecorded)
        assertEquals(ChrootCliHelperStatus.HELPER_NOT_PROBED, snap.helper.helperLiveness)
        // A grant is not a liveness proof, so the model has no alive/running
        // field at all; these assertions pin the only exposed facts.
        assertEquals("generation=gtest\npid=4242", snap.helper.stateMarker)
        assertTrue(snap.prerequisitesMet)
        assertTrue(snap.notes.joinToString(" ").contains("不调用 su"))
        assertTrue(snap.notes.joinToString(" ").contains("helper 本身是 uid0"))
    }

    @Test
    fun statusReportsRootDisabledWithoutChangingAnything() {
        val snap = ChrootCliStatus.snapshot(FakeStatus(rootLevel = "NOT_ALLOWED"))
        assertEquals("NOT_ALLOWED", snap.rootLevel)
        assertFalse(snap.rootSwitchAllowed)
        assertFalse(snap.prerequisitesMet)
        // Still a read-only report: the Root level is echoed, never changed.
        assertEquals("PROOT", snap.selectedBackend)
    }

    @Test
    fun statusPrerequisitesRequireAnExecutableLauncher() {
        val snap = ChrootCliStatus.snapshot(FakeStatus(launcherExecutable = false))
        assertTrue(snap.launcherInstalled)
        assertFalse(snap.prerequisitesMet)
        assertTrue(snap.notes.joinToString(" ").contains("不探测 su"))
    }

    @Test
    fun statusReadsEveryProbeExactlyOnceAndNothingElse() {
        var reads = 0
        val source = object : ChrootCliStatusSource {
            override val launcherPath: String get() = "l".also { reads++ }
            override val launcherInstalled: Boolean get() = true.also { reads++ }
            override val launcherExecutable: Boolean get() = true.also { reads++ }
            override val rootfsPath: String get() = "r".also { reads++ }
            override val rootfsPresent: Boolean get() = true.also { reads++ }
            override val offloadClientPath: String get() = "c".also { reads++ }
            override val offloadClientInstalled: Boolean get() = true.also { reads++ }
            override val selectedBackend: String get() = "CHROOT".also { reads++ }
            override val generation: String get() = "g".also { reads++ }
            override val runtimeGrantPresent: Boolean get() = false.also { reads++ }
            override val grantMayBeExpired: Boolean get() = false.also { reads++ }
            override val autostart: Boolean get() = false.also { reads++ }
            override val helperStateMarker: String? get() = null.also { reads++ }
            override val lastError: String? get() = null.also { reads++ }
            override val rootLevel: String get() = "ASK_ONCE".also { reads++ }
            override val rootSwitchAllowed: Boolean get() = true.also { reads++ }
            override val uid: Int get() = 1.also { reads++ }
            override val gid: Int get() = 1.also { reads++ }
        }
        val snap = ChrootCliStatus.snapshot(source)
        assertNotNull(snap)
        assertEquals(18, reads)
    }
}
