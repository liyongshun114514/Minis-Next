package com.openminis.app.sandbox

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * [T-offload-wire] The native_offload wire format, in one place, with no
 * Android dependency.
 *
 * Both clients (proot's `native_offload` extension and the CHROOT guest
 * client, `minis_offload_client.c`) speak this frame. It used to live inline
 * in [NativeOffloadServer], private and untestable on the JVM, so the only
 * observable failure mode of a protocol mismatch was the guest client's
 * "malformed reply" - with no way to tell a bad frame from a refused peer.
 * Every parser here returns a value instead of throwing, so the server can
 * ALWAYS answer with a well-formed frame:
 *
 *   req: u32 magic 0x46464F4E, u32 version=1, u32 pid, u32 argc,
 *        argc * (u32 len + bytes), u32 envc, envc * (u32 len + bytes "K=V"),
 *        str cwd
 *   rsp: u32 magic 0x52464F4E, i32 exitCode, str guestReplyPath
 *
 * All integers are little-endian. The reply path is a GUEST path
 * (`/tmp/.native-offload-<pid>-<seq>`); the client copies that file to stdout
 * and exits with `exitCode`.
 */
internal sealed interface OffloadFrame {
    data class Request(
        val pid: Int,
        val argv: List<String>,
        val env: Map<String, String>,
        val cwd: String,
    ) : OffloadFrame

    /** The bytes were not a request this build can serve; [reason] is logged. */
    data class Rejected(val reason: String) : OffloadFrame
}

internal object OffloadWire {
    const val MAGIC_REQ = 0x46464F4E  // 'N' 'O' 'F' 'F' little-endian
    const val MAGIC_RSP = 0x52464F4E  // 'N' 'O' 'F' 'R'

    /** Bumped only with a matching change in minis_offload_client.c. */
    const val VERSION = 1

    const val MAX_ARGC = 256
    const val MAX_ENVC = 4096
    const val MAX_STR = 1 shl 20

    /** sysexits EX_NOPERM: the peer is not allowed to use this socket. */
    const val EXIT_DENIED = 77

    /** sysexits EX_DATAERR: the request frame could not be parsed. */
    const val EXIT_BAD_FRAME = 65

    /** sysexits EX_NOUSER-ish: no handler registered for that argv[0]. */
    const val EXIT_NO_HANDLER = 127

    /**
     * Read one request frame. Never throws: a truncated, oversized or
     * otherwise unparseable frame comes back as [OffloadFrame.Rejected], so
     * the caller can answer it instead of closing the socket silently (which
     * is what made the guest client print "malformed reply").
     */
    fun readRequest(input: DataInputStream): OffloadFrame {
        return try {
            val magic = input.readLEInt()
            if (magic != MAGIC_REQ) return OffloadFrame.Rejected("bad magic 0x${magic.toUInt().toString(16)}")
            val version = input.readLEInt()
            if (version != VERSION) return OffloadFrame.Rejected("unsupported version $version")

            val pid = input.readLEInt()
            val argc = input.readLEInt()
            if (argc < 0 || argc > MAX_ARGC) return OffloadFrame.Rejected("bad argc=$argc")
            val argv = ArrayList<String>(argc)
            repeat(argc) { argv.add(input.readLEString()) }

            val envc = input.readLEInt()
            if (envc < 0 || envc > MAX_ENVC) return OffloadFrame.Rejected("bad envc=$envc")
            val env = LinkedHashMap<String, String>(envc)
            repeat(envc) {
                val s = input.readLEString()
                val eq = s.indexOf('=')
                if (eq >= 0) env[s.substring(0, eq)] = s.substring(eq + 1) else env[s] = ""
            }
            val cwd = input.readLEString()
            OffloadFrame.Request(pid, argv, env, cwd)
        } catch (e: EOFException) {
            OffloadFrame.Rejected("truncated frame (${e.message ?: "eof"})")
        } catch (e: IOException) {
            OffloadFrame.Rejected("io error: ${e.javaClass.simpleName}: ${e.message}")
        } catch (e: RuntimeException) {
            OffloadFrame.Rejected("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Decode a complete frame from memory (regression tests, and frame builders). */
    fun decodeRequest(bytes: ByteArray): OffloadFrame =
        readRequest(DataInputStream(ByteArrayInputStream(bytes)))

    /** Encode a request exactly as both clients do (little-endian). */
    fun encodeRequest(pid: Int, argv: List<String>, env: List<String>, cwd: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        out.writeLEInt(MAGIC_REQ)
        out.writeLEInt(VERSION)
        out.writeLEInt(pid)
        out.writeLEInt(argv.size)
        argv.forEach { out.writeLEString(it) }
        out.writeLEInt(env.size)
        env.forEach { out.writeLEString(it) }
        out.writeLEString(cwd)
        out.flush()
        return bytes.toByteArray()
    }

    /** The response frame, byte for byte (magic, exit code, reply path). */
    fun encodeReply(exitCode: Int, guestPath: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        writeReply(out, exitCode, guestPath)
        return bytes.toByteArray()
    }

    fun writeReply(out: DataOutputStream, exitCode: Int, guestPath: String) {
        out.writeLEInt(MAGIC_RSP)
        out.writeLEInt(exitCode)
        out.writeLEString(guestPath)
        out.flush()
    }

    /**
     * [T-offload-peer-refusal] What a refused peer is told instead of a silent
     * close. The guest client prints the reply file's contents, so a stale
     * in-rootfs client (the one that predates the euid switch and therefore
     * connects as uid 0) now names its own cause instead of "malformed reply".
     */
    fun deniedMessage(peerUid: Int, myUid: Int): String =
        "native_offload: refused - peer uid $peerUid is not this app's uid $myUid.\n" +
            "Inside a CHROOT shell the guest offload client must connect with the app uid " +
            "(MINIS_CHROOT_APP_UID) and restore uid 0 afterwards; a client installed in the " +
            "rootfs before that switch connects as uid 0 and is refused here.\n" +
            "Fix: let the app refresh /usr/local/lib/minis/minis-offload-client " +
            "(ChrootKernel.installOffloadClient, checked on every rootfs boot) and retry. " +
            "No permission was granted and no handler ran.\n"

    fun badFrameMessage(reason: String): String =
        "native_offload: bad request frame - $reason. No handler ran.\n" +
            "The client and this build disagree on the wire format (or the frame was truncated); " +
            "refresh the guest client instead of retrying the command.\n"

    fun noHandlerMessage(name: String): String =
        "native_offload: no handler for '$name'\n"

    // ---- little-endian helpers ----

    private fun DataInputStream.readLEInt(): Int {
        val buf = ByteArray(4)
        readFully(buf)
        return ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun DataInputStream.readLEString(): String {
        val len = readLEInt()
        if (len < 0 || len > MAX_STR) throw IOException("bad string len $len")
        if (len == 0) return ""
        val buf = ByteArray(len)
        readFully(buf)
        return String(buf, Charsets.UTF_8)
    }

    private fun DataOutputStream.writeLEInt(v: Int) {
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
        write(buf)
    }

    private fun DataOutputStream.writeLEString(s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        writeLEInt(bytes.size)
        if (bytes.isNotEmpty()) write(bytes)
    }
}
