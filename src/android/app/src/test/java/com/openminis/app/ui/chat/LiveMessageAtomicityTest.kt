package com.openminis.app.ui.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LiveMessageAtomicityTest {
    private data class Row(val id: String, val sent: Boolean = false)

    @Test fun `deterministic append during IO completion survives CAS retry`() {
        val messages = MutableStateFlow(listOf(Row("assistant")))
        val read = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            var first = true
            val completion = pool.submit {
                messages.updateItems { row ->
                    if (first) {
                        first = false
                        read.countDown()
                        check(resume.await(5, TimeUnit.SECONDS))
                    }
                    row.copy(sent = true)
                }
            }
            check(read.await(5, TimeUnit.SECONDS))
            messages.update { it + Row("user-followup") }
            resume.countDown()
            completion.get(5, TimeUnit.SECONDS)
            assertEquals(listOf("assistant", "user-followup"), messages.value.map { it.id })
            assertTrue(messages.value.first().sent)
        } finally { resume.countDown(); pool.shutdownNow() }
    }
    @Test fun `parallel followups and stream patches keep all identities`() {
        val messages = MutableStateFlow(listOf(Row("assistant")))
        val pool = Executors.newFixedThreadPool(4)
        try {
            val futures = (0 until 4).map { thread -> pool.submit {
                repeat(250) { index ->
                    messages.update { it + Row("$thread-$index") }
                    messages.updateItems { if (it.id == "assistant") it.copy(sent = true) else it }
                }
            } }
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1001, messages.value.size)
            assertEquals(1001, messages.value.map { it.id }.toSet().size)
        } finally { pool.shutdownNow() }
    }
    @Test fun `pre-fix whole-list write deterministically loses the followup`() {
        val messages = MutableStateFlow(listOf(Row("assistant")))
        val stale = messages.value
        messages.value = messages.value + Row("user-followup")
        messages.value = stale.map { it.copy(sent = true) }
        assertEquals(listOf("assistant"), messages.value.map { it.id })
    }
}
