package com.openminis.app.agent.jobs

import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class SubAgentConcurrencyTest {
    private val parent = "concurrency-regression"
    private fun queued(id: String) = AgentJobRegistry.QueuedDelegation(parent, "{}", id)
    private fun child(id: String) = AgentJobRegistry.register(
        title = id, origin = AgentJobOrigin.TOOL, trigger = AgentJobTrigger.Immediate,
        target = AgentJobTarget.ChildOfCurrent(parent, id), prompt = "work", then = AgentJobThen.None,
    )

    @Before fun reset() {
        AgentJobRegistry.unregisterQueuedStarter(parent)
        AgentJobRegistry.dropQueuedDelegations(parent, "test")
        AgentJobRegistry.resetForTest()
    }
    @After fun clean() = reset()

    @Test fun `user limit is clamped to one through eight`() {
        assertEquals(1, AgentJobRegistry.setUserMaxConcurrentChildJobs(-1))
        assertEquals(8, AgentJobRegistry.setUserMaxConcurrentChildJobs(100))
    }

    @Test fun `eight concurrent startup requests reserve eight slots without exceeding cap`() {
        AgentJobRegistry.setUserMaxConcurrentChildJobs(8)
        val gate = java.util.concurrent.CountDownLatch(1)
        val results = java.util.Collections.synchronizedList(mutableListOf<Boolean>())
        val threads = (1..16).map { n -> Thread {
            gate.await()
            results.add(AgentJobRegistry.reserveChildStart(parent, "tool-$n"))
        }.also { it.start() } }
        gate.countDown()
        threads.forEach { it.join(5_000); assertFalse(it.isAlive) }
        assertEquals(8, results.count { it })
        assertFalse(AgentJobRegistry.canStartChildJob(parent))
    }

    @Test fun `one drain fills every available slot before asynchronous registration`() {
        AgentJobRegistry.setUserMaxConcurrentChildJobs(8)
        val starts = mutableListOf<String>()
        AgentJobRegistry.registerQueuedStarter(parent) { starts.add(it.toolUseId); true }
        repeat(10) { AgentJobRegistry.enqueueDelegation(queued("tool-$it")) }
        AgentJobRegistry.drainIfStalled()
        assertEquals(8, starts.size)
        assertEquals(2, AgentJobRegistry.queuedCount(parent))
        assertFalse(AgentJobRegistry.canStartChildJob(parent))
        // Registration must replace (not add to) its startup reservation.
        val job = child("tool-0")
        assertFalse(AgentJobRegistry.canStartChildJob(parent))
        AgentJobRegistry.finish(job.id, AgentJobState.DONE, "done")
        assertEquals(9, starts.size)
        assertEquals(1, AgentJobRegistry.queuedCount(parent))
    }

    @Test fun `failed asynchronous startup releases slot and starts next queued task`() {
        AgentJobRegistry.setUserMaxConcurrentChildJobs(1)
        val starts = mutableListOf<String>()
        AgentJobRegistry.registerQueuedStarter(parent) { starts.add(it.toolUseId); true }
        AgentJobRegistry.enqueueDelegation(queued("first"))
        AgentJobRegistry.enqueueDelegation(queued("next"))
        AgentJobRegistry.drainIfStalled()
        assertEquals(listOf("first"), starts)
        AgentJobRegistry.releaseChildStart(parent, "first")
        assertEquals(listOf("first", "next"), starts)
        assertEquals(0, AgentJobRegistry.queuedCount(parent))
    }

    @Test fun `raising limit starts backlog and lowering does not cancel live jobs`() {
        AgentJobRegistry.setUserMaxConcurrentChildJobs(1)
        val job = child("live")
        val starts = mutableListOf<String>()
        AgentJobRegistry.registerQueuedStarter(parent) { starts.add(it.toolUseId); true }
        repeat(7) { AgentJobRegistry.enqueueDelegation(queued("tool-$it")) }
        AgentJobRegistry.setUserMaxConcurrentChildJobs(8)
        assertEquals(7, starts.size)
        AgentJobRegistry.setUserMaxConcurrentChildJobs(1)
        assertEquals(AgentJobState.PENDING, AgentJobRegistry.job(job.id)?.state)
        assertFalse(AgentJobRegistry.canStartChildJob(parent))
    }

    @Test fun `failed starter does not strand following queue entries`() {
        val starts = mutableListOf<String>()
        AgentJobRegistry.registerQueuedStarter(parent) { starts.add(it.toolUseId); it.toolUseId != "bad" }
        AgentJobRegistry.enqueueDelegation(queued("bad"))
        AgentJobRegistry.enqueueDelegation(queued("good"))
        AgentJobRegistry.drainIfStalled()
        assertEquals(listOf("bad", "good"), starts)
        assertEquals(0, AgentJobRegistry.queuedCount(parent))
    }

    @Test fun `cancelled startup cannot be revived by late running callback`() {
        val job = child("late")
        AgentJobRegistry.cancel(job.id, "stop")
        var stoppedLateChild = false
        AgentJobRegistry.markRunning(job.id, "child-session") { stoppedLateChild = true }
        assertEquals(AgentJobState.CANCELLED, AgentJobRegistry.job(job.id)?.state)
        assertTrue(stoppedLateChild)
        assertEquals(0, AgentJobRegistry.runningChildJobCount(parent))
    }

    @Test fun `failed starter notifies parent instead of leaving a queued spinner`() {
        var failedTool = ""
        AgentJobRegistry.registerQueuedFailureHandler(parent) { item, _ -> failedTool = item.toolUseId }
        AgentJobRegistry.registerQueuedStarter(parent) { false }
        AgentJobRegistry.enqueueDelegation(queued("bad"))
        AgentJobRegistry.drainIfStalled()
        assertEquals("bad", failedTool)
        assertEquals(0, AgentJobRegistry.queuedCount(parent))
    }

    @Test fun `stopping backlog releases startup reservations and prevents further starts`() {
        AgentJobRegistry.setUserMaxConcurrentChildJobs(1)
        val starts = mutableListOf<String>()
        AgentJobRegistry.registerQueuedStarter(parent) { starts.add(it.toolUseId); true }
        AgentJobRegistry.enqueueDelegation(queued("starting"))
        AgentJobRegistry.enqueueDelegation(queued("waiting"))
        AgentJobRegistry.drainIfStalled()
        assertTrue(AgentJobRegistry.hasAgentWork(parent))
        AgentJobRegistry.dropQueuedDelegations(parent, "stop")
        AgentJobRegistry.releaseChildStart(parent, "starting")
        assertEquals(listOf("starting"), starts)
        assertFalse(AgentJobRegistry.hasAgentWork(parent))
    }
}
