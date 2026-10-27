package com.openminis.app.sandbox

import com.openminis.app.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Hardening around the native-offload socket: the listener is close-on-exec
 * [T-native-offload-cloexec], only this app's uid may connect
 * [T-native-offload-peer-uid], and the terminal child closes inherited fds
 * before exec [T-pty-close-fds].
 */
class NativeOffloadHardeningTest {

    private val server = ProductionSources.read("sandbox/NativeOffload.kt")

    @Test
    fun `the listening socket is set close-on-exec as soon as it is bound`() {
        val onBound = server.substringAfter("private fun onBound(s: LocalServerSocket) {").substringBefore("\n    }\n")
        assertTrue(onBound.trimStart().startsWith("setCloseOnExec(s)"))
        assertTrue(server.contains("OsConstants.F_SETFD, android.system.OsConstants.FD_CLOEXEC)"))
    }

    @Test
    fun `only the app's own uid may use the socket`() {
        assertTrue(PeerCheck.allowed(10379, 10379))
        assertFalse("another app", PeerCheck.allowed(10234, 10379))
        assertFalse("adb shell", PeerCheck.allowed(2000, 10379))
        assertFalse("root", PeerCheck.allowed(0, 10379))
        assertFalse("credentials unreadable", PeerCheck.allowed(-1, 10379))
        val handle = server.substringAfter("private fun handleClient(client: LocalSocket) {").take(1200)
        assertTrue("checked before any byte is read",
            handle.indexOf("PeerCheck.allowed(") in 0 until handle.indexOf("DataInputStream(client.inputStream)"))
    }

    @Test
    fun `a refused peer is answered, not closed silently`() {
        // [T-offload-peer-refusal] Closing the socket without a frame is what the
        // CHROOT guest client reported as "malformed reply ... exit 69": an
        // outdated in-rootfs client connects as uid 0 instead of the app uid.
        val handle = server.substringAfter("private fun handleClient(client: LocalSocket) {").take(1200)
        assertTrue("refusal answers before returning", handle.contains("denyPeer(client, peerUid)"))
        assertTrue(server.contains("OffloadWire.EXIT_DENIED"))
        assertTrue(server.contains("OffloadWire.deniedMessage(peerUid, android.os.Process.myUid())"))
        // The body it sends must name the cause, not read like a protocol bug.
        val denied = OffloadWire.deniedMessage(peerUid = 0, myUid = 10123)
        assertTrue(denied.contains("refused"))
        assertTrue(denied.contains("rootfs"))
    }

    @Test
    fun `a malformed request frame is answered with EX_DATAERR`() {
        val handle = server.substringAfter("private fun handleClient(client: LocalSocket) {").take(2000)
        assertTrue(handle.contains("is OffloadFrame.Rejected ->"))
        assertTrue(handle.contains("OffloadWire.EXIT_BAD_FRAME"))
        assertTrue(handle.contains("OffloadWire.badFrameMessage(frame.reason)"))
        // Parsing lives in the pure, unit-tested OffloadWire (OffloadWireTest).
        assertFalse("no duplicated frame parser left behind", server.contains("private fun readRequest("))
    }

    @Test
    fun `the terminal child closes inherited fds with raw syscalls before exec`() {
        val pty = File(ProductionSources.mainRoot()!!, "../../../../cpp/pty_bridge.c").readText()
        val child = pty.substringAfter("if (pid == 0) {").substringBefore("execve(cmd, argv, envp);")
        assertTrue(child.contains("closeInheritedFds();"))
        val helper = pty.substringAfter("static void closeInheritedFds(void) {").substringBefore("\n}\n")
        assertTrue(helper.contains("syscall(__NR_close, (int) fd)"))
        assertFalse("no fdsan-checked close() / malloc after fork", helper.contains(" close(") || helper.contains("opendir"))
        assertTrue("starts above stdio", helper.contains("for (long fd = 3;"))
    }
}
