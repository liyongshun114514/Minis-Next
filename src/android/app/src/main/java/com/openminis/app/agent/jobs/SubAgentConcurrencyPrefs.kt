package com.openminis.app.agent.jobs

import android.content.Context

/** User-selected simultaneous helpers per parent; existing agent definitions are retained. */
object SubAgentConcurrencyPrefs {
    private const val PREFS = "sub_agent_execution"
    private const val KEY_LIMIT = "max_concurrent_per_parent"

    fun prime(context: Context) {
        val stored = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_LIMIT, AgentJobRegistry.MAX_CONCURRENT_CHILD_JOBS_PER_PARENT)
        AgentJobRegistry.setUserMaxConcurrentChildJobs(stored)
    }

    fun set(context: Context, count: Int) {
        val limit = AgentJobRegistry.setUserMaxConcurrentChildJobs(count)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_LIMIT, limit).apply()
    }
}
