package app.openminis.rootbridge;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs commands with real root through {@code su}.
 *
 * Hard rules enforced here (each one fixes a real failure of the old module):
 *   - su discovery is passive: only stat()/access() checks, never
 *     {@code su -c id} or {@code sh -c command -v}. Startup never executes su.
 *   - when no usable su exists the request fails closed (exit 127), it is not
 *     retried with a guess.
 *   - the child gets /dev/null as stdin, so a command cannot block waiting for
 *     input the protocol never forwards.
 *   - the timeout comes from the (clamped) settings, and on expiry the process
 *     is killed, its streams closed and its pump threads joined — no thread is
 *     left behind.
 *   - a dead client (write failure) cancels the command immediately.
 */
public final class RootExecutor {

    /** Candidate su binaries, most common first. Never executed during lookup. */
    private static final String[] SU_CANDIDATES = {
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/debug_ramdisk/su",
            "/data/adb/ksu/bin/su",
            "/data/adb/ap/bin/su",
            "/data/adb/magisk/su",
    };

    private static final long PUMP_JOIN_MS = 3000L;
    private static final long KILL_GRACE_MS = 2000L;

    public interface Sink {
        /** @return false when the client is gone; the command is then killed. */
        boolean stdout(byte[] data);

        boolean stderr(byte[] data);

        void finish(int exitCode);
    }

    /** Result of an explicit root probe. Never produced automatically. */
    public static final class ProbeResult {
        public final boolean tested;
        public final int uid;
        public final String suPath;
        public final String message;

        ProbeResult(boolean tested, int uid, String suPath, String message) {
            this.tested = tested;
            this.uid = uid;
            this.suPath = suPath;
            this.message = message;
        }

        public boolean isRoot() {
            return tested && uid == 0;
        }
    }

    private final RootExecutorConfig config;
    /** Effective command timeout. The public ctor takes it from the settings. */
    private final int timeoutSec;

    /** A native UI decision for this exact request, never a cached UID grant. */
    public interface Authorizer { boolean approve(WireProtocol.Request request); }
    private final Authorizer authorizer;

    public RootExecutor(RootExecutorConfig config) {
        this(config, 0, request -> false);
    }

    public RootExecutor(RootExecutorConfig config, Authorizer authorizer) {
        this(config, 0, authorizer);
    }

    /**
     * @param timeoutOverrideSec when &gt; 0, used instead of the settings value
     *                           (probe / tests); still bounded to 1..900s
     */
    RootExecutor(RootExecutorConfig config, int timeoutOverrideSec) {
        this(config, timeoutOverrideSec, request -> false);
    }

    public RootExecutor(RootExecutorConfig config, int timeoutOverrideSec, Authorizer authorizer) {
        this.authorizer = authorizer == null ? request -> false : authorizer;
        this.config = (config == null ? RootExecutorConfig.defaults() : config).sanitized();
        this.timeoutSec = timeoutOverrideSec > 0
                ? Math.max(1, Math.min(RootExecutorConfig.MAX_TIMEOUT_SEC, timeoutOverrideSec))
                : this.config.timeoutSec;
    }

    public RootExecutorConfig config() {
        return config.copy();
    }

    public int timeoutSec() {
        return timeoutSec;
    }

    // ------------------------------------------------------------------
    // su discovery (passive)
    // ------------------------------------------------------------------

    /**
     * Resolve the su binary without running anything.
     *
     * @return an absolute path or a bare name found on PATH, or null when no
     *         candidate exists. Callers must treat null as "root unavailable".
     */
    public String resolveSu() {
        String configured = RootExecutorConfig.sanitizeSuPath(config.suPath);
        if (!configured.isEmpty()) {
            if (configured.indexOf('/') >= 0) {
                // Explicit path: honour it exactly, or fail — silently falling
                // back to another su would defeat the setting.
                return isExecutableFile(configured) ? configured : null;
            }
            return findOnPath(configured);
        }
        for (String cand : SU_CANDIDATES) {
            if (isExecutableFile(cand)) {
                return cand;
            }
        }
        return findOnPath("su");
    }

