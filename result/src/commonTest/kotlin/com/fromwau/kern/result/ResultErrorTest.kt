package com.fromwau.kern.result

import kotlin.test.Test
import kotlin.test.assertEquals

private sealed interface StorageError : ResultError {
    data class DiskFull(val free: Long) : StorageError

    data object ReadOnly : StorageError
}

private sealed interface PaymentError : ResultError {
    override val errorType: String get() = "Payment.${this::class.simpleName}"

    data object Declined : PaymentError

    data class Failed(val code: String) : PaymentError
}

private sealed interface CardError : ResultError {
    override val errorType: String get() = "Card.${this::class.simpleName}"

    data object Declined : CardError {
        override val errorType: String get() = "Payment.Declined"
    }
}

class ResultErrorTest {
    @Test
    fun `an error's type is its class's simple name by default and the same for every instance`() {
        assertEquals("DiskFull", StorageError.DiskFull(1).errorType)
        assertEquals("DiskFull", StorageError.DiskFull(2).errorType)
        assertEquals("ReadOnly", StorageError.ReadOnly.errorType)
    }

    @Test
    fun `a sealed hierarchy namespaces the type of every case in one line`() {
        assertEquals("Payment.Declined", PaymentError.Declined.errorType)
        assertEquals("Payment.Failed", PaymentError.Failed("51").errorType)
    }

    @Test
    fun `a case can name its type with a literal`() {
        assertEquals("Payment.Declined", CardError.Declined.errorType)
    }

    @Test
    fun `an anonymous error's type is ResultError`() {
        assertEquals("ResultError", object : ResultError {}.errorType)
    }
}
