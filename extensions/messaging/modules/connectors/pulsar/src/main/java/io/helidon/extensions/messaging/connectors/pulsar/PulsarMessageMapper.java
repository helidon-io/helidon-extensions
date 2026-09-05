/*
 * Copyright (c) 2026 Oracle and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.helidon.extensions.messaging.connectors.pulsar;

import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import io.helidon.messaging.DeadLetterMessage;
import io.helidon.messaging.Message;
import io.helidon.messaging.MessageHeader;
import io.helidon.messaging.MessageHeaderValue;
import io.helidon.messaging.MessageHeaders;
import io.helidon.messaging.MessagingException;

import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.TypedMessageBuilder;

import static io.helidon.extensions.messaging.connectors.pulsar.PulsarConnectorProvider.DLQ_ORIGINAL_MESSAGE_ID_HEADER;
import static io.helidon.extensions.messaging.connectors.pulsar.PulsarConnectorProvider.DLQ_ORIGINAL_PRODUCER_NAME_HEADER;
import static io.helidon.extensions.messaging.connectors.pulsar.PulsarConnectorProvider.DLQ_ORIGINAL_PUBLISH_TIME_HEADER;
import static io.helidon.extensions.messaging.connectors.pulsar.PulsarConnectorProvider.DLQ_ORIGINAL_REDELIVERY_COUNT_HEADER;
import static io.helidon.extensions.messaging.connectors.pulsar.PulsarConnectorProvider.DLQ_ORIGINAL_SEQUENCE_ID_HEADER;
import static io.helidon.extensions.messaging.connectors.pulsar.PulsarConnectorProvider.DLQ_ORIGINAL_TOPIC_HEADER;

final class PulsarMessageMapper {
    private static final String LEGACY_FAILURE_TYPE_HEADER = "helidon_messaging_dead_letter_failure_type";
    private static final String LEGACY_FAILURE_MESSAGE_HEADER = "helidon_messaging_dead_letter_failure_message";
    private static final Set<String> RESERVED_HEADERS = Set.of(DeadLetterMessage.SOURCE_CHANNEL_HEADER,
                                                                DeadLetterMessage.ATTEMPTS_HEADER,
                                                                LEGACY_FAILURE_TYPE_HEADER,
                                                                LEGACY_FAILURE_MESSAGE_HEADER,
                                                                DLQ_ORIGINAL_TOPIC_HEADER,
                                                                DLQ_ORIGINAL_MESSAGE_ID_HEADER,
                                                                DLQ_ORIGINAL_PUBLISH_TIME_HEADER,
                                                                DLQ_ORIGINAL_PRODUCER_NAME_HEADER,
                                                                DLQ_ORIGINAL_SEQUENCE_ID_HEADER,
                                                                DLQ_ORIGINAL_REDELIVERY_COUNT_HEADER);

    private PulsarMessageMapper() {
    }

    static PulsarMessage<Object> fromPulsarMessage(org.apache.pulsar.client.api.Message<Object> message,
                                                   PulsarConnectorConfig config,
                                                   PulsarSchemaResolver.ResolvedSchema schema) {
        Objects.requireNonNull(message);
        int size = message.size();
        if (size < 0) {
            throw new MessagingException("Pulsar message declared a negative payload size: " + size);
        }
        if (size > config.maxMessageBytes()) {
            throw new MessagingException("Pulsar message payload size " + size
                                                 + " exceeds max-message-bytes " + config.maxMessageBytes());
        }
        Object entity = schema.snapshot(message.getValue());
        if (entity == null) {
            throw new MessagingException("Pulsar message payload is null");
        }
        return PulsarMessageImpl.incoming(entity, message);
    }

    static PulsarMessage<Object> metadataOnly(org.apache.pulsar.client.api.Message<?> message) {
        Objects.requireNonNull(message);
        return PulsarMessageImpl.rejected(message);
    }

    static CompletableFuture<MessageId> send(Producer<Object> producer,
                                             Message<?> message,
                                             PulsarSchemaResolver.ResolvedSchema schema) {
        Objects.requireNonNull(producer);
        OutgoingMapping mapping = outgoingMapping(Objects.requireNonNull(message));
        PulsarMessage<?> pulsarMessage = mapping.pulsarMessage();
        Object entity = message instanceof DeadLetterMessage<?>
                && pulsarMessage instanceof PulsarMessageImpl<?> implementation
                && !implementation.entityAvailable()
                ? null
                : schema.snapshot(message.entity());
        TypedMessageBuilder<Object> builder = producer.newMessage()
                .value(entity)
                .properties(mapping.properties());
        if (pulsarMessage != null) {
            if (pulsarMessage.base64EncodedKey()) {
                pulsarMessage.keyBytes().ifPresent(builder::keyBytes);
            } else {
                pulsarMessage.key().ifPresent(builder::key);
            }
            pulsarMessage.orderingKey().ifPresent(builder::orderingKey);
            pulsarMessage.eventTime().ifPresent(builder::eventTime);
        }
        return builder.sendAsync();
    }

    private static OutgoingMapping outgoingMapping(Message<?> message) {
        if (!(message instanceof DeadLetterMessage<?> deadLetterMessage)) {
            return new OutgoingMapping(copyHeaders(message.headers(), Set.of()),
                                       message instanceof PulsarMessage<?> pulsarMessage ? pulsarMessage : null);
        }
        Message<?> original = deadLetterMessage.originalMessage();
        PulsarMessage<?> pulsarMessage = original instanceof PulsarMessage<?> actual ? actual : null;
        Map<String, String> properties = new LinkedHashMap<>(
                copyHeaders(deadLetterMessage.headers(), RESERVED_HEADERS));
        properties.put(DeadLetterMessage.SOURCE_CHANNEL_HEADER, deadLetterMessage.sourceChannel());
        properties.put(DeadLetterMessage.ATTEMPTS_HEADER, String.valueOf(deadLetterMessage.attempts()));
        if (pulsarMessage != null) {
            pulsarMessage.topic().ifPresent(value -> properties.put(DLQ_ORIGINAL_TOPIC_HEADER, value));
            pulsarMessage.messageId().ifPresent(value -> properties.put(DLQ_ORIGINAL_MESSAGE_ID_HEADER,
                                                                         Base64.getEncoder().encodeToString(value)));
            pulsarMessage.publishTime().ifPresent(value -> properties.put(DLQ_ORIGINAL_PUBLISH_TIME_HEADER,
                                                                           String.valueOf(value)));
            pulsarMessage.producerName().ifPresent(value -> properties.put(DLQ_ORIGINAL_PRODUCER_NAME_HEADER, value));
            pulsarMessage.sequenceId().ifPresent(value -> properties.put(DLQ_ORIGINAL_SEQUENCE_ID_HEADER,
                                                                          String.valueOf(value)));
            pulsarMessage.redeliveryCount().ifPresent(value -> properties.put(DLQ_ORIGINAL_REDELIVERY_COUNT_HEADER,
                                                                               String.valueOf(value)));
        }
        return new OutgoingMapping(immutableProperties(properties), pulsarMessage);
    }

    private static Map<String, String> copyHeaders(MessageHeaders headers, Set<String> excludedNames) {
        Map<String, String> result = new LinkedHashMap<>();
        for (MessageHeader header : Objects.requireNonNull(headers)) {
            if (excludedNames.contains(header.name())) {
                continue;
            }
            MessageHeaderValue value = header.value();
            if (!(value instanceof MessageHeaderValue.TextValue textValue)) {
                throw new MessagingException("Pulsar properties support only text message headers; header '"
                                                     + header.name() + "' has value type "
                                                     + value.getClass().getSimpleName());
            }
            if (result.putIfAbsent(header.name(), textValue.value()) != null) {
                throw new MessagingException("Pulsar properties do not support duplicate message header '"
                                                     + header.name() + "'");
            }
        }
        return immutableProperties(result);
    }

    private static Map<String, String> immutableProperties(Map<String, String> properties) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    }

    private record OutgoingMapping(Map<String, String> properties, PulsarMessage<?> pulsarMessage) {
        private OutgoingMapping {
            Objects.requireNonNull(properties);
        }
    }
}
