package com.fromwau.kern.dirs

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import kotlinx.io.files.Path
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermission

/**
 * @param layout which convention macOS uses; Linux and Windows have one each and ignore it.
 * @param strict whether a blank variable is [DirsError.Blank], one set to something other than an absolute path
 *   [DirsError.NotAbsolute], and an `XDG_RUNTIME_DIR` that names no private folder [DirsError.NotPrivate], rather than
 *   listed in [BaseDirs.ignored].
 */
public actual class BaseDirsFactory(
    private val layout: DirLayout = DirLayout.Native,
    private val strict: Boolean = false,
) {
    public actual fun create(): Result<BaseDirs, DirsError> =
        baseDirFor(
            osName = System.getProperty("os.name").orEmpty(),
            env = System::getenv,
            userHome = System.getProperty("user.home"),
            layout = layout,
            strict = strict,
        )
}

/**
 * Picks the rules for [osName]. [env] reads the raw environment; a blank value counts as unset unless [strict] makes
 * it [DirsError.Blank], and so does a relative one unless [strict] makes it [DirsError.NotAbsolute]. A [userHome] that
 * is not an absolute path counts as unset as well: the JDK reports `?` when it cannot find the account.
 * [isPrivateFolder] decides whether `XDG_RUNTIME_DIR` is taken.
 */
internal fun baseDirFor(
    osName: String,
    env: (String) -> String?,
    userHome: String?,
    layout: DirLayout = DirLayout.Native,
    strict: Boolean = false,
    isPrivateFolder: (Path) -> Boolean = ::isPrivateFolder,
): Result<BaseDirs, DirsError> {
    val pathVariable = CheckedVariables(env)
    val home = userHome?.asRoot()
    val os = osName.lowercase()
    val dirs = when {
        // Checked before "win", which "darwin" contains.
        "mac" in os || "darwin" in os -> when (layout) {
            DirLayout.Native -> appleBaseDir(pathVariable, home)
            // As on Linux but with no runtime root, which no folder on macOS has the guarantees of.
            DirLayout.Xdg -> resolveXdg(pathVariable, { home }, { tempRoot(pathVariable) }, isPrivateFolder = null)
        }
        "win" in os -> windowsBaseDir(pathVariable, home)
        else -> return linuxBaseDir(env, { home }, strict, isPrivateFolder)
    }
    return dirs.withIgnored(pathVariable, strict)
}

internal fun appleBaseDir(
    env: (String) -> String?,
    userHome: String?,
): Result<BaseDirs, DirsError> {
    val home = userHome ?: return Err(DirsError.UnableToResolve(DirKind.Home))
    val support = Path("$home/Library/Application Support")

    return Ok(
        BaseDirs(
            home = Path(home),
            configHome = support,
            dataHome = support,
            stateHome = support,
            cacheHome = Path("$home/Library/Caches"),
            tempHome = tempRoot(env),
            naming = AppNaming.Apple,
        ),
    )
}

/**
 * Whether [path] is a folder this process's user owns that its group and others may not enter, read through any link.
 * The owner's user id is compared with that of `/proc/self`, which is this process's effective user. Without the unix
 * attribute view or `/proc/self`, the folder counts as not private.
 */
internal actual fun isPrivateFolder(path: Path): Boolean = try {
    val folder = Paths.get(path.toString())
    val attributes = Files.readAttributes(folder, PosixFileAttributes::class.java)
    attributes.isDirectory &&
        Files.getAttribute(folder, "unix:uid") == Files.getAttribute(Paths.get("/proc/self"), "unix:uid") &&
        attributes.permissions().all { it in OWNER_PERMISSIONS }
} catch (_: Exception) {
    false
}

private val OWNER_PERMISSIONS = setOf(
    PosixFilePermission.OWNER_READ,
    PosixFilePermission.OWNER_WRITE,
    PosixFilePermission.OWNER_EXECUTE,
)
