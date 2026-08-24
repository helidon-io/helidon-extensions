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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import io.helidon.messaging.DeadLetterMessage;
import io.helidon.messaging.Message;
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
    private static final Set<String> RESERVED_HEADERS = Set.of(DeadLetterMessage.SOURCE_CHANNEL_HEADER,
                                                                DeadLetterMessage.ATTEMPTS_HEADER,
                                                                DeadLetterMessage.FAILURE_TYPE_HEADER,
                                                                DeadLetterMessage.FAILURE_MESSAGE_HEADER,
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
        return PulsarMessageImpl.incoming(entity, message);
    }

    static PulsarMessage<Object> metadataOnly(org.apache.pulsar.client.api.Message<?> message) {
        return PulsarMessageImpl.incoming(null, message);
    }

    static CompletableFuture<MessageId> send(Producer<Object> producer,
                                             Message<?> message,
                                             PulsarSchemaResolver.ResolvedSchema schema) {
        Objects.requireNonNull(producer);
        OutgoingMapping mapping = outgoingMapping(Objects.requireNonNull(message));
        TypedMessageBuilder<Object> builder = producer.newMessage()
                .value(schema.snapshot(message.entity()))
                .properties(mapping.properties());
        PulsarMessage<?> pulsarMessage = mapping.pulsarMessage();
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
            return new OutgoingMapping(copyHeaders(message.headers()),
                                       message instanceof PulsarMessage<?> pulsarMessage ? pulsarMessage : null);
        }
        Message<?> original = deadLetterMessage.originalMessage();
        PulsarMessage<?> pulsarMessage = original instanceof PulsarMessage<?> actual ? actual : null;
        Map<String, String> properties = new LinkedHashMap<>();
        copyHeaders(original.headers()).forEach((name, value) -> {
            if (!RESERVED_HEADERS.contains(name)) {
                properties.put(name, value);
            }
        });
        copyHeaders(deadLetterMessage.headers()).forEach((name, value) -> {
            if (!RESERVED_HEADERS.contains(name)) {
                properties.put(name, value);
            }
        });
        properties.put(DeadLetterMessage.SOURCE_CHANNEL_HEADER, deadLetterMessage.sourceChannel());
        properties.put(DeadLetterMessage.ATTEMPTS_HEADER, String.valueOf(deadLetterMessage.attempts()));
        properties.put(DeadLetterMessage.FAILURE_TYPE_HEADER, deadLetterMessage.failureType());
        properties.put(DeadLetterMessage.FAILURE_MESSAGE_HEADER, deadLetterMessage.failureMessage());
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
        return new OutgoingMapping(Map.copyOf(properties), pulsarMessage);
    }

    private static Map<String, String> copyHeaders(Map<String, String> headers) {
        Map<String, String> result = new LinkedHashMap<>();
        Objects.requireNonNull(headers).forEach((name, value) -> result.put(Objects.requireNonNull(name),
                                                                           Objects.requireNonNull(value)));
        return Map.copyOf(result);
    }

    private record OutgoingMapping(Map<String, String> properties, PulsarMessage<?> pulsarMessage) {
        private OutgoingMapping {
            Objects.requireNonNull(properties);
        }
    }
}
