package app.openminis.rootbridge;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the {@code su -c} invocation.
 *
 * Pure Java on purpose: quoting is security-relevant and is unit tested on a
 * desktop JVM (tools/root/java).
 */
public final class ShellCommand {

    /** PATH handed to the root shell so Android tools resolve. */
    public static final String ROOT_PATH =
            "/data/adb/ksu/bin:/data/adb/ap/bin:/data/adb/magisk:"
                    + "/sbin:/system/sbin:/system/bin:/system/xbin:/vendor/bin:"
                    + "/product/bin:/apex/com.android.runtime/bin";

    private ShellCommand() {
    }

    /**
     * @param suPath  resolved su binary (never null/empty)
     * @param argv    validated argv; argv[0] is the command
     * @param shell   true when argv[0] is already a shell script
     */
    public static List<String> build(String suPath, List<String> argv, boolean shell) {
        if (suPath == null || suPath.isEmpty()) {
            throw new IllegalArgumentException("su path is required");
        }
        if (argv == null || argv.isEmpty()) {
            throw new IllegalArgumentException("argv is required");
        }
        String payload = shell ? argv.get(0) : joinQuoted(argv);
        String script = "export PATH=" + ROOT_PATH + ":$PATH; exec " + payload;
        List<String> cmd = new ArrayList<>(3);
        cmd.add(suPath);
        cmd.add("-c");
        cmd.add(script);
        return cmd;
    }

    /** POSIX single-quote one argument so the root shell sees it verbatim. */
    public static String quote(String s) {
        if (s == null || s.isEmpty()) {
            return "''";
        }
        boolean safe = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || "_@%+=:,./-".indexOf(c) >= 0;
            if (!ok) {
                safe = false;
                break;
            }
        }
        if (safe) {
            return s;
        }
        return "'" + s.replace("'", "'\\''") + "'";
    }

    public static String joinQuoted(List<String> argv) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < argv.size(); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(quote(argv.get(i)));
        }
        return sb.toString();
    }

    /**
     * Strictly parse the real uid out of {@code id} output.
     *
     * Returns -1 unless the output contains a well-formed {@code uid=N} token
     * as the first field of a line. "uid=0" appearing inside a longer number
     * (uid=10000) or inside unrelated text is not accepted — the previous
     * implementation used String.contains("uid=0"), which reports root for
     * uid=10000 and for any command that merely printed that text.
     */
    public static int parseUid(String idOutput) {
        if (idOutput == null) {
            return -1;
        }
        String[] lines = idOutput.split("\n");
        for (String line : lines) {
            String t = line.trim();
            if (!t.startsWith("uid=")) {
                continue;
            }
            int i = 4;
            int start = i;
            while (i < t.length() && Character.isDigit(t.charAt(i))) {
                i++;
            }
            if (i == start) {
                return -1;
            }
            if (i < t.length()) {
                char next = t.charAt(i);
                if (next != '(' && next != ')' && !Character.isWhitespace(next)) {
                    // e.g. "uid=0abc" — not the output of `id`.
                    return -1;
                }
            }
            try {
                return Integer.parseInt(t.substring(start, i));
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }
}
