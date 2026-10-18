package com.fromwau.kern.terminal

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** What a platform knows about its own stdio. Policy over these answers lives in [toTerminal], never in an actual. */
internal class PlatformIo(
    val writeOut: (String) -> Unit,
    val writeErr: (String) -> Unit,
    /** Whether standard output is a tty. The JVM's only probe covers stdin and stdout together. */
    val isTty: Boolean,
    /** Whether standard error is a tty. The JVM has no probe for it. */
    val errIsTty: Boolean = false,
    /** Detected width in columns, or null where it cannot be detected: JVM, Android, androidNative, WASI, a browser. */
    val width: Int?,
    /** Whether the standard output handle can render ANSI escapes right now. */
    val ansiCapable: Boolean,
    /** Whether the standard error handle can render ANSI escapes right now. */
    val errAnsiCapable: Boolean = ansiCapable,
    val env: (String) -> String?,
    /** Why a write to the stream failed, or null while every write to it landed. */
    val failureOf: (Stream) -> WriteError? = { null },
    /** Calls back once every write so far has landed or failed, for a platform that reports failures later. */
    val afterPendingWrites: (landed: () -> Unit) -> Unit = { landed -> landed() },
)

/** The platform's own stdio. Constructing it may configure the terminal (Windows code page and VT mode). */
internal expect fun platformIo(): PlatformIo

/**
 * The real terminal for this process, with [ansiEnabled] and [resolveColumns] already applied.
 *
 * In a Windows native (mingwX64) build the first call also opts the console into virtual-terminal processing and
 * UTF-8 output, both of which must happen before anything is written, so ask for it early rather than per line. The
 * JVM on Windows does neither.
 */
public fun defaultTerminal(): Terminal = platformIo().toTerminal()

/** Applies the shared width and colour policy to a platform's answers. */
internal fun PlatformIo.toTerminal(): Terminal = object : Terminal {
    override fun out(text: String) = writeOut(text)

    override fun err(text: String) = writeErr(text)

    override val columns: Int = resolveColumns(env, width)

    override val ansi: Boolean = ansiEnabled(isTty, env, ansiCapable)

    override val errAnsi: Boolean = ansiEnabled(errIsTty, env, errAnsiCapable)

    override val outIsTerminal: Boolean = isTty

    override val errIsTerminal: Boolean = errIsTty

    override fun writeResult(): EmptyResult<WriteError> =
        (failureOf(Stream.Out) ?: failureOf(Stream.Err))?.let { Err(it) } ?: Ok(Unit)

    override fun writeResult(stream: Stream): EmptyResult<WriteError> = failureOf(stream)?.let { Err(it) } ?: Ok(Unit)

    override fun afterWrites(block: (EmptyResult<WriteError>) -> Unit) = afterPendingWrites {
        super.afterWrites(block)
    }

    override suspend fun awaitWriteResult(): EmptyResult<WriteError> =
        suspendCoroutine { answered -> afterPendingWrites { answered.resume(writeResult()) } }
}

/**
 * Whether ANSI colour should be emitted, in one place so every caller agrees. Precedence: `NO_COLOR` >
 * `FORCE_COLOR`/`CLICOLOR_FORCE` > [supported] > `CLICOLOR=0`/dumb `TERM` > whether the handle is a real
 * tty. Forcing colour asks for the escapes regardless of what the handle can render right now, so it alone
 * bypasses [supported].
 *
 * [defaultTerminal] applies this for you. Call it directly only when you own the IO and still want the
 * standard answer, passing your own [env] lookup.
 */
public fun ansiEnabled(isTty: Boolean, env: (String) -> String?, supported: Boolean = true): Boolean =
    when {
        // Per the NO_COLOR spec (no-color.org), only a present AND non-empty value disables colour; an
        // empty value is treated as not-set, so it falls through to the rest of the ladder.
        !env("NO_COLOR").isNullOrEmpty() -> false
        env("FORCE_COLOR").forcesColor() || env("CLICOLOR_FORCE").forcesColor() -> true
        !supported -> false
        env("CLICOLOR") == "0" -> false
        env("TERM") == "dumb" -> false
        else -> isTty
    }

/**
 * `FORCE_COLOR` and `CLICOLOR_FORCE` both force colour when set to anything other than `"0"` or `"false"`. An
 * empty value counts as not set, as it does for `NO_COLOR`.
 */
private fun String?.forcesColor(): Boolean = !isNullOrEmpty() && this != "0" && this != "false"

/** Terminal width: the `COLUMNS` environment variable wins, then the [detected] width, then 80. */
public fun resolveColumns(env: (String) -> String?, detected: Int? = null): Int =
    env("COLUMNS")?.toIntOrNull()?.takeIf { it > 0 }
        ?: detected?.takeIf { it > 0 }
        ?: 80
