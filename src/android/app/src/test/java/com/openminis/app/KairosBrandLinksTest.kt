package com.openminis.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * [Minis Next brand] Source + constant contract for the fork's user-facing
 * contact entry points.
 *
 * Guards the split of responsibilities:
 *  - every contact / community / update link points at the Minis Next fork;
 *  - the original OpenMinis project stays linked as upstream (GPL-3.0
 *    attribution must not be dropped);
 *  - About + Feedback keep using [KairosBrandLinks] instead of hard-coded
 *    upstream URLs.
 */
class KairosBrandLinksTest {

    @Test
    fun kairosContactLinksPointAtFork() {
        assertEquals("Minis Next", KairosBrandLinks.MAINTAINER)

        assertEquals("https://github.com/liyongshun114514/Minis-Next", KairosBrandLinks.REPOSITORY_URL)
        assertEquals("https://github.com/liyongshun114514/Minis-Next/issues", KairosBrandLinks.ISSUES_URL)
        assertEquals("https://github.com/liyongshun114514/Minis-Next/issues/new", KairosBrandLinks.NEW_ISSUE_URL)
        assertEquals("https://github.com/liyongshun114514/Minis-Next/releases", KairosBrandLinks.RELEASES_URL)

        assertEquals("liyongshun114514@gmail.com", KairosBrandLinks.EMAIL)
        assertEquals("https://t.me/LYS114514", KairosBrandLinks.TELEGRAM_URL)

        // Every contact URL is https (mailto handled by the email constant).
        listOf(
            KairosBrandLinks.REPOSITORY_URL,
            KairosBrandLinks.ISSUES_URL,
            KairosBrandLinks.NEW_ISSUE_URL,
            KairosBrandLinks.RELEASES_URL,
            KairosBrandLinks.TELEGRAM_URL,
        ).forEach { url ->
            assertTrue("not https: $url", url.startsWith("https://"))
            assertFalse("trailing slash: $url", url.endsWith("/"))
        }
    }

    @Test
    fun upstreamAttributionIsPreservedAndSeparate() {
        assertEquals("https://github.com/OpenMinis/OpenMinis", KairosBrandLinks.UPSTREAM_REPOSITORY_URL)
        assertEquals("https://github.com/OpenMinis/OpenMinis/issues", KairosBrandLinks.UPSTREAM_ISSUES_URL)

        // The upstream project must never be presented as the fork's own repo.
        assertTrue(KairosBrandLinks.UPSTREAM_REPOSITORY_URL != KairosBrandLinks.REPOSITORY_URL)
        assertFalse(KairosBrandLinks.UPSTREAM_REPOSITORY_URL.contains("liyongshun114514"))
        assertFalse(KairosBrandLinks.REPOSITORY_URL.contains("OpenMinis/OpenMinis"))
    }

    /**
     * UpdateChecker is deliberately NOT imported: that object drags in the
     * whole Android/provider stack, which makes this class impossible to
     * compile in a source-subset Kotlin/JUnit runner. The contract is still
     * real — the release URL is read straight out of UpdateChecker's source,
     * so any drift (upstream URL, different constant) fails here.
     */
    @Test
    fun updateCheckerReleasesUrlFollowsFork() {
        val updateChecker = sourceFile("data/UpdateChecker.kt")
        assumeTrue("UpdateChecker.kt not found from ${File("").absolutePath}", updateChecker != null)
        val src = updateChecker!!.readText()

        val match = Regex("const val RELEASES_URL: String = \"([^\"]+)\"").find(src)
        assertTrue("UpdateChecker.RELEASES_URL declaration not found", match != null)
        val releasesUrl = match!!.groupValues[1]

        assertEquals(KairosBrandLinks.RELEASES_URL, releasesUrl)
        assertFalse("UpdateChecker releases point upstream", releasesUrl.contains("OpenMinis/OpenMinis"))
        assertFalse(
            "UpdateChecker still hard-codes the upstream repository",
            src.contains("github.com/OpenMinis/OpenMinis"),
        )
    }

    @Test
    fun aboutAndFeedbackScreensUseKairosLinksAndKeepUpstream() {
        val about = sourceFile("ui/settings/AboutScreen.kt")
        assumeTrue("AboutScreen.kt not found from ${File("").absolutePath}", about != null)
        val aboutText = about!!.readText()
        assertTrue("KairosBrandLinks.REPOSITORY_URL missing", aboutText.contains("KairosBrandLinks.REPOSITORY_URL"))
        assertTrue("KairosBrandLinks.NEW_ISSUE_URL missing", aboutText.contains("KairosBrandLinks.NEW_ISSUE_URL"))
        assertTrue("KairosBrandLinks.EMAIL missing", aboutText.contains("KairosBrandLinks.EMAIL"))
        assertTrue("KairosBrandLinks.TELEGRAM_URL missing", aboutText.contains("KairosBrandLinks.TELEGRAM_URL"))
        assertTrue(
            "upstream attribution row missing",
            aboutText.contains("KairosBrandLinks.UPSTREAM_REPOSITORY_URL"),
        )
        assertFalse(
            "hard-coded upstream repo URL still used as a link target",
            aboutText.contains("openExternalUrl(context, \"https://github.com/OpenMinis/OpenMinis\")"),
        )

        val settings = sourceFile("ui/settings/SettingsScreen.kt")
        assumeTrue("SettingsScreen.kt not found", settings != null)
        val settingsText = settings!!.readText()
        assertTrue("feedback sheet not using Minis Next email", settingsText.contains("KairosBrandLinks.EMAIL"))
        assertTrue("feedback sheet not using Minis Next Telegram", settingsText.contains("KairosBrandLinks.TELEGRAM_URL"))
        assertTrue("feedback issue URL not using Minis Next", settingsText.contains("KairosBrandLinks.NEW_ISSUE_URL"))
        assertTrue("feedback sheet lost upstream entry", settingsText.contains("KairosBrandLinks.UPSTREAM_REPOSITORY_URL"))
        assertFalse("old Telegram invite still present", settingsText.contains("t.me/+2NzhOJuzRyI1YmM1"))
        assertFalse("upstream mailbox still present", settingsText.contains("dev@openminis.app"))
    }

    private fun sourceFile(relative: String): File? {
        var dir: File? = File("").absoluteFile
        repeat(5) {
            val candidate = File(dir, "src/main/java/com/openminis/app/$relative")
            if (candidate.isFile) return candidate
            dir = dir?.parentFile
        }
        return null
    }
}
