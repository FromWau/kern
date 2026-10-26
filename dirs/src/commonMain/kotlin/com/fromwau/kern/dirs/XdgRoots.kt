package com.fromwau.kern.dirs

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import kotlinx.io.files.Path

/**
 * [env] with every value [asRoot], so a relative one reads as unset, and so does a blank one. Each value read as unset
 * that way, or by [privateFolder], is kept in [ignored], once per name, in the order it was first read.
 */
internal class CheckedVariables(private val env: (String) -> String?) : (String) -> String? {
    private val rejected = LinkedHashMap<String, IgnoredVariable>()

    val ignored: List<IgnoredVariable> get() = rejected.values.toList()

    override fun invoke(name: String): String? {
        val value = valueOf(name) ?: return null
        return value.asRoot() ?: ignore(name, value, IgnoredBecause.NotAbsolute)
    }

    /**
     * The variable [name] as [invoke] reads it, also read as unset when [isPrivate] says the folder it names is not one
     * only this user may enter.
     */
    fun privateFolder(
        name: String,
        isPrivate: (Path) -> Boolean,
    ): String? {
        val value = valueOf(name) ?: return null
        val root = value.asRoot() ?: return ignore(name, value, IgnoredBecause.NotAbsolute)
        return if (isPrivate(Path(root))) root else ignore(name, value, IgnoredBecause.NotPrivateFolder)
    }

    /** The variable [name] as set, a blank one read as unset. */
    private fun valueOf(name: String): String? {
        val value = env(name) ?: return null
        return if (value.isBlank()) ignore(name, value, IgnoredBecause.Blank) else value
    }

    private fun ignore(
        name: String,
        value: String,
        reason: IgnoredBecause,
    ): Nothing? {
        if (name !in rejected) rejected[name] = IgnoredVariable(name, value, reason)
        return null
    }
}

/**
 * This answer with the variables [variables] ignored attached, to the roots or to the root that could not be had; or,
 * when [strict], the error for the first of them, as [toError] gives it.
 */
internal fun Result<BaseDirs, DirsError>.withIgnored(
    variables: CheckedVariables,
    strict: Boolean,
): Result<BaseDirs, DirsError> {
    val ignored = variables.ignored
    val first = ignored.firstOrNull()
    if (strict && first != null) return Err(first.toError())
    return when (this) {
        is Result.Success -> Ok(value.copy(ignored = ignored))
        is Result.Error -> Err((error as? DirsError.UnableToResolve)?.copy(ignored = ignored) ?: error)
    }
}

/**
 * This value as a root, or null when it is not an absolute path, since a relative one would move with the working
 * directory. Trailing separators are dropped, so a root built under it never holds `//`, while `/` or `C:\` keeps its
 * own, the way kotlinx-io spells a [Path].
 */
internal fun String.asRoot(): String? = Path(this).takeIf { it.isAbsolute }?.toString()

/**
 * The XDG roots, the one rule every XDG layout resolves by: home from `$HOME`, else [accountHome], the account's own
 * home, asked only without it; [tempHome]; each root from its `XDG_*` variable in [env] when that is set, else under
 * home; and, when [isPrivateFolder] is given, the runtime root from `$XDG_RUNTIME_DIR` when it says only this user may
 * enter the folder it names. The variables are read in that order.
 */
internal fun resolveXdg(
    env: CheckedVariables,
    accountHome: () -> String?,
    tempHome: () -> Path,
    isPrivateFolder: ((Path) -> Boolean)?,
): Result<BaseDirs, DirsError> {
    // The account's home ignores $HOME, which XDG's defaults are relative to.
    val home = env("HOME") ?: accountHome() ?: return Err(DirsError.UnableToResolve(DirKind.Home))
    val temp = tempHome()

    fun root(
        variable: String,
        vararg underHome: String,
    ): Path = env(variable)?.let(::Path) ?: Path(home, *underHome)

    val configHome = root("XDG_CONFIG_HOME", ".config")
    val dataHome = root("XDG_DATA_HOME", ".local", "share")
    val stateHome = root("XDG_STATE_HOME", ".local", "state")
    val cacheHome = root("XDG_CACHE_HOME", ".cache")
    val runtimeHome = isPrivateFolder?.let { env.privateFolder("XDG_RUNTIME_DIR", it) }?.let(::Path)
    return Ok(
        BaseDirs(
            home = Path(home),
            configHome = configHome,
            dataHome = dataHome,
            stateHome = stateHome,
            cacheHome = cacheHome,
            tempHome = temp,
            runtimeHome = runtimeHome,
        ),
    )
}

/**
 * Whether [path] is a folder this user owns that its group and others may not enter, read through any link, as the
 * XDG Base Directory spec requires of `$XDG_RUNTIME_DIR`. False where the platform cannot tell.
 */
internal expect fun isPrivateFolder(path: Path): Boolean

/** The group's and everyone else's permission bits, none of which a private folder may have. */
internal const val GROUP_AND_OTHER_BITS = 0b000_111_111
