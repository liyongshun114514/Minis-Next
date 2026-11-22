package com.openminis.app.ui.chat

import android.content.Context
import com.openminis.app.sandbox.PRootKernel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * [T-skills-link-blank-webview] Tapping a skills link in chat used to open the
 * preview WebView with a `minis://skills/...` (or `/var/minis/skills/...`) URL
 * when the host file could not be found, and the WebView painted a blank page.
 *
 * The confirmed cause is resolution depending on `PRootKernel.bindMounts`: on a
 * cold start under the CHROOT backend no PRoot shell has booted, the table is
 * empty, and the answer was a rootfs path that never exists. Skills are global,
 * so they must resolve straight from `context.filesDir/minis-global/skills`.
 *
 * These tests run with [PRootKernel.bindMounts] cleared and never boot PRoot —
 * exactly the cold-start shape.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SkillLinkPreviewTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var skillsRoot: File

    @Before
    fun setUp() {
        PRootKernel.clearBindMounts()
        skillsRoot = File(ctx.filesDir, "minis-global/skills").also { it.mkdirs() }
    }

    @After
    fun tearDown() {
        PRootKernel.clearBindMounts()
    }

    private fun skill(relative: String): File =
        File(skillsRoot, relative).also { file ->
            file.parentFile?.mkdirs()
            file.writeText("# skill\n")
        }

    private fun resolveAction(url: String, sessionId: String? = "session-1") =
        ChatLinkResolver.resolve(url, sessionId, ctx)

    private fun resolvedFile(url: String, sessionId: String? = "session-1"): File {
        val action = resolveAction(url, sessionId)
        assertTrue("expected SandboxFile for $url, got $action", action is ChatLinkAction.SandboxFile)
        return (action as ChatLinkAction.SandboxFile).item.file
    }

    // --- existence, with no mount table at all -----------------------------

    @Test
    fun absoluteSkillsPathResolvesWithoutAnyMountTable() {
        val expected = skill("demo/SKILL.md")
        assertTrue("cold-start precondition: mount table empty", PRootKernel.bindMounts.isEmpty())

        val actual = resolvedFile("/var/minis/skills/demo/SKILL.md")

        assertEquals(expected.canonicalPath, actual.canonicalPath)
        assertTrue("resolution must not populate the mount table", PRootKernel.bindMounts.isEmpty())
    }

    @Test
    fun minisSchemeSkillsPathResolvesWithoutAnyMountTable() {
        val expected = skill("demo/SKILL.md")
        assertEquals(
            expected.canonicalPath,
            resolvedFile("minis://skills/demo/SKILL.md").canonicalPath,
        )
        assertTrue(PRootKernel.bindMounts.isEmpty())
    }

    @Test
    fun fileUrlIntoVarMinisSkillsResolvesToHostFile() {
        val expected = skill("demo/SKILL.md")
        assertEquals(
            expected.canonicalPath,
            resolvedFile("file:///var/minis/skills/demo/SKILL.md").canonicalPath,
        )
    }

    @Test
    fun skillsFolderLinkResolvesWithoutAnyMountTable() {
        File(skillsRoot, "demo").mkdirs()
        assertTrue(PRootKernel.bindMounts.isEmpty())
        val action = resolveAction("minis://skills/demo")
        assertTrue("expected SandboxFolder, got $action", action is ChatLinkAction.SandboxFolder)
        assertEquals("/var/minis/skills/demo", (action as ChatLinkAction.SandboxFolder).linuxPath)
    }

    @Test
    fun absoluteEncodedSkillsPathResolves() {
        val expected = skill("a+b/中文/SKILL.md")
        assertEquals(expected.canonicalPath,
            resolvedFile("/var/minis/skills/a+b/%E4%B8%AD%E6%96%87/SKILL.md").canonicalPath)
    }

    @Test
    fun unresolvedResourcesAreGuardedBeforeOpeningWebPreview() {
        val chat = com.openminis.app.ProductionSources.read("ui/chat/ChatScreen.kt")
        val preview = com.openminis.app.ProductionSources.read("ui/sandbox/FilePreviewScreen.kt")
        assertTrue(chat.contains("ChatLinkResolver.isSandboxUrl(action.url)"))
        assertTrue(preview.contains("ChatLinkResolver.isSandboxUrl(action.url)"))
        assertTrue(chat.contains("R.string.filepreview_file_read_error"))
        assertTrue(preview.contains("R.string.filepreview_file_read_error"))
    }

    // --- encoding ----------------------------------------------------------

    @Test
    fun percentEncodedCjkPathResolves() {
        val expected = skill("中文技能/SKILL.md")
        assertEquals(
            expected.canonicalPath,
            resolvedFile("minis://skills/%E4%B8%AD%E6%96%87%E6%8A%80%E8%83%BD/SKILL.md").canonicalPath,
        )
    }

    @Test
    fun doubleEncodedCjkPathResolves() {
        val expected = skill("中文技能/SKILL.md")
        assertEquals(
            expected.canonicalPath,
            resolvedFile(
                "minis://skills/%25E4%25B8%25AD%25E6%2596%2587" +
                    "%25E6%258A%2580%25E8%2583%25BD/SKILL.md",
            ).canonicalPath,
        )
    }

    @Test
    fun plusInDirectoryNameIsNotDecodedAsSpace() {
        val expected = skill("a+b/SKILL.md")
        assertEquals(
            expected.canonicalPath,
            resolvedFile("minis://skills/a+b/SKILL.md").canonicalPath,
        )
        // Nothing was ever written as "a b" — only the '+' name exists.
        assertFalse(File(skillsRoot, "a b/SKILL.md").exists())
    }

    @Test
    fun fileUrlKeepsPlusAndDecodesCjk() {
        val expected = skill("a+b/中文/SKILL.md")
        assertEquals(
            expected.canonicalPath,
            resolvedFile("file:///var/minis/skills/a+b/%E4%B8%AD%E6%96%87/SKILL.md").canonicalPath,
        )
    }

    // --- the blank-page case ----------------------------------------------

    @Test
    fun missingSkillFileFallsBackToWebButIsRecognisedAsSandbox() {
        val action = resolveAction("minis://skills/missing/SKILL.md")
        assertTrue("expected Web fallback, got $action", action is ChatLinkAction.Web)
        assertTrue(
            ChatLinkResolver.isSandboxUrl((action as ChatLinkAction.Web).url),
        )
    }

    @Test
    fun missingAbsoluteSkillPathIsRecognisedAsSandbox() {
        val action = resolveAction("/var/minis/skills/missing/SKILL.md")
        assertTrue(action is ChatLinkAction.Web)
        assertTrue(ChatLinkResolver.isSandboxUrl((action as ChatLinkAction.Web).url))
    }

    @Test
    fun sandboxHelperDoesNotCaptureRealWebOrExternalLinks() {
        assertFalse(ChatLinkResolver.isSandboxUrl("https://example.com/skills/SKILL.md"))
        assertFalse(ChatLinkResolver.isSandboxUrl("http://example.com"))
        assertFalse(ChatLinkResolver.isSandboxUrl("mailto:someone@example.com"))
        assertFalse(ChatLinkResolver.isSandboxUrl("tel:10086"))
        assertFalse(ChatLinkResolver.isSandboxUrl("minis://settings/appearance"))
        val hostOnly = File(ctx.cacheDir, "page.html").also { it.writeText("<html></html>") }
        assertFalse(ChatLinkResolver.isSandboxUrl("file://${hostOnly.absolutePath}"))
        val web = resolveAction("https://example.com/a")
        assertTrue("http(s) must still reach the WebView", web is ChatLinkAction.Web)
    }

    // --- isolation / no guessing ------------------------------------------

    @Test
    fun perSessionPathNeverBorrowsAnotherSession() {
        val otherSession = File(
            ctx.filesDir,
            "minis-sessions/other-session/attachments/photo.png",
        ).also {
            it.parentFile?.mkdirs()
            it.writeText("png")
        }
        assertTrue(otherSession.exists())

        val action = resolveAction("/var/minis/attachments/photo.png", "session-1")

        assertTrue("must not resolve to another chat's file, got $action", action is ChatLinkAction.Web)
        assertNull(ChatLinkResolver.globalHostFile("/var/minis/attachments/photo.png", ctx))
        assertTrue(ChatLinkResolver.isSandboxUrl((action as ChatLinkAction.Web).url))
    }

    @Test
    fun traversalOutsideTheSkillsBaseIsRejected() {
        File(ctx.filesDir, "minis-global/secret.txt").also { it.writeText("s") }

        assertNull(ChatLinkResolver.globalHostFile("/var/minis/skills/../secret.txt", ctx))
        val encoded = resolveAction("minis://skills/%2E%2E/secret.txt")
        assertTrue("encoded traversal must not resolve, got $encoded", encoded is ChatLinkAction.Web)
        assertTrue(ChatLinkResolver.isSandboxUrl((encoded as ChatLinkAction.Web).url))
    }

    @Test
    fun globalHostFileNeedsAContextAndGlobalSubdir() {
        assertNull(ChatLinkResolver.globalHostFile("/var/minis/skills/x/SKILL.md", null))
        assertNull(ChatLinkResolver.globalHostFile("/var/minis/workspace/x.png", ctx))
    }

    // --- mount table is a fallback, not the source of truth ---------------

    @Test
    fun staleMountTableEntryDoesNotHideTheRealFile() {
        val expected = skill("demo/SKILL.md")
        PRootKernel.addBindMount(
            "/var/minis/skills",
            File(ctx.filesDir, "minis-global/not-here").absolutePath,
        )

        assertEquals(
            expected.canonicalPath,
            resolvedFile("minis://skills/demo/SKILL.md").canonicalPath,
        )
    }
}
