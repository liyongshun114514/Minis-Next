package com.openminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-offload-wire] The native_offload frame, on a plain JVM.
 *
 * Before this test the frame was private inside [NativeOffloadServer] (an
 * Android class), so the only way to notice a mismatch was a device: the
 * CHROOT guest client printed "malformed reply ... exit 69" with no way to
 * tell a bad frame from a refused peer. Every case below is what the server
 * must ANSWER, not silently drop.
 */
class OffloadWireTest {

    private val statusArgv = listOf("/usr/local/bin/android-chroot-cli", "status")
    private val execArgv = listOf("/usr/local/bin/android-chroot-cli", "exec", "--command", "id -u")

    // ---- happy paths: the two verbs the report was about ----

    @Test
    fun `a status request round-trips`() {
        val bytes = OffloadWire.encodeRequest(
            pid = 4242,
            argv = statusArgv,
            env = listOf("MINIS_NOFF_SOCKET=native-offload-10123-4242", "MINIS_SANDBOX_BACKEND=CHROOT"),
            cwd = "/home/minis",
        )
        val frame = OffloadWire.decodeRequest(bytes)
        assertTrue("decoded: $frame", frame is OffloadFrame.Request)
        frame as OffloadFrame.Request
        assertEquals(4242, frame.pid)
        assertEquals(statusArgv, frame.argv)
        assertEquals("/home/minis", frame.cwd)
        assertEquals("native-offload-10123-4242", frame.env["MINIS_NOFF_SOCKET"])
    }

    @Test
    fun `an exec request keeps argv order and the session env`() {
        val bytes = OffloadWire.encodeRequest(
            pid = 7,
            argv = execArgv,
            env = listOf("MINIS_CHAT_SESSION_ID=session-1", "MINIS_CHROOT_APP_UID=10123"),
            cwd = "/root",
        )
        val frame = OffloadWire.decodeRequest(bytes) as OffloadFrame.Request
        assertEquals(execArgv, frame.argv)
        assertEquals("session-1", frame.env["MINIS_CHAT_SESSION_ID"])
        assertEquals("10123", frame.env["MINIS_CHROOT_APP_UID"])
    }

    @Test
    fun `an empty environment and a zero-length string still parse`() {
        val bytes = OffloadWire.encodeRequest(pid = 1, argv = listOf("x"), env = emptyList(), cwd = "")
        val frame = OffloadWire.decodeRequest(bytes) as OffloadFrame.Request
        assertEquals(listOf("x"), frame.argv)
        assertTrue(frame.env.isEmpty())
        assertEquals("", frame.cwd)
    }

    // ---- malformed frames: answered EX_DATAERR, never a silent close ----

    @Test
    fun `a bad magic is rejected with a reason`() {
        val bytes = OffloadWire.encodeRequest(1, statusArgv, emptyList(), "/").copyOf()
        bytes[0] = 0x00
        val frame = OffloadWire.decodeRequest(bytes)
        assertTrue(frame is OffloadFrame.Rejected)
        assertTrue((frame as OffloadFrame.Rejected).reason.contains("bad magic"))
    }

    @Test
    fun `a foreign version is rejected`() {
        val bytes = OffloadWire.encodeRequest(1, statusArgv, emptyList(), "/")
        bytes[4] = 99
        val frame = OffloadWire.decodeRequest(bytes) as OffloadFrame.Rejected
        assertTrue(frame.reason.contains("unsupported version"))
    }

    @Test
    fun `a truncated frame is rejected instead of throwing`() {
        val bytes = OffloadWire.encodeRequest(1, statusArgv, emptyList(), "/")
        val frame = OffloadWire.decodeRequest(bytes.copyOf(bytes.size - 3))
        assertTrue(frame is OffloadFrame.Rejected)
        assertTrue((frame as OffloadFrame.Rejected).reason.contains("truncated"))
    }

    @Test
    fun `a truncated header is rejected, not an exception`() {
        val frame = OffloadWire.decodeRequest(byteArrayOf(0x4E, 0x4F))
        assertTrue(frame is OffloadFrame.Rejected)
    }

