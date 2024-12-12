import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

internal object ErrorCodeSerializer : KSerializer<ErrorCode> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ErrorCode", PrimitiveKind.STRING)

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: ErrorCode) {
        encoder.encodeInt(value.code)
    }

    override fun deserialize(decoder: Decoder): ErrorCode {
        val decodedString = decoder.decodeInt()
        return ErrorCode.Defined.entries.firstOrNull { it.code == decodedString }
            ?: ErrorCode.Unknown(decodedString)
    }
}

internal object RequestMethodSerializer : KSerializer<Method> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Method", PrimitiveKind.STRING)

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: Method) {
        encoder.encodeString(value.value)
    }

    override fun deserialize(decoder: Decoder): Method {
        val decodedString = decoder.decodeString()
        return Method.Defined.entries.firstOrNull { it.value == decodedString }
            ?: Method.Unknown(decodedString)
    }
}
