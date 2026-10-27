package com.openminis.app.sandbox

/**
 * [T-native-offload-bind] The `/usr/local/bin/<handler>` stub every offload CLI
 * resolves to on PATH.
 *
 * On the normal path the stub never runs: proot's native_offload extension
 * intercepts the execve and serves the command from the app. It runs only
 * when that did not happen - the app could not bind its offload socket (the
 * launcher then leaves `--native-offload` off), or the offload call failed.
 * It used to be `exit 0`, so a broken offload looked like a successful
 * command with no output; now it says what is wrong and exits non-zero.
 */
internal object HandlerStub {
    /** What every earlier build wrote; upgraded in place on boot. */
    const val LEGACY_CONTENT = "#!/bin/sh\nexit 0\n"

    /** sysexits EX_UNAVAILABLE. */
    const val EXIT_UNAVAILABLE = 69

    /** Recognize the previous app-owned error stub without replacing user files. */
    fun previousContent(name: String): String =
        "#!/bin/sh\n" +
            "echo \"$name: unavailable - Minis could not start its native offload service. " +
            "Restart the Minis app and try again.\" >&2\n" +
            "exit $EXIT_UNAVAILABLE\n"

    fun content(name: String): String =
        "#!/bin/sh\n" +
            // An explicitly confirmed chroot command can coexist with PRoot.
            // Its tools must work without globally rewriting the shared stubs.
            // Routing to the native client grants no permission: peer uid and
            // each handler's approval gate remain enforced by the host.
            "if [ \"\${MINIS_SANDBOX_BACKEND:-}\" = CHROOT ] && " +
            "[ -x /usr/local/lib/minis/minis-offload-client ]; then\n" +
            "    exec /usr/local/lib/minis/minis-offload-client \"\$0\" \"\$@\"\n" +
            "fi\n" +
            previousContent(name).removePrefix("#!/bin/sh\n")
}
