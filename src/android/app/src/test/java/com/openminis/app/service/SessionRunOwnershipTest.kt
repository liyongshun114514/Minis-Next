package com.openminis.app.service

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SessionRunOwnershipTest {
    @After fun reset() {
        SessionActivityTracker.setCompletionListener(null)
        (0..63).forEach { SessionActivityTracker.setInactive("ownership-$it") }
        SessionActivityTracker.clearOverlayTasks()
    }
    @Test fun `old job completion cannot stop replacement or publish completion`() {
        val sid = "ownership-0"
        val old = Any()
        val current = Any()
        var completions = 0
        SessionActivityTracker.setCompletionListener { _, _ -> completions++ }
        SessionActivityTracker.setActive(sid, owner = old)
        SessionActivityTracker.setInactive(sid) // synchronous user stop
        SessionActivityTracker.setActive(sid, owner = current)
        completions = 0
        SessionActivityTracker.setInactive(sid, owner = old)
        assertTrue(SessionActivityTracker.isActive(sid))
        assertTrue(sid in SessionActivityTracker.processingSessions.value)
        assertTrue(SessionActivityTracker.ownsRun(sid, current))
        assertFalse(SessionActivityTracker.ownsRun(sid, old))
        assertFalse(SessionActivityTracker.overlayTasks.value.single { it.sessionId == sid }.finished)
        assertEquals(0, completions)
        SessionActivityTracker.setInactive(sid, owner = current)
        assertFalse(SessionActivityTracker.isProcessing(sid))
        assertEquals(1, completions)
    }
    @Test fun `parallel sessions cannot overwrite each other activity or processing union`() {
        val pool = Executors.newFixedThreadPool(8)
        try {
            repeat(20) {
                val gate = CountDownLatch(1)
                val owners = List(64) { Any() }
                val futures = owners.mapIndexed { i, owner -> pool.submit {
                    gate.await()
                    SessionActivityTracker.setActive("ownership-$i", owner = owner)
                } }
                gate.countDown()
                futures.forEach { it.get(10, TimeUnit.SECONDS) }
                val expected = owners.indices.map { "ownership-$it" }.toSet()
                assertTrue(SessionActivityTracker.activeSessions.value.containsAll(expected))
                assertTrue(SessionActivityTracker.processingSessions.value.containsAll(expected))
                owners.forEachIndexed { i, owner -> SessionActivityTracker.setInactive("ownership-$i", owner = owner) }
            }
        } finally { pool.shutdownNow() }
    }
}