    @Test
    fun `an oversized string length is rejected`() {
        val bytes = OffloadWire.encodeRequest(1, listOf("x"), emptyList(), "/").copyOf()
        // argc=1, then the string length sits right after pid/argc: magic,ver,pid,argc
        bytes[16] = 0xFF.toByte()
        bytes[17] = 0xFF.toByte()
        bytes[18] = 0xFF.toByte()
        bytes[19] = 0x7F.toByte()
        val frame = OffloadWire.decodeRequest(bytes) as OffloadFrame.Rejected
        assertTrue(frame.reason.contains("io error") || frame.reason.contains("bad string len"))
    }

    @Test
    fun `an absurd argc is rejected before allocating`() {
        val bytes = OffloadWire.encodeRequest(1, statusArgv, emptyList(), "/").copyOf()
        bytes[12] = 0x00
        bytes[13] = 0x01
        bytes[14] = 0x00
        bytes[15] = 0x00 // 0x00000100 = 256 -> allowed; use 257 below
        bytes[12] = 0x01
        bytes[13] = 0x01
        val frame = OffloadWire.decodeRequest(bytes) as OffloadFrame.Rejected
        assertTrue(frame.reason.contains("bad argc"))
    }

    @Test
    fun `an absurd envc is rejected`() {
        val bytes = OffloadWire.encodeRequest(1, statusArgv, emptyList(), "/").copyOf()
        // find the envc field: after magic,ver,pid,argc and the argv strings
        val envcAt = 16 + statusArgv.sumOf { 4 + it.toByteArray().size }
        bytes[envcAt] = 0x01
        bytes[envcAt + 1] = 0x00
        bytes[envcAt + 2] = 0x20 // 0x00200001 > 4096
        val frame = OffloadWire.decodeRequest(bytes) as OffloadFrame.Rejected
        assertTrue(frame.reason.contains("bad envc"))
    }

    // ---- replies ----

    @Test
    fun `the reply frame is magic then exit code then path`() {
        val bytes = OffloadWire.encodeReply(0, "/tmp/.native-offload-42-1")
        val raw = bytes
        assertEquals('N'.code, raw[0].toInt())
        assertEquals('O'.code, raw[1].toInt())
        assertEquals('F'.code, raw[2].toInt())
        assertEquals('R'.code, raw[3].toInt())
        val pathLen = (raw[8].toInt() and 0xFF) or ((raw[9].toInt() and 0xFF) shl 8)
        assertEquals("/tmp/.native-offload-42-1".length, pathLen)
    }

    @Test
    fun `refusals are answered with a named cause and a non-zero code`() {
        val denied = OffloadWire.deniedMessage(peerUid = 0, myUid = 10123)
        assertTrue(denied.contains("peer uid 0"))
        assertTrue(denied.contains("10123"))
        assertTrue("must not read as a protocol bug", denied.contains("refused"))
        assertTrue("must name the stale client", denied.contains("rootfs"))
        assertFalse(denied.isEmpty())

        val bad = OffloadWire.badFrameMessage("bad argc=999")
        assertTrue(bad.contains("bad argc=999"))
        assertTrue(bad.contains("No handler ran"))

        assertEquals(77, OffloadWire.EXIT_DENIED)
        assertEquals(65, OffloadWire.EXIT_BAD_FRAME)
        assertEquals(127, OffloadWire.EXIT_NO_HANDLER)
    }

    @Test
    fun `the guest client frames exactly like this decoder`() {
        // Byte-for-byte the layout minis_offload_client.c writes (verified
        // against the compiled binary by scripts/test_offload_client.py):
        // magic, version, pid, argc, strings, envc, strings, cwd.
        val bytes = OffloadWire.encodeRequest(
            pid = 0x01020304,
            argv = listOf("android-chroot-cli", "status"),
            env = listOf("A=b"),
            cwd = "/",
        )
        assertEquals(0x4E, bytes[0].toInt()) // 'N'
        assertEquals(0x01, bytes[4].toInt())
        assertEquals(0x04, bytes[8].toInt()) // pid LE
        assertEquals(0x03, bytes[9].toInt())
        assertEquals(2, bytes[12].toInt()) // argc = 2, little-endian
    }
}
