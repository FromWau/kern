package com.fromwau.kern.exec

/** Why this JVM cannot start children, or null when it can; worked out on the first start. */
internal val unableToStart: String? by lazy {
    unsupportedSystem(System.getProperty("os.name"), System.getProperty("os.arch")) ?: nativeAccessRefusal()
}

/**
 * Why a JVM on [osName] and [osArch] cannot start children, or null when it can: the constants and struct layouts
 * [startFfmChild] uses are those of x86_64 and aarch64 Linux.
 */
internal fun unsupportedSystem(osName: String?, osArch: String?): String? = when {
    osName != "Linux" -> "starting a child on the JVM needs Linux, and this JVM runs on $osName"
    osArch != "amd64" && osArch != "aarch64" ->
        "starting a child on the JVM needs an x86_64 or aarch64 CPU, and this JVM runs on $osArch"
    else -> null
}

/** [NATIVE_ACCESS_DENIED] when [load], which loads the libc calls, is refused native access, else null. */
internal fun nativeAccessRefusal(load: () -> Unit = ::loadLibc): String? = try {
    load()
    null
} catch (_: IllegalCallerException) {
    NATIVE_ACCESS_DENIED
} catch (failure: ExceptionInInitializerError) {
    // The refusal thrown while Libc initialises arrives wrapped.
    if (failure.cause is IllegalCallerException) NATIVE_ACCESS_DENIED else throw failure
}

internal const val NATIVE_ACCESS_DENIED: String =
    "this JVM denies kern native access, which starting a child needs: run it with --enable-native-access=ALL-UNNAMED"
