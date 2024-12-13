import LoggingMessageNotification.SetLevelRequest
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
            ?: Method.Custom(decodedString)
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

internal object PromptMessageContentPolymorphicSerializer :
    JsonContentPolymorphicSerializer<PromptMessageContent>(PromptMessageContent::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<PromptMessageContent> {
        return when (element.jsonObject.getValue("type").jsonPrimitive.content) {
            ImageContent.TYPE -> ImageContent.serializer()
            TextContent.TYPE -> TextContent.serializer()
            EmbeddedResource.TYPE -> EmbeddedResource.serializer()
            else -> UnknownContent.serializer()
        }
    }
}

internal object PromptMessageContentTextOrImagePolymorphicSerializer :
    JsonContentPolymorphicSerializer<PromptMessageContentTextOrImage>(PromptMessageContentTextOrImage::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<PromptMessageContentTextOrImage> {
        return when (element.jsonObject.getValue("type").jsonPrimitive.content) {
            ImageContent.TYPE -> ImageContent.serializer()
            TextContent.TYPE -> TextContent.serializer()
            else -> UnknownContent.serializer()
        }
    }
}

private fun selectClientRequestDeserializer(element: JsonElement): DeserializationStrategy<ClientRequest>? {
    return when (element.jsonObject.getValue("method").jsonPrimitive.content) {
        Method.Defined.Ping.value -> PingRequest.serializer()
        Method.Defined.Initialize.value -> InitializeRequest.serializer()
        Method.Defined.CompletionComplete.value -> CompleteRequest.serializer()
        Method.Defined.LoggingSetLevel.value -> SetLevelRequest.serializer()
        Method.Defined.PromptsGet.value -> GetPromptRequest.serializer()
        Method.Defined.PromptsList.value -> ListPromptsRequest.serializer()
        Method.Defined.ResourcesList.value -> ListResourcesRequest.serializer()
        Method.Defined.ResourcesTemplatesList.value -> ListResourceTemplatesRequest.serializer()
        Method.Defined.ResourcesRead.value -> ReadResourceRequest.serializer()
        Method.Defined.ResourcesSubscribe.value -> SubscribeRequest.serializer()
        Method.Defined.ResourcesUnsubscribe.value -> UnsubscribeRequest.serializer()
        Method.Defined.ToolsCall.value -> CallToolRequest.serializer()
        Method.Defined.ToolsList.value -> ListToolsRequest.serializer()
        else -> null
    }
}

internal object ClientRequestPolymorphicSerializer :
    JsonContentPolymorphicSerializer<ClientRequest>(ClientRequest::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<ClientRequest> {
        return selectClientRequestDeserializer(element)
            ?: UnknownMethodRequestOrNotification.serializer()
    }
}

private fun selectClientNotificationDeserializer(element: JsonElement): DeserializationStrategy<ClientNotification>? {
    return when (element.jsonObject.getValue("method").jsonPrimitive.content) {
        Method.Defined.NotificationsCancelled.value -> CancelledNotification.serializer()
        Method.Defined.NotificationsProgress.value -> ProgressNotification.serializer()
        Method.Defined.NotificationsInitialized.value -> InitializedNotification.serializer()
        Method.Defined.NotificationsRootsListChanged.value -> RootsListChangedNotification.serializer()
        else -> null
    }
}

internal object ClientNotificationPolymorphicSerializer :
    JsonContentPolymorphicSerializer<ClientNotification>(ClientNotification::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<ClientNotification> {
        return selectClientNotificationDeserializer(element)
            ?: UnknownMethodRequestOrNotification.serializer()
    }
}

private fun selectServerRequestDeserializer(element: JsonElement): DeserializationStrategy<ServerRequest>? {
    return when (element.jsonObject.getValue("method").jsonPrimitive.content) {
        Method.Defined.Ping.value -> PingRequest.serializer()
        Method.Defined.SamplingCreateMessage.value -> CreateMessageRequest.serializer()
        Method.Defined.RootsList.value -> ListRootsRequest.serializer()
        else -> null
    }
}

internal object ServerRequestPolymorphicSerializer :
    JsonContentPolymorphicSerializer<ServerRequest>(ServerRequest::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<ServerRequest> {
        return selectServerRequestDeserializer(element)
            ?: UnknownMethodRequestOrNotification.serializer()
    }
}

private fun selectServerNotificationDeserializer(element: JsonElement): DeserializationStrategy<ServerNotification>? {
    return when (element.jsonObject.getValue("method").jsonPrimitive.content) {
        Method.Defined.NotificationsCancelled.value -> CancelledNotification.serializer()
        Method.Defined.NotificationsProgress.value -> ProgressNotification.serializer()
        Method.Defined.NotificationsMessage.value -> LoggingMessageNotification.serializer()
        Method.Defined.NotificationsResourcesUpdated.value -> ResourceUpdatedNotification.serializer()
        Method.Defined.NotificationsResourcesListChanged.value -> ResourceListChangedNotification.serializer()
        Method.Defined.ToolsList.value -> ToolListChangedNotification.serializer()
        Method.Defined.PromptsList.value -> PromptListChangedNotification.serializer()
        else -> null
    }
}

internal object ServerNotificationPolymorphicSerializer :
    JsonContentPolymorphicSerializer<ServerNotification>(ServerNotification::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<ServerNotification> {
        return selectServerNotificationDeserializer(element)
            ?: UnknownMethodRequestOrNotification.serializer()
    }
}

internal object NotificationPolymorphicSerializer :
    JsonContentPolymorphicSerializer<Notification>(Notification::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<Notification> {
        return selectClientNotificationDeserializer(element)
            ?: selectServerNotificationDeserializer(element)
            ?: UnknownMethodRequestOrNotification.serializer()
    }
}

internal object RequestPolymorphicSerializer :
    JsonContentPolymorphicSerializer<Request>(Request::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<Request> {
        return selectClientRequestDeserializer(element)
            ?: selectServerRequestDeserializer(element)
            ?: UnknownMethodRequestOrNotification.serializer()
    }
}

internal object JSONRPCMessagePolymorphicSerializer :
    JsonContentPolymorphicSerializer<JSONRPCMessage>(JSONRPCMessage::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<JSONRPCMessage> {
        return when {
            element.jsonObject.contains("message") -> JSONRPCError.serializer()
            !element.jsonObject.contains("method") -> JSONRPCResponse.serializer()
            element.jsonObject.contains("id") -> JSONRPCRequest.serializer()
            else -> JSONRPCNotification.serializer()
        }
    }
}
