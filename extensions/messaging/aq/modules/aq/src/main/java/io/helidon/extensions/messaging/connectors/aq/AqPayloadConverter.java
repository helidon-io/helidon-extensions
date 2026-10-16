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

/**
 * Converts application values to and from the connector's detached payload types.
 * The connector invokes this converter while it owns transport resources.
 * Implementations must return non-null values independent of live database resources.
 * Native payloads are {@link AqAdt}, {@link AqXml}, text, byte arrays, or maps.
 */
public interface AqPayloadConverter {
    /**
     * Convert an incoming detached native payload to an application value.
     *
     * @param payload detached payload
     * @return stable application value
     */
    Object fromPayload(Object payload);

    /**
     * Convert an application value to a supported detached native payload.
     *
     * @param entity application value
     * @return native payload value
     */
    Object toPayload(Object entity);
}
