package com.fromwau.kern.terminal

/** Marks what kern's own modules, such as terminal-coroutines, use from the terminal. It may change in any release. */
@RequiresOptIn(
    message = "For kern's own modules, such as terminal-coroutines; it may change in any release.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
public annotation class InternalKernApi
