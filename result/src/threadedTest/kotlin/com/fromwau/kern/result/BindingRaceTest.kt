package com.fromwau.kern.result

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals

private data class NotFound(val id: Long) : ResultError

private const val RACE_ROUNDS = 2000

@OptIn(ExperimentalAtomicApi::class)
class BindingRaceTest {
    private fun find(id: Long): Result<String, NotFound> = Err(NotFound(id))

    @Test
    fun `a bind racing the block's return from another coroutine ends the block or fails that coroutine`() = runTest {
        val lost = withContext(Dispatchers.Default) { (1..RACE_ROUNDS).count { !bindRacingReturnIsKept() } }

        assertEquals(0, lost, "rounds out of $RACE_ROUNDS where the block gave Ok and the bind's error was lost")
    }

    private suspend fun bindRacingReturnIsKept(): Boolean {
        val foreignFailure = AtomicReference<Throwable?>(null)
        val foreign = CoroutineScope(
            SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> foreignFailure.store(e) },
        )
        val binding = AtomicBoolean(false)
        lateinit var job: Job

        val saved: Result<Int, NotFound> = result {
            job = foreign.launch {
                binding.store(true)
                find(2).bind()
            }
            // Returning the moment the bind starts puts the two in a tight race.
            while (!binding.load()) continue
            7
        }
        job.join()

        return saved == Err(NotFound(2)) || foreignFailure.load() is IllegalStateException
    }
}
