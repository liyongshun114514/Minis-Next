package com.openminis.app.sandbox

import com.openminis.app.ProductionSources
import org.junit.Assert.assertTrue
import org.junit.Test

/** Drift guards for Android-only dispatch paths; not a device execution test. */
class RootExecutionWiringTest {
    @Test fun chrootCannotReuseTheWarmShellForLaterUnconfirmedCommands() {
        val src = ProductionSources.read("sandbox/ExecutionCoordinator.kt")
        val at = src.indexOf("val strategy = if (")
        assertTrue(at >= 0)
        val branch = src.substring(at, src.indexOf("val spawned", at))
        assertTrue(branch.contains("::appContext.isInitialized"))
        assertTrue(branch.contains("SandboxBackendStore.selected(appContext) == SandboxBackend.CHROOT"))
        assertTrue(branch.contains("ShellExecutionStrategy.FRESH_PROCESS"))
        assertTrue(src.contains("strategy == ShellExecutionStrategy.FRESH_PROCESS"))
    }

    @Test fun shellExecutorReportsPermissionRefusalWithoutStartingAClient() {
        val src = ProductionSources.read("sandbox/ShellExecutor.kt")
        val refusal = src.indexOf("catch (e: ChrootKernel.RootExecRefusedException)")
        val process = src.indexOf("val processBuilder = ProcessBuilder(spawn.argv)")
        assertTrue(refusal >= 0 && process > refusal)
        val block = src.substring(refusal, process)
        assertTrue(block.contains("return@withContext ShellResult("))
        assertTrue(block.contains("exitCode = 126"))
    }

    @Test fun allPermissionSurfacesUseTheRootLabelAndRevokePath() {
        val settings = ProductionSources.read("ui/settings/OffloadPermissionScreen.kt")
        val menu = ProductionSources.read("ui/chat/ComposerPermissionMenu.kt")
        assertTrue(settings.contains("permissionLevelDisplayName(toolName, currentLevel)"))
        assertTrue(settings.contains("permissionLevelDisplayName(toolName, level)"))
        assertTrue(settings.contains("RootManager.setAccessLevel("))
        assertTrue(menu.contains("RootManager.setAccessLevel(selection)"))
    }
}
