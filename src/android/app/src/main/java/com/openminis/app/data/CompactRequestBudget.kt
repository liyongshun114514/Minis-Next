package com.openminis.app.data

/** Request headroom belongs to the compaction model, not the chat model. */
internal object CompactRequestBudget {
    fun maxOutputTokens(contextWindow: Int, estimatedInput: Int, modelMaxOutput: Int?): Int {
        require(contextWindow > 0 && estimatedInput >= 0) { "Unknown compaction context capacity." }
        val remaining = contextWindow.toLong() - estimatedInput.toLong()
        check(remaining >= 256) { "Compaction input exceeds the selected model's context window." }
        return minOf(8192L, remaining, (modelMaxOutput ?: 8192).toLong().coerceAtLeast(1L)).toInt()
    }
}
