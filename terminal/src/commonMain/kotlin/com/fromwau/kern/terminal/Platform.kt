package com.fromwau.kern.terminal

/** What a platform knows about its own stdio. Policy over these answers lives in [toTerminal], never in an actual. */
internal class PlatformIo(
    val writeOut: (String) -> Unit,
    val writeErr: (String) -> Unit,
    // The JVM's only probe covers stdin and stdout together; native asks about stdout alone.
    val isTty: Boolean,
    /** Detected width in columns, or null where the platform cannot detect it (every JVM and Android run). */
    val width: Int?,
    /** Whether the output handle can render ANSI escapes right now. */
    val ansiCapable: Boolean,
    val env: (String) -> String?,
    // Only platforms that survive a closed pipe answer: SIGPIPE kills native first, the JVM latches a flag.
    val writeFailed: () -> Boolean = { false },
)

/** The platform's own stdio. Constructing it may configure the terminal (Windows code page and VT mode). */
internal expect fun platformIo(): PlatformIo

/**
 * The real terminal for this process, with [ansiEnabled] and [resolveColumns] already applied.
 *
 * On Windows the first call also opts the console into virtual-terminal processing and UTF-8 output, both
 * of which must happen before anything is written, so ask for it early rather than per line.
 */
public fun defaultTerminal(): Terminal = platformIo().toTerminal()

/** Applies the shared width and colour policy to a platform's answers. */
internal fun PlatformIo.toTerminal(): Terminal = object : Terminal {
    override fun out(text: String) = writeOut(text)
    override fun err(text: String) = writeErr(text)
    override val columns: Int = resolveColumns(env, width)
    override val ansi: Boolean = ansiEnabled(isTty, env, ansiCapable)
    override fun writeErrored(): Boolean = writeFailed()
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

/** `FORCE_COLOR` / `CLICOLOR_FORCE` force colour when set to anything other than the opt-out value `"0"`. */
private fun String?.forcesColor(): Boolean = this != null && this != "0"

/** Terminal width: `COLUMNS` env override wins, then the [detected] ioctl/Win32 width, then an 80 fallback. */
public fun resolveColumns(env: (String) -> String?, detected: Int? = null): Int =
    env("COLUMNS")?.toIntOrNull()?.takeIf { it > 0 }
        ?: detected?.takeIf { it > 0 }
        ?: 80
