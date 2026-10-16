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

import java.util.Optional;

import io.helidon.builder.api.Option;
import io.helidon.builder.api.Prototype;

/**
 * Recipient of an AQ message.
 */
@Prototype.Blueprint(decorator = AqRecipientSupport.BuilderDecorator.class)
@Prototype.Sealed
interface AqRecipientBlueprint {
    /**
     * Consumer name. Omit when addressing all subscribers at a remote address.
     *
     * @return recipient name
     */
    Optional<String> name();

    /**
     * Remote queue address, such as {@code SCHEMA.QUEUE@DATABASE_LINK}.
     * Omit for a local consumer.
     *
     * @return recipient address
     */
    Optional<String> address();

    /**
     * AQ agent protocol.
     *
     * @return protocol, zero for Oracle AQ
     */
    @Option.DefaultInt(0)
    int protocol();
}
