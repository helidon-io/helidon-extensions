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

import io.helidon.common.Api;

/**
 * Converts application values to and from the connector's detached payload types.
 * The connector invokes this converter while it owns transport resources.
 * Implementations must return non-null values independent of live database resources.
 * Incoming payloads are {@link AqAdt}, {@link AqXml}, {@link java.lang.String}, byte arrays, maps,
 * or {@link java.io.Serializable} values from object messages when {@code allow-object-messages} is enabled.
 * Application maps may contain domain-specific keys and values; native MapMessage restrictions apply to
 * maps read from or written to the transport. Message envelopes copy the map structure and byte-array
 * values, while retaining keys and other values, which must remain stable.
 */
@Api.Incubating
public interface AqPayloadConverter {
    /**
     * Convert an incoming detached native payload to an application value.
     * Native message decoding and object-message opt-in checks occur before this method is invoked.
     *
     * @param payload detached payload
     * @return stable application value
     */
    Object fromPayload(Object payload);

    /**
     * Convert an application value to a supported detached native payload.
     * For {@link AqPayloadType#JMS}, return a {@link java.lang.String}, byte array,
     * {@link java.nio.ByteBuffer}, map with supported JMS map keys and values, or another
     * {@link java.io.Serializable} value when {@code allow-object-messages} is enabled.
     * Strings, byte arrays, buffers and maps select their corresponding JMS body types before
     * the serializable-object fallback. A buffer contributes its remaining bytes without changing its position.
     * For {@link AqPayloadType#ADT}, return an {@link AqAdt} with kind {@link AqAdt.Kind#STRUCT}.
     * For {@link AqPayloadType#XML}, return an {@link AqXml} or XML text as a {@link java.lang.String}.
     *
     * @param entity application value
     * @return native payload value
     */
    Object toPayload(Object entity);
}
