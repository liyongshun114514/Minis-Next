package com.openminis.app.ui.chat

/**
 * Automatic compaction is maintenance, not a new work order. Only the loop
 * that was waiting for its successful summary may request continuation.
 * Manual/idle compaction and a stopped or replaced loop never wake a model.
 */
internal object AutoCompactContinuation {
    const val MESSAGE = "<system-reminder>Context compaction finished while your current task was still in progress. Continue the user's latest unfinished request from the saved summary and recent messages. Check what has already been done; do not repeat completed actions. If the request is already complete, finish without starting new work.</system-reminder>"

    fun shouldResume(
        compactSucceeded: Boolean,
        workPending: Boolean,
        sameRun: Boolean,
        cancelled: Boolean,
    ): Boolean = compactSucceeded && workPending && sameRun && !cancelled
}
