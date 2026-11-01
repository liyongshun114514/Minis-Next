package com.openminis.app.provider

import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.provider.openai.ThinkPrefixStreamParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Normalize relay-embedded reasoning for every wire protocol, not just Chat Completions.
 * Only a response PREFIX is recognized. Code examples and tags in ordinary prose stay literal.
 * The parser belongs to this collection (one request/attempt), never to the whole chat.
 */
internal fun Flow<LLMStreamChunk>.splitThinkingPrefix(): Flow<LLMStreamChunk> = flow {
    val parser = ThinkPrefixStreamParser(preserveBodyWhitespace = true)
    suspend fun emitSplit(output: ThinkPrefixStreamParser.Output) {
        if (output.thinking.isNotEmpty()) emit(LLMStreamChunk.ThinkingDelta(output.thinking))
        if (output.visible.isNotEmpty()) emit(LLMStreamChunk.Text(output.visible))
    }
    collect { chunk ->
        when (chunk) {
            is LLMStreamChunk.Text -> emitSplit(parser.feed(chunk.text))
            is LLMStreamChunk.ToolUseStart -> {
                emitSplit(parser.resolveAtToolBoundary())
                emit(chunk)
            }
            is LLMStreamChunk.Finished -> {
                emitSplit(parser.finishTurn())
                emit(chunk)
            }
            else -> emit(chunk)
        }
    }
    // Some compatible endpoints omit Finished; don't drop an undecided suffix at EOF.
    emitSplit(parser.finishTurn())
}