    private static boolean isExecutableFile(String path) {
        try {
            File f = new File(path);
            return f.isFile() && f.canExecute();
        } catch (Throwable t) {
            return false;
        }
    }

    /** PATH lookup by stat only — no {@code command -v}, no shell. */
    private static String findOnPath(String name) {
        if (name == null || name.isEmpty() || name.indexOf('/') >= 0) {
            return null;
        }
        List<String> dirs = new ArrayList<>();
        String path = System.getenv("PATH");
        if (path != null) {
            dirs.addAll(Arrays.asList(path.split(":")));
        }
        dirs.addAll(Arrays.asList(ShellCommand.ROOT_PATH.split(":")));
        for (String dir : dirs) {
            if (dir.isEmpty()) {
                continue;
            }
            String cand = dir + "/" + name;
            if (isExecutableFile(cand)) {
                return cand;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // execution
    // ------------------------------------------------------------------

    /** Run one request to completion (or timeout/cancel), always ending with finish(). */
    public void execute(WireProtocol.Request req, Sink sink) {
        if (!config.enabled || !authorizer.approve(req)) {
            sink.stderr("Root request disabled, denied, dismissed or timed out.\n".getBytes(StandardCharsets.UTF_8));
            sink.finish(WireProtocol.EXIT_CANNOT_EXEC);
            return;
        }
        final String su = resolveSu();
        if (su == null) {
            sink.stderr(("minis-root: no usable su binary found (checked "
                    + Arrays.toString(SU_CANDIDATES) + " and PATH). "
                    + "Set su_path in the module settings.\n").getBytes(StandardCharsets.UTF_8));
            sink.finish(WireProtocol.EXIT_NO_SU);
            return;
        }

        List<String> cmd;
        try {
            cmd = ShellCommand.build(su, req.argv, req.shell);
        } catch (IllegalArgumentException e) {
            sink.stderr(("minis-root: " + e.getMessage() + "\n").getBytes(StandardCharsets.UTF_8));
            sink.finish(WireProtocol.EXIT_CANNOT_EXEC);
            return;
        }

        Process process = null;
        final Cancel cancel = new Cancel();
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(false);
            // The protocol has no stdin channel; give the child an immediate EOF
            // instead of the app's (never written) pipe.
            pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
            if (!req.cwd.isEmpty()) {
                File dir = new File(req.cwd);
                if (dir.isDirectory()) {
                    pb.directory(dir);
                }
            }

            process = pb.start();
            cancel.process = process;
            closeQuietly(process.getOutputStream());

            Pump outPump = new Pump(process.getInputStream(), true, sink, cancel);
            Pump errPump = new Pump(process.getErrorStream(), false, sink, cancel);
            Thread tOut = newDaemon(outPump, "minis-root-stdout");
            Thread tErr = newDaemon(errPump, "minis-root-stderr");
            tOut.start();
            tErr.start();

            boolean finished = process.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!finished) {
                cancel.kill();
                awaitExit(process);
                closeQuietly(process.getInputStream());
                closeQuietly(process.getErrorStream());
                joinBounded(tOut, 500);
                joinBounded(tErr, 500);
                sink.stderr(("\nminis-root: command timed out after "
                        + timeoutSec + "s and was killed\n")
                        .getBytes(StandardCharsets.UTF_8));
                sink.finish(WireProtocol.EXIT_TIMEOUT);
                return;
            }

            // Drain whatever is still buffered, but never block on a stalled
            // reader: close the pipes after a bounded join.
            joinBounded(tOut, PUMP_JOIN_MS);
            joinBounded(tErr, PUMP_JOIN_MS);
            if (tOut.isAlive() || tErr.isAlive()) {
                closeQuietly(process.getInputStream());
                closeQuietly(process.getErrorStream());
                joinBounded(tOut, 500);
                joinBounded(tErr, 500);
            }

            if (cancel.cancelled) {
                sink.finish(WireProtocol.EXIT_CANCELLED);
                return;
            }
            sink.finish(process.exitValue());
        } catch (IOException e) {
            sink.stderr(("minis-root: " + e.getClass().getSimpleName() + ": "
                    + e.getMessage() + "\n").getBytes(StandardCharsets.UTF_8));
            sink.finish(WireProtocol.EXIT_CANNOT_EXEC);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancel.kill();
            sink.finish(WireProtocol.EXIT_CANCELLED);
        } finally {
            cancel.kill();
            if (process != null) {
                closeQuietly(process.getOutputStream());
                if (process.isAlive()) {
                    awaitExit(process);
                }
            }
        }
    }

    /**
     * Explicit root probe ({@code su -c id}).
     *
     * NOT called on startup and NOT called by the settings screen: the settings
     * screen runs in the module UID, where a grant would test the wrong
     * principal. Kept for a future explicit host-side "verify" action.
     */
    public ProbeResult probeRoot(int timeoutSecOverride) {
        final int timeout = timeoutSecOverride > 0
                ? timeoutSecOverride
                : Math.min(config.timeoutSec, 30);
        final StringBuilder out = new StringBuilder();
        final StringBuilder err = new StringBuilder();
        {
            // execute() uses the instance timeout; a probe must stay short.
            WireProtocol.Request req = new WireProtocol.Request(
                    Arrays.asList("id"), "", false);
            Sink sink = new Sink() {
                @Override
                public boolean stdout(byte[] data) {
                    synchronized (out) {
                        out.append(new String(data, StandardCharsets.UTF_8));
                    }
                    return true;
                }

                @Override
                public boolean stderr(byte[] data) {
                    synchronized (err) {
                        err.append(new String(data, StandardCharsets.UTF_8));
                    }
                    return true;
                }

                @Override
                public void finish(int exitCode) {
                }
            };
            // A probe runs on its own executor with a narrowed timeout.
            RootExecutor probe = new RootExecutor(config, timeout, authorizer);
            probe.execute(req, sink);
            String text;
            synchronized (out) {
                text = out.toString();
            }
            int uid = ShellCommand.parseUid(text);
            String su = probe.resolveSu();
            if (uid < 0) {
                synchronized (err) {
                    text = err.toString();
                }
                return new ProbeResult(true, -1, su,
                        "id output did not contain a strict uid=0 field: " + trim(text));
            }
            return new ProbeResult(true, uid, su, uid == 0 ? "root granted" : "uid=" + uid);
        }
    }

    private static String trim(String s) {
        String t = s == null ? "" : s.trim();
        return t.length() > 300 ? t.substring(0, 300) + "..." : t;
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** Shared cancellation state between the executor and its pumps. */
    private static final class Cancel {
        volatile Process process;
        volatile boolean cancelled;

        void kill() {
            cancelled = true;
            Process p = process;
            if (p != null) {
                try {
                    p.destroyForcibly();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static Thread newDaemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    private static void awaitExit(Process p) {
        try {
            p.waitFor(KILL_GRACE_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void joinBounded(Thread t, long ms) {
        try {
            t.join(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(InputStream in) {
        if (in == null) {
            return;
        }
        try {
            in.close();
        } catch (IOException ignored) {
        }
    }

    private static void closeQuietly(OutputStream out) {
        if (out == null) {
            return;
        }
        try {
            out.close();
        } catch (IOException ignored) {
        }
    }

    /** Drains a child stream into the sink; stops and kills the child if the client is gone. */
    private static final class Pump implements Runnable {
        private final InputStream in;
        private final boolean stdout;
        private final Sink sink;
        private final Cancel cancel;

        Pump(InputStream in, boolean stdout, Sink sink, Cancel cancel) {
            this.in = in;
            this.stdout = stdout;
            this.sink = sink;
            this.cancel = cancel;
        }

        @Override
        public void run() {
            byte[] buf = new byte[8192];
            try (InputStream stream = in) {
                int n;
                while ((n = stream.read(buf)) > 0) {
                    byte[] chunk = Arrays.copyOf(buf, n);
                    boolean alive = stdout ? sink.stdout(chunk) : sink.stderr(chunk);
                    if (!alive) {
                        cancel.kill();
                        return;
                    }
                }
            } catch (IOException ignored) {
                // Stream closed by timeout/cancel path.
            }
        }
    }
}
