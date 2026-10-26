package com.fromwau.kern.fs

/** Marks what kern's own modules, such as fs-test, use from fs. It may change in any release. */
@RequiresOptIn(
    message = "For kern's own modules, such as fs-test; it may change in any release.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
public annotation class InternalKernApi
