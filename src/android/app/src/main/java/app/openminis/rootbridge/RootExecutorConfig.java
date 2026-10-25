package app.openminis.rootbridge;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable-by-convention snapshot of the bridge settings.
 *
 * This class has no android.* imports so the clamping/parsing rules can be
 * unit tested on a desktop JVM. {@link ConfigLoader} is the Android-aware
 * entry point; call {@code ConfigLoader.load(Context)} for host configuration.
 *
 * Defaults are security defaults, not "whatever used to work":
 *   enabled=false        root stays off until the user explicitly opts in
 *   verbose=false
 *   su_path=""           auto-detect (existence checks only, never executed)
 *   timeout_sec=120      clamped to 10..900
 *   soul_enabled=true    consumed by the main entry
 *   composer_enabled=true
 */
public final class RootExecutorConfig {

    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_VERBOSE = "verbose_log";
    public static final String KEY_SU_PATH = "su_path";
    public static final String KEY_TIMEOUT = "timeout_sec";
    public static final String KEY_SOUL = "soul_enabled";
    public static final String KEY_COMPOSER = "composer_enabled";

    public static final int DEFAULT_TIMEOUT_SEC = 120;
    public static final int MIN_TIMEOUT_SEC = 10;
    public static final int MAX_TIMEOUT_SEC = 900;

    public boolean enabled = false;
    public boolean verboseLog = false;
    public String suPath = "";
    public int timeoutSec = DEFAULT_TIMEOUT_SEC;
    public boolean soulEnabled = true;
    public boolean composerEnabled = true;

    public RootExecutorConfig() {
    }

    public static RootExecutorConfig defaults() {
        return new RootExecutorConfig();
    }

    /** Copy + clamp. Always call this before handing a config to the executor. */
    public RootExecutorConfig sanitized() {
        RootExecutorConfig c = new RootExecutorConfig();
        c.enabled = enabled;
        c.verboseLog = verboseLog;
        c.suPath = sanitizeSuPath(suPath);
        c.timeoutSec = clampTimeout(timeoutSec);
        c.soulEnabled = soulEnabled;
        c.composerEnabled = composerEnabled;
        return c;
    }

    public static int clampTimeout(int seconds) {
        if (seconds <= 0) {
            return DEFAULT_TIMEOUT_SEC;
        }
        if (seconds < MIN_TIMEOUT_SEC) {
            return MIN_TIMEOUT_SEC;
        }
        if (seconds > MAX_TIMEOUT_SEC) {
            return MAX_TIMEOUT_SEC;
        }
        return seconds;
    }

    /** Reject control characters; an empty value means "auto-detect". */
    public static String sanitizeSuPath(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return "";
        }
        if (s.length() > 512) {
            return "";
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                return "";
            }
        }
        return s;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(KEY_ENABLED, enabled);
        m.put(KEY_VERBOSE, verboseLog);
        m.put(KEY_SU_PATH, suPath);
        m.put(KEY_TIMEOUT, timeoutSec);
        m.put(KEY_SOUL, soulEnabled);
        m.put(KEY_COMPOSER, composerEnabled);
        return m;
    }

    /** Missing or malformed entries fall back to the secure default. */
    public static RootExecutorConfig fromMap(Map<String, ?> map) {
        RootExecutorConfig c = new RootExecutorConfig();
        if (map == null) {
            return c;
        }
        c.enabled = bool(map.get(KEY_ENABLED), false);
        c.verboseLog = bool(map.get(KEY_VERBOSE), false);
        c.suPath = sanitizeSuPath(str(map.get(KEY_SU_PATH)));
        c.timeoutSec = clampTimeout(num(map.get(KEY_TIMEOUT), DEFAULT_TIMEOUT_SEC));
        c.soulEnabled = bool(map.get(KEY_SOUL), true);
        c.composerEnabled = bool(map.get(KEY_COMPOSER), true);
        return c;
    }

    public RootExecutorConfig copy() {
        RootExecutorConfig c = new RootExecutorConfig();
        c.enabled = enabled;
        c.verboseLog = verboseLog;
        c.suPath = suPath;
        c.timeoutSec = timeoutSec;
        c.soulEnabled = soulEnabled;
        c.composerEnabled = composerEnabled;
        return c;
    }

    private static boolean bool(Object o, boolean def) {
        if (o instanceof Boolean) {
            return (Boolean) o;
        }
        if (o instanceof String) {
            String s = ((String) o).trim();
            if ("true".equalsIgnoreCase(s)) {
                return true;
            }
            if ("false".equalsIgnoreCase(s)) {
                return false;
            }
        }
        if (o instanceof Number) {
            return ((Number) o).intValue() != 0;
        }
        return def;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static int num(Object o, int def) {
        if (o instanceof Number) {
            return ((Number) o).intValue();
        }
        if (o instanceof String) {
            try {
                return Integer.parseInt(((String) o).trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }
}
