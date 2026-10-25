package com.openminis.app.offload

/** Shared copy: both entry points keep the same short enable warning. */
internal const val ROOT_ENABLE_MESSAGE =
    "开启仅允许发起请求，每条命令仍须单独确认。Root 误操作可能损坏系统或丢失数据。"

/** Exact argv is rendered separately; this never rates a command as safe. */
internal fun rootConfirmRisk(cwd: String, timeoutSec: Int): String =
    "关键风险：以 Root 身份执行，可能修改系统或删除数据，部分操作无法撤销。\n" +
        "目录：${cwd.ifBlank { "Android 默认目录" }} · 超时：${timeoutSec}s"
