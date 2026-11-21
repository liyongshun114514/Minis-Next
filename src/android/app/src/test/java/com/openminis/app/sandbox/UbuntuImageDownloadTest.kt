package com.openminis.app.sandbox

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class UbuntuImageDownloadTest {
    private fun release(build: Int, draft: Boolean = false, checksum: Boolean = true, urlHost: String = "github.com"): String {
        val tag = "ubuntu-rootfs-v1-$build"
        val base = "https://$urlHost/${UbuntuImagePolicy.REPOSITORY}/releases/download/$tag/"
        val image = """{"name":"ubuntu-base-arm64.tar.gz","size":20000000,"browser_download_url":"${base}ubuntu-base-arm64.tar.gz"}"""
        val sum = if (checksum) """,{"name":"ubuntu-base-arm64.tar.gz.sha256","browser_download_url":"${base}ubuntu-base-arm64.tar.gz.sha256"}""" else ""
        return """{"tag_name":"$tag","draft":$draft,"assets":[$image$sum]}"""
    }

    @Test fun newestCompleteImageWinsRegardlessOfListOrder() {
        assertEquals(21L, UbuntuImagePolicy.latest("[${release(21)},${release(9)}]").build)
        assertEquals(21L, UbuntuImagePolicy.latest("[${release(9)},${release(21)}]").build)
    }
    @Test fun ignoresDraftsIncompleteImagesAndForeignUrls() {
        val json = "[${release(40, draft = true)},${release(39, checksum = false)},${release(38, urlHost = "other.example")},${release(37)}]"
        assertEquals(37L, UbuntuImagePolicy.latest(json).build)
    }
    @Test fun appReleasesDoNotCountAsTerminalImages() {
        assertThrows(IOException::class.java) { UbuntuImagePolicy.latest("[{\"tag_name\":\"1.3\",\"assets\":[]}]") }
    }
    @Test fun checksumRequiresTheExpectedFilename() {
        val hash = "a".repeat(64)
        assertEquals(hash, UbuntuImagePolicy.checksum("$hash  ubuntu-base-arm64.tar.gz\n"))
        assertThrows(IOException::class.java) { UbuntuImagePolicy.checksum("$hash  other.tar.gz") }
        assertThrows(IOException::class.java) { UbuntuImagePolicy.checksum("invalid") }
    }
    @Test fun partialOrCorruptBytesCannotBePromoted() {
        val size = 20_000_000L
        val hash = "a".repeat(64)
        UbuntuImagePolicy.verify(hash, hash, size, size)
        assertThrows(IOException::class.java) { UbuntuImagePolicy.verify(hash, "b".repeat(64), size, size) }
        assertThrows(IOException::class.java) { UbuntuImagePolicy.verify(hash, hash, size, size - 1) }
    }
}
