package com.openminis.app.sandbox.offload

import com.openminis.app.offload.RootAccess
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FEAT-261027-CHROOT-ROOT] `android-chroot-cli` under the unified tri-state.
 *
 * 完全允许 is the only dialog-free level; 每次询问 asks once per invocation;
 * 不允许 refuses before a dialog, a helper or a process exists.
 */
class ChrootCliTriStateTest {

    private val request = ChrootCliExecRequest("apt-get install -y curl", 30, "s1")

    private class Recorder {
        val calls = mutableListOf<String>()
        var executed = false
    }

    private fun run(
        recorder: Recorder,
        level: RootAccess,
        confirmAnswer: Boolean = true,
        epoch: () -> Long = { 1L },
    ): ChrootCliOutcome = runBlocking {
        ChrootCliCoordinator(
            rootSwitchAllowed = { level != RootAccess.NOT_ALLOWED },
            level = { level },
            epoch = epoch,
            confirm = { recorder.calls += "confirm"; confirmAnswer },
            ensureServer = { recorder.calls += "ensure"; Result.success(Unit) },
            execute = { recorder.calls += "execute"; recorder.executed = true; ChrootCliRun(0, "ok\n") },
        ).exec(request)
    }

    @Test
    fun bypassSkipsTheDialogAndRuns() {
        val r = Recorder()
        val outcome = run(r, RootAccess.BYPASS)
        assertTrue(outcome is ChrootCliOutcome.Completed)
        assertFalse("完全允许 must not show a dialog", r.calls.contains("confirm"))
        assertEquals(listOf("ensure", "execute"), r.calls)
    }

    @Test
    fun askOncePromptsBeforeHelperAndRunsOnlyAfterYes() {
        val r = Recorder()
        val outcome = run(r, RootAccess.ASK_ONCE, confirmAnswer = true)
        assertTrue(outcome is ChrootCliOutcome.Completed)
        assertEquals(listOf("confirm", "ensure", "execute"), r.calls)
    }

    @Test
    fun askOnceDenialRunsNothing() {
        val r = Recorder()
        val outcome = run(r, RootAccess.ASK_ONCE, confirmAnswer = false)
        assertEquals(ChrootCliRefusal.DENIED, (outcome as ChrootCliOutcome.Refused).reason)
        assertEquals(listOf("confirm"), r.calls)
        assertFalse(r.executed)
    }

    @Test
    fun notAllowedNeverPromptsOrStartsAnything() {
        val r = Recorder()
        val outcome = run(r, RootAccess.NOT_ALLOWED)
        assertEquals(ChrootCliRefusal.ROOT_DISABLED, (outcome as ChrootCliOutcome.Refused).reason)
        assertEquals(emptyList<String>(), r.calls)
    }

    @Test
    fun revokeDuringTheConfirmationStartsNothing() = runBlocking {
        val r = Recorder()
        var epoch = 1L
        val outcome = runBlocking {
            ChrootCliCoordinator(
                rootSwitchAllowed = { true },
                level = { RootAccess.ASK_ONCE },
                epoch = { epoch },
                confirm = { r.calls += "confirm"; epoch = 2L; true },
                ensureServer = { r.calls += "ensure"; Result.success(Unit) },
                execute = { r.calls += "execute"; r.executed = true; ChrootCliRun(0, "ok\n") },
            ).exec(request)
        }
        assertEquals(ChrootCliRefusal.ROOT_REVOKED, (outcome as ChrootCliOutcome.Refused).reason)
        assertEquals(listOf("confirm"), r.calls)
        assertFalse(r.executed)
    }

    @Test
    fun revokeDuringTheHelperStartRunsNoCommand() = runBlocking {
        val r = Recorder()
        var epoch = 1L
        val outcome = runBlocking {
            ChrootCliCoordinator(
                rootSwitchAllowed = { true },
                level = { RootAccess.BYPASS },
                epoch = { epoch },
                confirm = { r.calls += "confirm"; true },
                ensureServer = { r.calls += "ensure"; epoch = 2L; Result.success(Unit) },
                execute = { r.calls += "execute"; r.executed = true; ChrootCliRun(0, "ok\n") },
            ).exec(request)
        }
        assertEquals(ChrootCliRefusal.ROOT_REVOKED, (outcome as ChrootCliOutcome.Refused).reason)
        assertEquals(listOf("ensure"), r.calls)
        assertFalse(r.executed)
    }
}
