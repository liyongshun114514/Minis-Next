package com.openminis.app.agent.prompts

import com.openminis.app.ProductionSources
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [K-system-prompt-scope] Pins the boundary between "system prompt setting" and
 * "text some feature owns".
 *
 * The catalog is generated from every `PromptOverrides.render(...)` call site, so
 * it keeps growing when a tool or a scenario gains text. If such a group were
 * shown on Settings → System Prompt, the page would silently go back to listing
 * per-tool parameter descriptions. These assertions fail on that drift: the
 * global set is explicit, the excluded groups still exist in the asset (they
 * were not deleted), and the hub filters through [PromptCatalogScope] instead of
 * keeping a second copy of the list.
 */
class PromptCatalogScopeTest {

    private val catalog: JSONArray = run {
        val main = ProductionSources.mainRoot()!!.parentFile!!.parentFile!!.parentFile!!.parentFile!!
        JSONArray(File(main, "assets/prompt-catalog.json").readText())
    }

    private fun rows(): List<JSONObject> = (0 until catalog.length()).map { catalog.getJSONObject(it) }

    private fun PromptCatalogScope.isGlobal(row: JSONObject): Boolean = isGlobal(
        PromptEntry(
            id = row.getString("id"),
            group = row.getString("group"),
            title = row.getString("title"),
            source = row.getString("source"),
            defaultTemplate = row.getString("defaultTemplate"),
        ),
    )

    @Test
    fun `the global group list is the documented eight`() {
        assertEquals(
            listOf("身份与全局", "主系统提示词", "工作区指令", "隐私提示", "记忆", "技能", "计划任务", "MCP"),
            PromptCatalogScope.GLOBAL_GROUPS,
        )
    }

    @Test
    fun `every global group exists in the shipped catalog`() {
        val groups = rows().map { it.getString("group") }.toSet()
        assertEquals(emptyList<String>(), PromptCatalogScope.GLOBAL_GROUPS.filterNot { it in groups })
    }

    @Test
    fun `the hub lists only the global entries`() {
        val all = rows()
        val global = all.filter { PromptCatalogScope.isGlobal(it) }
        assertEquals(28, global.size)
        assertTrue("the catalog stays complete", all.size > global.size)
        assertEquals(global.size, global.map { it.getString("id") }.toSet().size)
        assertTrue(global.all { it.getString("defaultTemplate").isNotBlank() })
        assertTrue(global.all { it.getString("group") in PromptCatalogScope.GLOBAL_GROUPS })
    }

    @Test
    fun `tool and scenario groups stay in the catalog but out of the page`() {
        val byGroup = rows().groupingBy { it.getString("group") }.eachCount()
        // Every one of these was visible on the page before [K-system-prompt-scope].
        val excluded = listOf(
            "工具定义" to 86,
            "Shell语法规则" to 44,
            "语音" to 40,
            "运行提醒" to 35,
            "视觉" to 32,
            "标题与分组" to 25,
            "子代理" to 23,
            "辅助模型任务" to 15,
            "上下文卸载" to 13,
            "上下文压缩" to 10,
            "连接测试" to 2,
            "图像生成" to 2,
        )
        for ((group, count) in excluded) {
            assertEquals("$group must still ship", count, byGroup[group])
            assertFalse("$group must not be a system-prompt group", group in PromptCatalogScope.GLOBAL_GROUPS)
        }
        assertTrue(rows().none { it.getString("group") == "工具定义" && PromptCatalogScope.isGlobal(it) })
    }

    @Test
    fun `the hub filters through the shared scope instead of its own list`() {
        val hub = ProductionSources.read("ui/settings/PromptSettingsScreen.kt")
        assertTrue(hub.contains("PromptCatalogScope.globalOnly(it)"))
        assertTrue(hub.contains("import com.openminis.app.agent.prompts.PromptCatalogScope"))
        assertFalse("a second copy of the group list would drift", hub.contains("\"主系统提示词\""))
    }

    @Test
    fun `an unknown new group is not silently promoted to the system prompt`() {
        assertTrue(PromptCatalogScope.globalOnly(listOf(PromptEntry("x", "某个新工具", "t", "s", "d"))).isEmpty())
    }
}