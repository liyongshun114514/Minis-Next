package com.openminis.app.sandbox.offload

/**
 * [FEAT-261027-CHROOT-CLI] `status` is a pure projection of file/memory facts.
 *
 * [ChrootCliStatusSource] deliberately has NO method that can execute
 * anything: there is no su, no helper probe and no shell in this type. The
 * Android source only stats files, reads the existing preferences and reads
 * the helper's state marker.
 *
 * A grant is an in-process record of a past handshake, never a liveness proof:
 * the snapshot says [ChrootCliHelperStatus.grantRecorded] and pins
 * [ChrootCliHelperStatus.helperLiveness] to "not_probed". The JSON shape is
 * produced by the Android handler so this model stays dependency-free and
 * testable on a plain JVM.
 */
internal interface ChrootCliStatusSource {
    val launcherPath: String
    val launcherInstalled: Boolean
    val launcherExecutable: Boolean
    val rootfsPath: String
    val rootfsPresent: Boolean
    val offloadClientPath: String
    val offloadClientInstalled: Boolean
    val selectedBackend: String
    val generation: String
    val runtimeGrantPresent: Boolean
    val grantMayBeExpired: Boolean
    val autostart: Boolean
    val helperStateMarker: String?
    val lastError: String?
    val rootLevel: String
    val rootSwitchAllowed: Boolean
    val uid: Int
    val gid: Int
}

internal data class ChrootCliHelperStatus(
    /** A handshake was recorded in THIS process; not "the helper is alive". */
    val grantRecorded: Boolean,
    val grantMayBeExpired: Boolean,
    /** Always "not_probed": status never contacts or probes the helper. */
    val helperLiveness: String = HELPER_NOT_PROBED,
    val autostart: Boolean,
    val stateMarker: String?,
    val lastError: String?,
) {
    companion object {
        const val HELPER_NOT_PROBED = "not_probed"
    }
}

internal data class ChrootCliStatusSnapshot(
    val uid: Int,
    val gid: Int,
    val selectedBackend: String,
    val launcherPath: String,
    val launcherInstalled: Boolean,
    val launcherExecutable: Boolean,
    val rootfsPath: String,
    val rootfsPresent: Boolean,
    val offloadClientPath: String,
    val offloadClientInstalled: Boolean,
    val generation: String,
    val helper: ChrootCliHelperStatus,
    val rootLevel: String,
    val rootSwitchAllowed: Boolean,
    /** File/switch prerequisites only; su/helper liveness is NOT probed. */
    val prerequisitesMet: Boolean,
    val notes: List<String>,
)

internal object ChrootCliStatus {
    fun snapshot(source: ChrootCliStatusSource): ChrootCliStatusSnapshot {
        // Read each probe once: status is cheap, and a single read per fact
        // keeps a file that changes mid-report from producing a mixed answer.
        val launcherInstalled = source.launcherInstalled
        val launcherExecutable = source.launcherExecutable
        val rootfsPresent = source.rootfsPresent
        val switchAllowed = source.rootSwitchAllowed
        val prerequisitesMet = launcherInstalled &&
            launcherExecutable &&
            rootfsPresent &&
            switchAllowed
        return ChrootCliStatusSnapshot(
            uid = source.uid,
            gid = source.gid,
            selectedBackend = source.selectedBackend,
            launcherPath = source.launcherPath,
            launcherInstalled = launcherInstalled,
            launcherExecutable = launcherExecutable,
            rootfsPath = source.rootfsPath,
            rootfsPresent = rootfsPresent,
            offloadClientPath = source.offloadClientPath,
            offloadClientInstalled = source.offloadClientInstalled,
            generation = source.generation,
            helper = ChrootCliHelperStatus(
                grantRecorded = source.runtimeGrantPresent,
                grantMayBeExpired = source.grantMayBeExpired,
                autostart = source.autostart,
                stateMarker = source.helperStateMarker,
                lastError = source.lastError,
            ),
            rootLevel = source.rootLevel,
            rootSwitchAllowed = switchAllowed,
            prerequisitesMet = prerequisitesMet,
            notes = listOf(
                "status 只查询文件与内存（不调用 su、不启动 helper、不执行进程）。",
                "grant_recorded 仅表示本进程记录过握手，不代表 helper 当前存活；" +
                    "helper_liveness=${ChrootCliHelperStatus.HELPER_NOT_PROBED}，grant_may_be_expired 只是可能过期的下界。",
                "exec 每次都需用户在应用内单次确认，Root BYPASS/会话授权不会跳过。",
                "prerequisites_met 只是文件与开关前置条件；status 不探测 su 可用性，也不保证 helper 能启动。",
                "helper 本身是 uid0 的特权进程；当前入口一律请求 guest 内真实 uid0，" +
                    "不会静默降级为 app uid；命令始终处于私有 mount 命名空间且 NoNewPrivs=1。",
            ),
        )
    }
}
