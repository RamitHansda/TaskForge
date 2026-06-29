package com.taskforge.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

internal class ExecutionScheduler(private val scope: CoroutineScope) {
    fun requestSchedule(
        runtime: ExecutionRuntime,
        runCycle: suspend (ExecutionRuntime) -> Unit,
    ) {
        scope.launch {
            val shouldStart = runtime.mutex.withLock {
                if (runtime.status in terminalExecutionStates) {
                    return@withLock false
                }
                runtime.scheduleRequested = true
                if (runtime.schedulerRunning) {
                    false
                } else {
                    runtime.schedulerRunning = true
                    true
                }
            }
            if (shouldStart) {
                runScheduler(runtime, runCycle)
            }
        }
    }

    private suspend fun runScheduler(
        runtime: ExecutionRuntime,
        runCycle: suspend (ExecutionRuntime) -> Unit,
    ) {
        while (true) {
            val shouldRun = runtime.mutex.withLock {
                if (runtime.status in terminalExecutionStates) {
                    runtime.scheduleRequested = false
                    runtime.schedulerRunning = false
                    return@withLock false
                }
                if (!runtime.scheduleRequested) {
                    runtime.schedulerRunning = false
                    return@withLock false
                }
                runtime.scheduleRequested = false
                true
            }
            if (!shouldRun) {
                return
            }
            runCycle(runtime)
        }
    }
}
