package com.openminis.app.groups

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-model-group-avatar] Catalog parsing is per-entry tolerant (one bad entry
 * never kills the list) and the SHIPPED catalog actually satisfies the
 * contract this code assumes: unique safe ids, existing 192px PNG files, and
 * Chinese aliases that make Chinese group names match.
 */
class ModelGroupAvatarCatalogTest {

    @Test
    fun `one malformed or unsafe entry never kills the catalog`() {
        val json = """
            [
              {"id":"OpenAI","name":"OpenAI","aliases":[" gpt ","","gpt","gpt"],"file":"openai.png"},
              {"id":"openai","name":"Duplicate","aliases":[],"file":"dup.png"},
              {"id":"traversal","name":"Bad","aliases":[],"file":"../escape.png"},
              {"id":"svg","name":"Bad","aliases":[],"file":"openai.svg"},
              {"id":"","name":"No id","aliases":[],"file":"x.png"},
              {"id":"ok","aliases":[],"file":"ok.png"},
              {"id":123}
            ]
        """.trimIndent()
        val catalog = ModelIconCatalog.parse(json)
        assertEquals(2, catalog.entries.size)
        val openai = catalog.entry("OPENAI")
        assertNotNull(openai)
        assertEquals("openai", openai!!.id)
        assertEquals("OpenAI", openai.name)
        assertEquals(listOf("gpt"), openai.aliases)
        assertEquals("ok", catalog.entry("ok")?.name)
    }

    @Test
    fun `a broken root or an empty array is an empty catalog`() {
        assertTrue(ModelIconCatalog.parse("not json").entries.isEmpty())
        assertTrue(ModelIconCatalog.parse("{}").entries.isEmpty())
        assertTrue(ModelIconCatalog.parse("[]").entries.isEmpty())
        assertTrue(ModelIconCatalog.parse("").entries.isEmpty())
    }

    @Test
    fun `the shipped catalog is complete, safe and 192px`() {
        val dir = assetDir()
        val catalog = ModelIconCatalog.parse(File(dir, "catalog.json").readText())
        assertEquals("catalog entry count", 42, catalog.entries.size)
        assertEquals("unique ids", 42, catalog.entries.map { it.id }.toSet().size)
        for (entry in catalog.entries) {
            val file = File(dir, entry.file)
            assertTrue("missing icon for ${entry.id}: ${entry.file}", file.isFile)
            val head = file.readBytes().copyOfRange(0, 24)
            assertTrue("${entry.file} is not a PNG", ModelGroupAvatarValue.isPng(head))
            assertEquals(192, bigEndianInt(head, 16))
            assertEquals(192, bigEndianInt(head, 20))
        }
        for (id in listOf("openai", "anthropic", "claude", "gemini", "qwen", "deepseek", "doubao")) {
            assertNotNull("catalog lost $id", catalog.entry(id))
        }
    }

    @Test
    fun `the shipped catalog auto-matches Chinese group names`() {
        val catalog = ModelIconCatalog.parse(File(assetDir(), "catalog.json").readText())
        val expected = mapOf(
            "通义千问" to "qwen",
            "通义千问主力" to "qwen",
            "千问 高并发" to "qwen",
            "豆包" to "doubao",
            "豆包 日常" to "doubao",
            "智谱" to "zhipu",
            "智谱清言" to "zhipu",
            "文心一言" to "baidu",
            "文心" to "baidu",
            "混元" to "tencent",
            "腾讯混元" to "tencent",
            "硅基流动" to "siliconcloud",
            "月之暗面" to "moonshot",
            "深度求索" to "deepseek",
            "谷歌" to "google",
            "英伟达" to "nvidia",
            "小米" to "xiaomi",
        )
        for ((name, id) in expected) {
            assertEquals(name, id, ModelGroupAvatarResolver.autoMatch(name, catalog)?.id)
        }
    }

    private fun bigEndianInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun assetDir(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val direct = File(dir, "src/main/assets/model-icons")
            if (direct.isDirectory) return direct
            val viaModule = File(dir, "src/android/app/src/main/assets/model-icons")
            if (viaModule.isDirectory) return viaModule
            val viaAndroid = File(dir, "app/src/main/assets/model-icons")
            if (viaAndroid.isDirectory) return viaAndroid
            dir = dir.parentFile
        }
        error("model-icons assets not found from ${File("").absolutePath}")
    }
}