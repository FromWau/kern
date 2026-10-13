package com.fromwau.kern.dirs

/**
 * An app's full identity, from which [BaseDirs.forApp] builds the folder name each platform expects.
 *
 * @param qualifier the reverse domain's top level, such as `com`; only macOS and iOS use it.
 * @param organization who makes the app, such as `Foo Corp`; macOS, iOS and Windows use it.
 * @param application the app's name, such as `Bar App`.
 */
public data class AppId(
    val qualifier: String,
    val organization: String,
    val application: String,
)

/** How a platform names an app's folder from an [AppId]. The factory sets it on the [BaseDirs] it creates. */
public enum class AppNaming {
    /**
     * The application alone, lowercased, its words joined: `barapp`. Linux, Android, and macOS under
     * [DirLayout.Xdg].
     */
    Xdg,

    /** A bundle identifier, spaces turned into `-`: `com.Foo-Corp.Bar-App`. macOS and iOS. */
    Apple,

    /** The organization's folder holding the application's: `Foo Corp\Bar App`. Windows. */
    Windows,
}

/** The folders, outermost first, that hold the app under each root. An empty organization or qualifier is left out. */
internal fun AppNaming.foldersOf(id: AppId): List<String> = when (this) {
    AppNaming.Xdg -> listOf(
        id.application
            .trim()
            .split(WHITESPACE)
            .joinToString("") { it.lowercase() },
    )
    AppNaming.Apple -> listOf(
        listOf(id.qualifier, id.organization.replace(' ', '-'), id.application.replace(' ', '-'))
            .filter { it.isNotEmpty() }
            .joinToString("."),
    )
    AppNaming.Windows -> listOfNotNull(id.organization.takeIf { it.isNotEmpty() }, id.application)
}

private val WHITESPACE = Regex("""\s+""")
