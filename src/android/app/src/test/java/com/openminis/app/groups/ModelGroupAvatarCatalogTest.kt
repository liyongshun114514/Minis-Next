package com.openminis.app.groups

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

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

    @Test
    fun `mono is parsed and defaults to off`() {
        val catalog = ModelIconCatalog.parse(
            """
            [
              {"id":"colour","name":"Colour","file":"colour.png"},
              {"id":"dark","name":"Dark","file":"dark.png","mono":true},
              {"id":"off","name":"Off","file":"off.png","mono":false}
            ]
            """.trimIndent(),
        )
        assertEquals(3, catalog.entries.size)
        assertFalse(catalog.entry("colour")!!.mono)
        assertTrue(catalog.entry("dark")!!.mono)
        assertFalse(catalog.entry("off")!!.mono)
    }

    /**
     * A real pixel check of the SHIPPED PNGs (JDK ImageIO, no Android
     * graphics needed): the renderer now draws every builtin icon on a
     * transparent tile, so an opaque backdrop baked into a file would show up
     * as a light square in the app. Corners must be fully transparent and the
     * mark must actually cover part of the tile.
     */
    @Test
    fun `every shipped icon is a transparent 192px PNG`() {
        val dir = assetDir()
        val catalog = ModelIconCatalog.parse(File(dir, "catalog.json").readText())
        for (entry in catalog.entries) {
            val image = ImageIO.read(File(dir, entry.file))
            assertNotNull("${entry.file} is not decodable", image)
            assertEquals("${entry.file} width", 192, image!!.width)
            assertEquals("${entry.file} height", 192, image.height)
            for ((x, y) in listOf(0 to 0, 191 to 0, 0 to 191, 191 to 191)) {
                assertEquals(
                    "${entry.file} corner ($x,$y) is not transparent",
                    0,
                    alpha(image, x, y),
                )
            }
            val opaque = countPixels(image) { a, _ -> a > 8 }
            assertTrue("${entry.file} is empty", opaque > image.width * image.height * 3 / 100)
        }
    }

    /**
     * The regression this task fixes: `gemini.png` was the Mono glyph painted
     * black (the gradient overlays of Lobe's `Color` component lost their
     * `fill`), so Gemini rendered as a monochrome star on a light tile. The
     * shipped asset must be the multicolour mark and must not be flagged mono.
     */
    @Test
    fun `gemini ships the multicolour mark, not the mono glyph`() {
        val dir = assetDir()
        val catalog = ModelIconCatalog.parse(File(dir, "catalog.json").readText())
        val gemini = requireNotNull(catalog.entry("gemini")) { "catalog lost gemini" }
        assertFalse("gemini must be drawn untinted", gemini.mono)

        val image = requireNotNull(ImageIO.read(File(dir, gemini.file)))
        var opaque = 0
        var nearBlack = 0
        var red = 0
        var green = 0
        var blue = 0
        var yellow = 0
        forEachPixel(image) { a, rgb ->
            if (a <= 8) return@forEachPixel
            opaque++
            val r = (rgb shr 16) and 0xFF
            val g = (rgb shr 8) and 0xFF
            val b = rgb and 0xFF
            if (maxOf(r, g, b) < 48) nearBlack++
            if (r > 180 && g < 120 && b < 120) red++
            if (g > 130 && r < 120 && b < 160) green++
            if (b > 200 && r < 100) blue++
            if (r > 200 && g > 150 && b < 80) yellow++
        }
        assertTrue("gemini.png has no opaque pixels", opaque > 0)
        assertTrue(
            "gemini.png is still a black overlay (near-black ${nearBlack * 100 / opaque}%)",
            nearBlack * 100 <= opaque * 5,
        )
        // Lobe's Gemini Color mark is a blue star with red, green and yellow
        // gradient accents; all four families must be present. A mono glyph
        // has none of them.
        for ((name, count) in listOf(
            "red" to red, "green" to green, "blue" to blue, "yellow" to yellow,
        )) {
            assertTrue("gemini.png lost its $name accent", count > 0)
        }
    }

    /**
     * `mono` is a claim about the shipped pixels, not a free-form tag: the
     * renderer tints mono entries and leaves colour entries alone, so a
     * regenerated colour asset with a stale flag (or the reverse) would render
     * wrong. Saturation is the discriminator: mono marks are near-black, the
     * least colourful colour mark in the set is Cohere (~41% saturated
     * pixels), and the most colourful mono mark is Meta (~26%).
     */
    @Test
    fun `the mono flag agrees with the shipped pixels`() {
        val dir = assetDir()
        val catalog = ModelIconCatalog.parse(File(dir, "catalog.json").readText())
        for (entry in catalog.entries) {
            val image = requireNotNull(ImageIO.read(File(dir, entry.file)))
            var opaque = 0
            var vivid = 0
            forEachPixel(image) { a, rgb ->
                if (a <= 8) return@forEachPixel
                opaque++
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                if (maxOf(r, g, b) - minOf(r, g, b) > 40 && maxOf(r, g, b) > 80) vivid++
            }
            val looksMono = vivid * 100 < opaque * 35
            assertEquals(
                "${entry.id}: mono=${entry.mono} but ${vivid * 100 / opaque}% of pixels are saturated",
                entry.mono,
                looksMono,
            )
        }
    }

    private fun alpha(image: BufferedImage, x: Int, y: Int): Int =
        (image.getRGB(x, y) ushr 24) and 0xFF

    private inline fun countPixels(image: BufferedImage, predicate: (Int, Int) -> Boolean): Int {
        var count = 0
        forEachPixel(image) { a, rgb -> if (predicate(a, rgb)) count++ }
        return count
    }

    private inline fun forEachPixel(image: BufferedImage, action: (Int, Int) -> Unit) {
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val rgb = image.getRGB(x, y)
                action((rgb ushr 24) and 0xFF, rgb and 0xFFFFFF)
            }
        }
    }

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