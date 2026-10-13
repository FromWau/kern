package com.fromwau.kern.result

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure

// Written by hand for the reason ResultSerializer is: a generated one puts a serialization descriptor in the
// class's static initializer, and building the error would then fail on a classpath without kotlinx.serialization.

internal class AllErrorsSerializer<E : ResultError>(errorSerializer: KSerializer<E>) : KSerializer<AllErrors<E>> {
    private val errorsSerializer = ListSerializer(errorSerializer)

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("com.fromwau.kern.result.AllErrors") {
        element("errors", errorsSerializer.descriptor)
    }

    override fun serialize(encoder: Encoder, value: AllErrors<E>) {
        encoder.encodeStructure(descriptor) { encodeSerializableElement(descriptor, 0, errorsSerializer, value.errors) }
    }

    override fun deserialize(decoder: Decoder): AllErrors<E> = decoder.decodeStructure(descriptor) {
        var errors: List<E>? = null
        while (true) {
            when (val index = decodeElementIndex(descriptor)) {
                CompositeDecoder.DECODE_DONE -> break
                0 -> errors = decodeSerializableElement(descriptor, 0, errorsSerializer)
                else -> throw SerializationException("Unexpected index $index while reading AllErrors")
            }
        }
        AllErrors(errors ?: throw SerializationException("AllErrors needs its errors"))
    }
}

internal class PartialSerializer<S, E : ResultError>(
    doneSerializer: KSerializer<S>,
    private val errorSerializer: KSerializer<E>,
) : KSerializer<Partial<S, E>> {
    private val doneListSerializer = ListSerializer(doneSerializer)

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("com.fromwau.kern.result.Partial") {
        element("done", doneListSerializer.descriptor)
        element("error", errorSerializer.descriptor)
    }

    override fun serialize(encoder: Encoder, value: Partial<S, E>) {
        encoder.encodeStructure(descriptor) {
            encodeSerializableElement(descriptor, 0, doneListSerializer, value.done)
            encodeSerializableElement(descriptor, 1, errorSerializer, value.error)
        }
    }

    override fun deserialize(decoder: Decoder): Partial<S, E> = decoder.decodeStructure(descriptor) {
        var done: List<S>? = null
        var error: E? = null
        while (true) {
            when (val index = decodeElementIndex(descriptor)) {
                CompositeDecoder.DECODE_DONE -> break
                0 -> done = decodeSerializableElement(descriptor, 0, doneListSerializer)
                1 -> error = decodeSerializableElement(descriptor, 1, errorSerializer)
                else -> throw SerializationException("Unexpected index $index while reading a Partial")
            }
        }
        Partial(
            done ?: throw SerializationException("A Partial needs its done list"),
            error ?: throw SerializationException("A Partial needs its error"),
        )
    }
}
