import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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

internal object StopReasonSerializer : KSerializer<StopReason> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("StopReason", PrimitiveKind.STRING)

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: StopReason) {
        encoder.encodeString(value.value)
    }

    override fun deserialize(decoder: Decoder): StopReason {
        val decodedString = decoder.decodeString()
        return when (decodedString) {
            StopReason.StopSequence.value -> StopReason.StopSequence
            StopReason.MaxTokens.value -> StopReason.MaxTokens
            StopReason.EndTurn.value -> StopReason.EndTurn
            else -> StopReason.Other(decodedString)
        }
    }
}

internal object ReferencePolymorphicSerializer : JsonContentPolymorphicSerializer<Reference>(Reference::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<Reference> {
        return when (element.jsonObject.getValue("type").jsonPrimitive.content) {
            ResourceReference.TYPE -> ResourceReference.serializer()
            PromptReference.TYPE -> PromptReference.serializer()
            else -> UnknownReference.serializer()
        }
    }
}
