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

private data class BadPort(val given: String) : ResultError

private const val RACE_ROUNDS = 2000

@OptIn(ExperimentalAtomicApi::class)
class CollectingRaceTest {
    private fun port(raw: String): Result<Int, BadPort> = raw.toIntOrNull()?.let { Ok(it) } ?: Err(BadPort(raw))

    @Test
    fun `a collect racing the block's return from another coroutine fails the block or fails that coroutine`() =
        runTest {
            val lost = withContext(Dispatchers.Default) { (1..RACE_ROUNDS).count { !collectRacingReturnIsKept() } }

            assertEquals(0, lost, "rounds out of $RACE_ROUNDS where the block gave Ok and the collected error was lost")
        }

    private suspend fun collectRacingReturnIsKept(): Boolean {
        val foreignFailure = AtomicReference<Throwable?>(null)
        val foreign = CoroutineScope(
            SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> foreignFailure.store(e) },
        )
        val collecting = AtomicBoolean(false)
        lateinit var job: Job

        val checked: Result<Int, AllErrors<BadPort>> = resultAll {
            job = foreign.launch {
                collecting.store(true)
                port("x").collect()
            }
            // Returning the moment the collect starts puts the two in a tight race.
            while (!collecting.load()) continue
            7
        }
        job.join()

        return checked == Err(AllErrors(listOf(BadPort("x")))) || foreignFailure.load() is IllegalStateException
    }
}
