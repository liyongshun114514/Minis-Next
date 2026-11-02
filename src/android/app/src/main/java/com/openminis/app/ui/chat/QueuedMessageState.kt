package com.openminis.app.ui.chat

/**
 * [T-queued-bubble-atomic-delivery] Pure list algebra for the placeholders a
 * queued prompt shows while the agent is busy.
 *
 * A prompt typed mid-run exists in THREE places and they must not drift:
 *
 *  1. `_promptQueue` — the delivery list (in-memory only),
 *  2. `_messages`    — its dashed `isQueued` bubble (in-memory only),
 *  3. the DB         — the user row, created only when the prompt is finally
 *                      delivered.
 *
 * Until (3) happens, (2) is the ONLY thing the user can see of their message:
 * if the bubble goes away before the row exists, the message looks lost — it
 * is not in the chat, it is not in the transcript, and reopening the session
 * does not bring it back. Two shapes in the delivery path used to do exactly
 * that:
 *
 *  - REMOVE-then-APPEND as two separate emits, with the append gated on a
 *    predicate read before the removal. When the predicate was false (a chat
 *    reloaded while the inject was suspended, a cleared chat, a replaced
 *    assistant bubble) the placeholder was removed and nothing was put back.
 *  - A whole-list rebuild (`loadSession`, `healStaleInFlightTools`,
 *    `handleUserCancelledCleanup`) replacing `_messages` with rows read from
 *    the DB. Queued bubbles are not in the DB yet, so they were dropped while
 *    their prompts stayed queued — the model then answered a question that
 *    was no longer on screen.
 *
 * [deliver] makes the swap ONE emit whose decision is taken inside it (so it
 * sees the live list, including a message the user appended microseconds
 * ago), and [reattach] restores the invariant "every queued prompt has
 * exactly one bubble" after any wholesale rebuild.
 *
 * Everything here is generic over the message type: production passes
 * [ChatMessage], the regressions pass a two-field row. No Android, Room or
 * Compose dependency, so the arithmetic can be executed on the JVM.
 */
internal object QueuedMessageState {

    /**
     * Replace the dashed placeholders of [queuedIds] with [newBubbles] in a
     * single pass over the LIVE list.
     *
     * Contract:
     *  - a placeholder whose `queuedPromptId` is in [queuedIds] is removed;
     *  - a placeholder of any OTHER prompt is left alone (it belongs to a later
     *    batch and still has a prompt behind it);
     *  - if NOTHING of ours was present, the list is returned unchanged: the
     *    caller never persisted a bubble the screen no longer expects (a chat
     *    cleared or reloaded while the delivery was suspended must not be
     *    repopulated), and a withdrawn placeholder must not come back;
     *  - [newBubbles] are appended in order, skipping any whose id is already
     *    in the list (a reload may have inserted the persisted row already);
     *  - an element that is neither removed nor appended is returned as the
     *    same instance, so Compose identity for untouched rows is unchanged.
     *
     * @param queuedPromptIdOf reads a message's placeholder linkage; null for
     *   a normal (non-queued) message.
     * @param idOf reads a message's stable id, used for the duplicate guard.
     */
    fun <M> deliver(
        current: List<M>,
        queuedIds: Set<String>,
        queuedPromptIdOf: (M) -> String?,
        idOf: (M) -> String,
        newBubbles: List<M>,
    ): List<M> {
        if (queuedIds.isEmpty()) return current
        var removed = 0
        val kept = ArrayList<M>(current.size + newBubbles.size)
        for (m in current) {
            val pid = queuedPromptIdOf(m)
            if (pid != null && pid in queuedIds) {
                removed++
                continue
            }
            kept.add(m)
        }
        // Nothing of ours was on screen: leave the list exactly as it is.
        if (removed == 0) return current
        if (newBubbles.isEmpty()) return kept
        val present = HashSet<String>(kept.size + newBubbles.size)
        for (m in kept) present.add(idOf(m))
        var appended = false
        for (b in newBubbles) {
            if (present.add(idOf(b))) {
                kept.add(b)
                appended = true
            }
        }
        return if (appended || removed > 0) kept else current
    }

    /**
     * Restore the "one queued prompt, one bubble" invariant after a wholesale
     * rebuild of the message list ([ChatViewModel.loadSession] and friends):
     * append a fresh placeholder for every prompt in [prompts] that has none,
     * in queue order, and touch nothing else.
     *
     * Without this, switching away from a chat (or any reload) dropped the
     * dashed bubbles while the prompts stayed queued: the drain later persisted
     * and sent a message the screen did not show, so the chat read as "reply
     * with no question" — and a prompt whose row was never written vanished for
     * good if the view model was rebuilt.
     */
    fun <P, M> reattach(
        current: List<M>,
        prompts: List<P>,
        promptIdOf: (P) -> String,
        queuedPromptIdOf: (M) -> String?,
        bubbleFor: (P) -> M,
    ): List<M> {
        if (prompts.isEmpty()) return current
        val attached = HashSet<String>()
        for (m in current) queuedPromptIdOf(m)?.let { attached.add(it) }
        val missing = ArrayList<P>(prompts.size)
        for (p in prompts) if (attached.add(promptIdOf(p))) missing.add(p)
        if (missing.isEmpty()) return current
        val out = ArrayList<M>(current.size + missing.size)
        out.addAll(current)
        for (p in missing) out.add(bubbleFor(p))
        return out
    }

    /**
     * [T-queue-epilogue-consumer] Whether a queue with [queueSize] pending
     * prompts needs a drain started right now.
     *
     * A prompt is only consumed by a drain: the agent loop's epilogue, or the
     * post-Stop resume. A prompt accepted while the loop was clearing
     * `isStreaming` (the epilogue had already run) therefore had NO consumer —
     * it sat as a dashed bubble and was never sent. The epilogue asks this
     * after it clears the flag; [kickActive] keeps a single consumer in flight
     * and [drainFailed] keeps an un-drainable queue (a provider error that
     * made the last drain give up) from being retried in a tight loop.
     */
    fun needsConsumer(
        isStreaming: Boolean,
        queueSize: Int,
        kickActive: Boolean,
        drainFailed: Boolean,
    ): Boolean = queueSize > 0 && !isStreaming && !kickActive && !drainFailed
}
