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

package io.helidon.extensions.messaging.connectors.aq;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.helidon.builder.api.Option;
import io.helidon.builder.api.Prototype;
import io.helidon.messaging.MessageHeaderValue;

/**
 * Outgoing AQ message construction options.
 *
 * @param <T> entity type
 */
@Prototype.Blueprint(decorator = AqMessageSupport.BuilderDecorator.class)
@Prototype.Sealed
@Prototype.CustomMethods(AqMessageSupport.class)
interface AqMessageConfigBlueprint<T> extends Prototype.Factory<AqMessage<T>> {
    /**
     * Non-null entity. Use the Map interface as the type of map entities.
     *
     * @return entity
     */
    @Option.Confidential
    T entity();

    /**
     * Metadata local to this message envelope, initially empty.
     * Setting this map replaces all entries; adding a value replaces the value with the same exact name.
     * The AQ connector interprets {@link io.helidon.extensions.messaging.connectors.aq.AqMessage#DELIVERY_DELAY_METADATA}
     * as a delivery option. Other local metadata is not copied into native message properties.
     *
     * @return local metadata
     */
    @Option.Confidential
    @Option.Singular(value = "localMetadata", withPrefix = false)
    Map<String, MessageHeaderValue> localMetadata();

    /**
     * Correlation identifier.
     *
     * @return identifier
     */
    Optional<String> correlationId();

    /**
     * JMS message type, supported on JMS payloads.
     *
     * @return type
     */
    Optional<String> type();

    /**
     * Reply-to queue or topic name.
     *
     * @return destination name
     */
    Optional<String> replyTo();

    /**
     * Native AQ reply-to agent. Mutually exclusive with a queue or topic reply-to name.
     *
     * @return reply-to agent
     */
    Optional<AqRecipient> replyToAgent();

    /**
     * Reply-to destination kind.
     *
     * @return destination kind
     */
    @Option.Default("QUEUE")
    AqDestinationType replyToType();

    /**
     * Portable JMS application properties.
     *
     * @return properties
     */
    @Option.Singular("property")
    Map<String, Object> properties();

    /**
     * Explicit AQ recipients; an empty list uses configured subscribers.
     *
     * @return recipients
     */
    @Option.Singular("recipient")
    List<AqRecipient> recipients();
}
