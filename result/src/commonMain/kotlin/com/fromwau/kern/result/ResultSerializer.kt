package com.fromwau.kern.result

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure

private const val SUCCESS_INDEX = 0
private const val ERROR_INDEX = 1

/**
 * How a [Result] is written and read. [Result] names it, so you never mention it: a property typed
 * `Result<User, CrudError>` uses this automatically.
 *
 * It is written by hand rather than generated, which is what keeps serialization an optional dependency.
 * A generated serializer would put a descriptor in [Result]'s static initializer, and constructing a
 * result would then fail on any classpath without kotlinx.serialization, whether or not anything ever
 * serialized one.
 *
 * A result encodes as an object holding exactly one of `success` or `error`, which every format can
 * express, so this is not tied to JSON.
 */
public class ResultSerializer<S, E : IError>(
    private val successSerializer: KSerializer<S>,
    private val errorSerializer: KSerializer<E>,
) : KSerializer<Result<S, E>> {

    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("com.fromwau.kern.result.Result") {
            element("success", successSerializer.descriptor, isOptional = true)
            element("error", errorSerializer.descriptor, isOptional = true)
        }

    override fun serialize(encoder: Encoder, value: Result<S, E>) {
        encoder.encodeStructure(descriptor) {
            when (value) {
                is Result.Success ->
                    encodeSerializableElement(descriptor, SUCCESS_INDEX, successSerializer, value.value)

                is Result.Error ->
                    encodeSerializableElement(descriptor, ERROR_INDEX, errorSerializer, value.error)
            }
        }
    }

    override fun deserialize(decoder: Decoder): Result<S, E> = decoder.decodeStructure(descriptor) {
        var decoded: Result<S, E>? = null

        while (true) {
            when (val index = decodeElementIndex(descriptor)) {
                SUCCESS_INDEX ->
                    decoded = Ok(decodeSerializableElement(descriptor, SUCCESS_INDEX, successSerializer))

                ERROR_INDEX ->
                    decoded = Err(decodeSerializableElement(descriptor, ERROR_INDEX, errorSerializer))

                CompositeDecoder.DECODE_DONE -> break

                else -> throw SerializationException("Unexpected index $index while reading a Result")
            }
        }

        decoded ?: throw SerializationException("A Result needs either a success or an error, found neither")
    }
}
