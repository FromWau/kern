package com.fromwau.kern.logger.sentry

import com.fromwau.kern.logger.CloseError
import com.fromwau.kern.logger.ConfiguredSink
import com.fromwau.kern.logger.Flushed
import com.fromwau.kern.logger.Log
import com.fromwau.kern.logger.SinkError
import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.errorOrNull
import com.fromwau.kern.result.onError
import io.sentry.ScopeType
import io.sentry.kotlin.multiplatform.Sentry
import io.sentry.kotlin.multiplatform.SentryEvent
import io.sentry.kotlin.multiplatform.SentryOptions
import io.sentry.kotlin.multiplatform.protocol.Message
import io.sentry.kotlin.multiplatform.log.SentryLog
import io.sentry.kotlin.multiplatform.protocol.Breadcrumb
import io.sentry.kotlin.multiplatform.protocol.SentryException
import io.sentry.kotlin.multiplatform.protocol.User
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

// The README's snippets, kept here so they compile against the real API and do what the README says.
class ReadmeSnippetsJvmTest {
    private var inits = 0
    private val events = CopyOnWriteArrayList<SentryEvent>()
    private val logs = CopyOnWriteArrayList<SentryLog>()

    // Not in the README: counts each init and records each event and log instead of sending it.
    private fun record(options: SentryOptions) {
        inits++
        options.beforeSend = { event ->
            events += event
            null
        }
        options.logs.beforeSend = { log ->
            logs += log
            null
        }
    }

    private fun grantConsent(dsn: String) {
        if (!SentrySink.sendsHere || crashReporting) return
        Sentry.init {
            it.dsn = dsn
            it.logs.enabled = true
            record(it)
        }
        Log.configure { it.copy(sinks = it.sinks + ConfiguredSink(SentrySink())) }
            .onError { error -> Log.tag("App").w(error) { "not logging to Sentry" } }
    }

    private fun revokeConsent(flushTimeout: Duration = 5.seconds) {
        Log.configure { c -> c.copy(sinks = c.sinks.filterNot { it.sink is SentrySink }) }
            .onError { error -> Log.tag("App").w(error) { "still logging to Sentry" } }
        val flushed = Log.flush(flushTimeout)
        if (flushed != Ok(Flushed.FullyWritten)) Log.tag("App").w { "revoke left $flushed" }
        SentrySink.clearBreadcrumbs()
        io.sentry.Sentry.configureScope(ScopeType.GLOBAL) { it.user = null }
        Sentry.close()
    }

    private val crashReporting: Boolean get() = Log.sinks.value.any { it.sink is SentrySink }

    @BeforeTest
    fun resetSentry() = resetSentryGlobals()

    // A test that fails before its revoke would otherwise leave the sink and Sentry to the next.
    @AfterTest
    fun removeSentry() {
        try {
            val _ = Log.configure { c -> c.copy(sinks = c.sinks.filterNot { it.sink is SentrySink }) }
            Sentry.close()
        } finally {
            resetSentryGlobals()
        }
    }

    private fun SentryOptions.fingerprintLikeKern() {
        beforeSend = { event ->
            val tag = event.getTag("tag")
            val group = event.getTag("error.type") ?: event.message?.formatted
            if (tag != null && group != null && event.exceptions.isEmpty()) {
                event.fingerprint = mutableListOf(tag, group)
            }
            event
        }
    }

    @Test
    fun `consent adds the sink and revoking it removes the sink`() {
        grantConsent("https://key@127.0.0.1/1")
        val granted = crashReporting
        revokeConsent()

        assertTrue(granted)
        assertFalse(crashReporting)
        assertFalse(Sentry.isEnabled())
    }

    @Test
    fun `granting consent twice keeps one sink and inits Sentry once`() {
        grantConsent("https://key@127.0.0.1/1")
        grantConsent("https://key@127.0.0.1/1")
        val sinks = Log.sinks.value.count { it.sink is SentrySink }
        revokeConsent()

        assertEquals(1, sinks)
        assertEquals(1, inits)
    }

    @Test
    fun `revoking consent sends what was logged under it and loses nothing`() {
        // A close reports every loss under a name since it was first added, earlier tests' included.
        val lostBefore = sentryLost(Log.close())
        grantConsent("https://key@127.0.0.1/1")

        // Enough that a Sentry.close() not waiting for the sink lands while it still writes.
        repeat(2000) { Log.tag("App").e { "entry $it" } }
        // A timeout that fits 2000 entries, as the README asks, on a machine slowed by a parallel build.
        revokeConsent(flushTimeout = 1.minutes)
        val lostAfter = sentryLost(Log.close())

        assertEquals(lostBefore, lostAfter)
        assertEquals((0..<2000).map { "entry $it" }, events.map { it.message?.formatted })
    }

