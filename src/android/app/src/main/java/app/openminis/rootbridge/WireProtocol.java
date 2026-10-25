package app.openminis.rootbridge;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Wire format of the Minis root bridge (protocol version 1).
 *
 * Deliberately free of android.* imports: the whole validation path can be
 * exercised on a desktop JVM against real byte streams, which is how
 * tools/root/ tests it.
 *
 * <pre>
 * request:  u32 MAGIC_REQ, u32 version, u32 flags, u32 argc,
 *           argc * (u32 len + bytes), u32 cwdLen + bytes
 * response: u32 MAGIC_RSP, u32 version
 *           then repeated: u8 channel, u32 len, bytes
 *           terminated by: u8 CHAN_END, i32 exitCode
 * </pre>
 *
 * All integers little-endian. The version is not bumped: the hardening below
 * only rejects requests the old server accepted by accident (oversized,
 * malformed UTF-8, NUL, unknown flag bits), so an old guest binary keeps
 * working against the new host and vice versa.
 */
public final class WireProtocol {

    public static final int MAGIC_REQ = 0x5153524D;
    public static final int MAGIC_RSP = 0x5342524D;
    public static final int PROTO_VERSION = 1;

    /** Only bit currently defined: run the payload through a root shell. */
    public static final int FLAG_SHELL = 1;
    public static final int FLAG_MASK = FLAG_SHELL;

    public static final int CHAN_END = 0;
    public static final int CHAN_STDOUT = 1;
    public static final int CHAN_STDERR = 2;

    /** Hard limits. Everything is validated before a single byte is executed. */
    public static final int MAX_STRING = 256 * 1024;
    public static final int MAX_ARGC = 1024;
    public static final int MAX_CWD = 4096;
    /** Sum of all string payloads plus headers for one request. */
    public static final int MAX_REQUEST_BYTES = 1024 * 1024;

    /** Exit codes reported to the guest. */
    public static final int EXIT_TIMEOUT = 124;
    public static final int EXIT_BUSY = 125;
    public static final int EXIT_CANNOT_EXEC = 126;
    public static final int EXIT_NO_SU = 127;
    public static final int EXIT_CANCELLED = 130;

    private WireProtocol() {
    }

    /** A validated request. Never constructed from unvalidated bytes. */
    public static final class Request {
        public final List<String> argv;
        public final String cwd;
        public final boolean shell;

        public Request(List<String> argv, String cwd, boolean shell) {
            this.argv = Collections.unmodifiableList(new ArrayList<>(argv));
            this.cwd = cwd == null ? "" : cwd;
            this.shell = shell;
        }

        /**
         * Log-safe summary. Deliberately does NOT include argv: command lines
         * may carry tokens or passwords and the log is world-readable to the
         * module's UI.
         */
        public String summary() {
            return "argc=" + argv.size() + " shell=" + shell
                    + " cwd=" + (cwd.isEmpty() ? "-" : "set");
        }
    }

    public static final class ProtocolException extends IOException {
        private static final long serialVersionUID = 1L;

        public ProtocolException(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------
    // request decoding
    // ------------------------------------------------------------------

    /**
     * Read and validate one request.
     *
     * @param deadlineNanos {@link System#nanoTime()} value after which the
     *                      request is abandoned; guards against a peer that
     *                      trickles bytes forever.
     */
    public static Request readRequest(InputStream in, long deadlineNanos) throws IOException {
        int magic = readInt(in, deadlineNanos);
        if (magic != MAGIC_REQ) {
            throw new ProtocolException("bad request magic 0x" + Integer.toHexString(magic));
        }
        int version = readInt(in, deadlineNanos);
        if (version != PROTO_VERSION) {
            throw new ProtocolException("unsupported protocol version " + version);
        }
        int flags = readInt(in, deadlineNanos);
        if ((flags & ~FLAG_MASK) != 0) {
            throw new ProtocolException("unsupported flags 0x" + Integer.toHexString(flags));
        }
        int argc = readInt(in, deadlineNanos);
        if (argc <= 0 || argc > MAX_ARGC) {
            throw new ProtocolException("bad argc " + argc);
        }

        int budget = MAX_REQUEST_BYTES - 16; // fixed request header is part of the cap
        List<String> argv = new ArrayList<>(Math.min(argc, 64));
        for (int i = 0; i < argc; i++) {
            String arg = readString(in, deadlineNanos, budget);
            budget -= arg.getBytes(StandardCharsets.UTF_8).length + 4;
            argv.add(arg);
        }
        String cwd = readString(in, deadlineNanos, Math.min(budget, MAX_CWD + 4));
        if (!cwd.isEmpty()) {
            if (cwd.length() > MAX_CWD) {
                throw new ProtocolException("cwd too long");
            }
            if (cwd.charAt(0) != '/') {
                throw new ProtocolException("cwd must be absolute");
            }
        }
        if (argv.get(0).isEmpty()) {
            throw new ProtocolException("empty argv[0]");
        }
        return new Request(argv, cwd, (flags & FLAG_SHELL) != 0);
    }

    private static String readString(InputStream in, long deadlineNanos, int budget)
            throws IOException {
        int len = readInt(in, deadlineNanos);
        if (len < 0) {
            throw new ProtocolException("negative string length " + len);
        }
        if (len > MAX_STRING) {
            throw new ProtocolException("string too long: " + len);
        }
        if (budget < 4 || len > budget - 4) {
            throw new ProtocolException("request too large");
        }
        if (len == 0) {
            return "";
        }
        byte[] buf = new byte[len];
        readFully(in, buf, deadlineNanos);
        String s = decodeUtf8(buf);
        if (s.indexOf('\0') >= 0) {
            throw new ProtocolException("NUL byte in string");
        }
        return s;
    }

    /** Strict UTF-8: malformed or overlong sequences are a protocol error. */
    static String decodeUtf8(byte[] buf) throws ProtocolException {
        CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer cb = dec.decode(ByteBuffer.wrap(buf));
            return cb.toString();
        } catch (CharacterCodingException e) {
            throw new ProtocolException("invalid UTF-8 in request");
        }
    }

    private static void readFully(InputStream in, byte[] buf, long deadlineNanos)
            throws IOException {
        int off = 0;
        while (off < buf.length) {
            checkDeadline(deadlineNanos);
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) {
                throw new EOFException("peer closed after " + off + "/" + buf.length + " bytes");
            }
            off += n;
        }
    }

    public static int readInt(InputStream in, long deadlineNanos) throws IOException {
        byte[] b = new byte[4];
        readFully(in, b, deadlineNanos);
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static void checkDeadline(long deadlineNanos) throws IOException {
        if (deadlineNanos > 0 && System.nanoTime() > deadlineNanos) {
            throw new ProtocolException("request read deadline exceeded");
        }
    }

    // ------------------------------------------------------------------
    // response encoding
    // ------------------------------------------------------------------

    public static void writeHeader(OutputStream out) throws IOException {
        writeInt(out, MAGIC_RSP);
        writeInt(out, PROTO_VERSION);
    }

    public static void writeFrame(OutputStream out, int channel, byte[] data, int len)
            throws IOException {
        out.write(channel);
        writeInt(out, len);
        out.write(data, 0, len);
    }

    public static void writeEnd(OutputStream out, int exitCode) throws IOException {
        out.write(CHAN_END);
        writeInt(out, exitCode);
    }

    public static void writeInt(OutputStream out, int v) throws IOException {
        out.write(v & 0xff);
        out.write((v >>> 8) & 0xff);
        out.write((v >>> 16) & 0xff);
        out.write((v >>> 24) & 0xff);
    }
}
