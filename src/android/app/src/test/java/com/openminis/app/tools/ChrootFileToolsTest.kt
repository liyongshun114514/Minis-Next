package com.openminis.app.tools

import android.app.Application
import com.openminis.app.ProductionSources
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real production transport and bundled Python helper; no Root or simulated su. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ChrootFileToolsTest {
    private val helper: File get() = File(ProductionSources.mainRoot()!!,
        "../../../../assets/file-tools/chroot_file_tools.py").canonicalFile

    private fun fixture(block: suspend (File) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("chroot-file-bridge-").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }

    private suspend fun call(root: File, op: String, args: JSONObject): ToolExecutionResult =
        ChrootFileTools.runStaged(root, op, args, helper.readBytes(), "test") { command, job ->
            // Private content never appears in approval argv, only operation and target.
            if (args.has("content")) assertFalse(command.contains(args.getString("content").takeIf { it.isNotEmpty() } ?: "NEVER-PRESENT"))
            val response = File(job, "response.json")
            val inode = Files.getAttribute(response.toPath(), "unix:ino")
            val p = ProcessBuilder("python3", "-I", File(job, "helper.py").path,
                File(job, "request.json").path, response.path).redirectErrorStream(true).start()
            assertTrue("helper process did not finish", p.waitFor(10, TimeUnit.SECONDS))
            val output = p.inputStream.bufferedReader().readText()
            assertEquals("helper stdout/stderr must stay empty on normal responses", "", output)
            assertEquals("response must retain the App-owned inode", inode, Files.getAttribute(response.toPath(), "unix:ino"))
            ChrootFileTools.Exit(p.exitValue(), output)
        }

    @Test fun writeAppendReadAndEditUseTheRealHelper() = fixture { root ->
        assertTrue(helper.isFile)
        val target = File(root, "target space/中文.txt")
        val a = JSONObject().put("path", target.path).put("content", "private-payload-中文\n")
        assertTrue(call(root, "write", a).success)
        assertEquals("private-payload-中文\n", target.readText())
        val ino = Files.getAttribute(target.toPath(), "unix:ino")
        val mode = Files.getAttribute(target.toPath(), "unix:mode")
        assertTrue(call(root, "write", JSONObject().put("path", target.path).put("content", "suffix🌍").put("append", true)).success)
        val r = call(root, "read", JSONObject().put("path", target.path).put("offset", 2))
        assertTrue(r.success)
        assertTrue(r.output.endsWith("suffix🌍"))
        val e = call(root, "edit", JSONObject().put("path", target.path).put("old_string", "suffix").put("new_string", "改好"))
        assertTrue(e.output, e.success)
        assertEquals("private-payload-中文\n改好🌍", target.readText())
        assertEquals(ino, Files.getAttribute(target.toPath(), "unix:ino"))
        assertEquals(mode, Files.getAttribute(target.toPath(), "unix:mode"))
        assertTrue(File(root, ChrootFileTools.STAGING).listFiles()!!.isEmpty())
    }

    @Test fun directoryCreationFailureDoesNotModifyBlocker() = fixture { root ->
        val blocker = File(root, "block").apply { writeText("keep") }
        val r = call(root, "write", JSONObject().put("path", File(blocker, "nested/out").path).put("content", "new"))
        assertFalse(r.success)
        assertTrue(r.output.contains("failed path"))
        assertEquals("keep", blocker.readText())
        assertTrue(File(root, ChrootFileTools.STAGING).listFiles()!!.isEmpty())
    }

    @Test fun failedSpawnDoesNotClaimSuccessOrRetry() = fixture { root ->
        var calls = 0
        val r = ChrootFileTools.runStaged(root, "write", JSONObject().put("path", "/target"), helper.readBytes(), "t") { _, _ ->
            calls++; ChrootFileTools.Exit(126, "Root request denied")
        }
        assertFalse(r.success); assertEquals(1, calls)
        assertTrue(r.output.contains("126"))
        assertTrue(File(root, ChrootFileTools.STAGING).listFiles()!!.isEmpty())
    }

    @Test fun invalidAndEmptyResponsesCannotBecomeSuccessfulWrites() = fixture { root ->
        for (payload in listOf("", "not json", "{\"success\":true}", "{\"success\":\"true\",\"text\":\"x\"}")) {
            val r = runCatching {
                ChrootFileTools.runStaged(root, "write", JSONObject().put("path", "/target"), helper.readBytes(), "t") { _, job ->
                    File(job, "response.json").writeText(payload); ChrootFileTools.Exit(0)
                }
            }
            assertTrue(r.isFailure || r.getOrThrow().success == false)
            assertTrue(File(root, ChrootFileTools.STAGING).listFiles()!!.isEmpty())
        }
    }

    @Test fun oversizedResponseAndTimeoutAreExplicitFailures() = fixture { root ->
        val r = ChrootFileTools.runStaged(root, "read", JSONObject().put("path", "/target"), helper.readBytes(), "t") { _, job ->
            File(job, "response.json").writeBytes(ByteArray((ChrootFileTools.MAX_RESPONSE_BYTES + 1).toInt()))
            ChrootFileTools.Exit(0)
        }
        assertFalse(r.success)
        val timeout = ChrootFileTools.runStaged(root, "write", JSONObject().put("path", "/target"), helper.readBytes(), "t") { _, _ -> ChrootFileTools.Exit(124) }
        assertFalse(timeout.success); assertTrue(timeout.timedOut)
    }

    @Test fun toolsRouteBeforeAppStatAndKeepWriteGuards() {
        for ((name, op) in listOf("FileReadTool" to "read", "FileWriteTool" to "write", "FileEditTool" to "edit")) {
            val s = ProductionSources.read("tools/$name.kt")
            val route = s.indexOf("ChrootFileTools.execute(\"$op\"")
            assertTrue(route >= 0)
            assertTrue(route < s.indexOf("PRootKernel.resolveSessionHostPath"))
            if (op != "read") {
                assertTrue(s.indexOf("isLinuxPathUnderReadOnlyMount") < route)
                assertTrue(s.indexOf("isOffloadPlaceholder") < route)
            }
        }
        val bridge = ProductionSources.read("tools/ChrootFileTools.kt")
        assertTrue(bridge.contains("ChrootKernel.spawn(context, SandboxCommandSpec("))
        assertTrue(bridge.contains("rootRequired = true"))
        assertTrue(bridge.contains("sessionMounts = mounts.mounts"))
        assertTrue(bridge.contains("SessionMounts.forContext(context, sessionId)"))
        assertTrue(bridge.contains("Active(executionSessionId)"))
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        for (tool in listOf("FileReadTool", "FileWriteTool", "FileEditTool")) {
            assertTrue(vm.contains("$tool.execute(argsJson, fsSessionId, context, executionSessionId = activeSessionId)"))
        }
    }
}
