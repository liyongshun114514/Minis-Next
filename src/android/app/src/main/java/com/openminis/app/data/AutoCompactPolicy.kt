package com.openminis.app.data

/**
 * [T-ctx-auto-threshold] The user's own auto-compaction token threshold,
 * applied on top of the model's generated [ContextPolicy].
 *
 * Why a separate object instead of a new field on [ContextPolicy]: the policy
 * is derived, not stored — [ContextPolicy.forContextWindow] and
 * [ContextPolicy.forUserCap] own the capacity protection (headroom for the
 * answer, the offload ladder, "this window is too small for a summary"). A
 * user threshold must only ever move the *compact* line, and only downwards
 * or up to that protection, never past it: a threshold above the line leaves
 * no room for the summary request itself or for the next turn.
 *
 * Two shapes of base policy, one rule:
 *
 *  - `compactThreshold > 0` ([ContextPolicy.forUserCap] and the 64K+ tiers):
 *    the result is `min(user, base line)`. The base line already sits below
 *    the window by the tier's headroom (10K/20K) or by 15% of a user cap, so
 *    taking the minimum keeps exactly that protection.
 *  - `compactThreshold == 0` (windows under 64K, where the tier table says a
 *    summary could not pay for itself): there is no line to protect, so the
 *    window's own 85% share stands in for it — `min(user, window * 0.85)`.
 *    An explicit threshold is the user saying "I want to compact here", and a
 *    user-chosen compaction model may well be large enough to pay for the
 *    summary even when the session's model is not, so `exhaustedOnly` is
 *    lifted and `manualCompactAllowed` becomes true. Nothing else moves: the
 *    offload thresholds and targets stay untouched.
 *
 * Off, absent, non-positive or a window we do not know: the base policy is
 * returned untouched (the same instance), so a caller that never opts in
 * behaves exactly as before this feature existed.
 *
 * Pure: no prefs, no logging, no Context. [AutoCompactPrefs] stores the
 * values; [com.openminis.app.ui.chat.ChatViewModel] decides when to read them.
 */
object AutoCompactPolicy {

    /**
     * Share of the context window used as the stand-in safety line when the
     * base policy has no compact threshold of its own. Same 85% the user-cap
     * tier uses ([ContextPolicy.forUserCap]), so a capped session and a
     * threshold-only session draw their ceiling in the same place.
     */
    private const val WINDOW_CAP_PERCENT = 85L

    /**
     * [base] as it should be judged with a user threshold in force.
     *
     * @param base the policy for the live window, from
     *   [ContextPolicy.forContextWindow] / [ContextPolicy.forUserCap].
     * @param contextWindow the effective window in tokens (model window
     *   clamped by a group cap); `<= 0` means unknown — returns [base].
     * @param enabled the user's auto-compact switch. Off means "no override",
     *   not "never compact": [base] itself still decides.
     * @param thresholdTokens the user's token threshold; `null`, `0` and
     *   negative values mean "not set" and return [base].
     */
    fun apply(
        base: ContextPolicy,
        contextWindow: Int,
        enabled: Boolean,
        thresholdTokens: Int?,
    ): ContextPolicy {
        if (!enabled) return base
        val requested = thresholdTokens ?: return base
        if (requested <= 0) return base
        if (contextWindow <= 0) return base

        // The ceiling this threshold may not cross. Long maths: a 2M window
        // times 85 still fits an Int, but the multiplication is the one place
        // a future window size could overflow.
        val safetyLine = if (base.compactThreshold > 0) {
            base.compactThreshold
        } else {
            ((contextWindow.toLong() * WINDOW_CAP_PERCENT) / 100L)
                .coerceIn(1L, Int.MAX_VALUE.toLong())
                .toInt()
        }
        val effective = minOf(requested, safetyLine)
        if (effective <= 0) return base

        // A base with a line keeps its own flags (the 64K+ tiers and a user
        // cap already allow a summary). A base WITHOUT one is the "too small
        // to compact" verdict this feature overrides.
        val hadOwnLine = base.compactThreshold > 0
        return base.copy(
            compactThreshold = effective,
            exhaustedOnly = if (hadOwnLine) base.exhaustedOnly else false,
            manualCompactAllowed = if (hadOwnLine) base.manualCompactAllowed else true,
        )
    }
}