    @Test
    fun `after a revoke and a new grant an event carries no breadcrumb from before the revoke`() {
        grantConsent("https://key@127.0.0.1/1")
        Log.tag("App").i { "before the revoke" }
        // Waits for the breadcrumb; a loss it reports is an earlier test's.
        val _ = Log.flush()
        Sentry.captureMessage("under the first grant")
        revokeConsent()
        grantConsent("https://key@127.0.0.1/1")

        Sentry.captureMessage("after the grant")
        revokeConsent()

        fun trailOf(message: String) = events.single { it.message?.formatted == message }.breadcrumbs.map { it.message }
        assertTrue("before the revoke" in trailOf("under the first grant"))
        assertFalse("before the revoke" in trailOf("after the grant"))
    }

    @Test
    fun `after a revoke and a new grant no event carries the user your app set on the global scope`() {
        grantConsent("https://key@127.0.0.1/1")
        io.sentry.Sentry.configureScope(ScopeType.GLOBAL) { it.user = io.sentry.protocol.User().apply { id = "u-old" } }
        Log.tag("App").e { "under the first grant" }
        revokeConsent()
        grantConsent("https://key@127.0.0.1/1")

        Log.tag("App").e { "after the grant" }
        Sentry.captureMessage("captured after the grant")
        revokeConsent()

        fun userOf(message: String) = events.single { it.message?.formatted == message }.user?.id
        assertEquals("u-old", userOf("under the first grant"))
        assertNull(userOf("after the grant"))
        assertNull(userOf("captured after the grant"))
    }

    @Test
    fun `a user and a breadcrumb your app set on IO threads reach nothing the sink sends after a new grant`() {
        grantConsent("https://key@127.0.0.1/1")
        onEveryIoThread {
            Sentry.setUser(User(id = "u-io"))
            Sentry.addBreadcrumb(Breadcrumb(message = "from io"))
        }
        revokeConsent()
        grantConsent("https://key@127.0.0.1/1")

        repeat(64) { Log.tag("App").e { "after the grant $it" } }
        revokeConsent()

        val sent = events.filter { it.message?.formatted.orEmpty().startsWith("after the grant") }
        assertEquals(64, sent.size)
        assertEquals(emptyList(), sent.mapNotNull { it.user?.id })
        assertEquals(emptyList(), sent.flatMap { event -> event.breadcrumbs.filter { it.message == "from io" } })
        assertTrue(logs.none { it.attributes["user.id"]?.value == "u-io" })
    }

    @Test
    fun `a user your app sets on the root scope after init reaches every event and log the sink sends`() {
        grantConsent("https://key@127.0.0.1/1")
        // Each thread takes its own scope the first time Sentry is used on it, here before the user is set.
        onEveryIoThread { Sentry.isEnabled() }
        Sentry.setUser(User(id = "u-root"))

        repeat(64) { Log.tag("App").e { "entry $it" } }
        revokeConsent()

        val sent = events.filter { it.message?.formatted.orEmpty().startsWith("entry") }
        assertEquals(List(64) { "u-root" }, sent.map { it.user?.id })
        val sentLogs = logs.filter { it.body.startsWith("entry") }
        assertEquals(List(64) { "u-root" }, sentLogs.map { it.attributes["user.id"]?.value })
    }

    @Test
    fun `the beforeSend recipe groups as the sink does on the JVM`() {
        val beforeSend = SentryOptions().apply { fingerprintLikeKern() }.beforeSend!!

        fun fingerprintOf(errorType: String?, thrown: Boolean = false, tag: String? = "Storage"): List<String> {
            val event = SentryEvent().apply {
                message = Message(formatted = "saving failed")
                tag?.let { setTag("tag", it) }
                errorType?.let { setTag("error.type", it) }
                if (thrown) exceptions = mutableListOf(SentryException(type = "IllegalStateException"))
            }
            return beforeSend(event)!!.fingerprint
        }

        assertEquals(listOf("Storage", "DiskFull"), fingerprintOf("DiskFull"))
        assertEquals(listOf("Storage", "saving failed"), fingerprintOf(null))
        assertEquals(emptyList(), fingerprintOf("DiskFull", thrown = true))
        assertEquals(emptyList(), fingerprintOf("DiskFull", tag = null))
    }

    /** Runs [block] on as many of Dispatchers.IO's threads at once as it allows. */
    private fun onEveryIoThread(block: () -> Unit) = runBlocking {
        val started = CountDownLatch(IO_THREADS)
        repeat(IO_THREADS) {
            launch(Dispatchers.IO) {
                started.countDown()
                started.await(5, TimeUnit.SECONDS)
                block()
            }
        }
    }

    private fun sentryLost(closed: EmptyResult<CloseError>): Long = closed
        .errorOrNull()
        ?.errors
        ?.get("sentry")
        .orEmpty()
        .filterIsInstance<SinkError.Lost>()
        .sumOf { it.count }
}

// Dispatchers.IO's default limit on the threads it runs at once.
private const val IO_THREADS = 64
