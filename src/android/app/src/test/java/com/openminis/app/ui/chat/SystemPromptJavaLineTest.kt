package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-java-prompt] The system prompt says how to install a JDK, right
 * after the Python packages note (iOS a0207db08). Android sets no
 * JAVA_TOOL_OPTIONS (measured: C1-only made a 40-file javac ~25% slower on
 * native arm64), so the iOS sentences about that variable are left out.
 */
class SystemPromptJavaLineTest {
    private val vm by lazy {
        val main = ProductionSources.mainRoot()!!.parentFile!!.parentFile!!.parentFile!!.parentFile!!
        val entries = org.json.JSONArray(java.io.File(main, "assets/prompt-catalog.json").readText())
        (0 until entries.length()).map { entries.getJSONObject(it).getString("defaultTemplate") }
            .first { it.startsWith("You should proactively use shell commands") }
    }

    @Test
    fun `the JDK line follows the Python note once and mentions no JAVA_TOOL_OPTIONS`() {
        val line = "- Java: install a JDK with `apt-get install -y openjdk-21-jdk-headless` (or openjdk-17-jdk-headless for older projects). " +
            "JDK 8's javac needs the `-d` output directory to exist.\n"
        assertEquals(1, vm.split(line).size - 1)
        val python = vm.indexOf("- Python packages: use Ubuntu packages when suitable:")
        val java = vm.indexOf(line)
        assertTrue(python in 0 until java)
        assertFalse(vm.substring(python, java).contains("\n- "))
        assertFalse(vm.contains("JAVA_TOOL_OPTIONS"))
    }
}
