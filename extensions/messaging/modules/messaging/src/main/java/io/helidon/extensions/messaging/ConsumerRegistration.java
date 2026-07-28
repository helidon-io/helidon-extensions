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

package io.helidon.extensions.messaging;

import java.util.List;

import io.helidon.common.GenericType;
import io.helidon.service.registry.Service;

/**
 * Generated consumer registration contract.
 */
@Service.Contract
public interface ConsumerRegistration {
    /**
     * Channel name.
     *
     * @return channel name
     */
    String channel();

    /**
     * Expected payload type.
     *
     * @return payload type
     */
    Class<?> payloadType();

    /**
     * Expected payload type including generic arguments.
     * <p>
     * Generated registrations override this method to retain the complete declared payload type. The default keeps
     * manually implemented registrations source-compatible and represents their raw {@link #payloadType()}.
     *
     * @return generic payload type
     */
    default GenericType<?> payloadGenericType() {
        return GenericType.create(payloadType());
    }

    /**
     * Expected message envelope raw type.
     * <p>
     * The default accepts any {@link Message} implementation. Generated registrations override this for consumer
     * parameters that declare a more specific message subtype.
     *
     * @return message envelope raw type
     */
    default Class<?> envelopeType() {
        return Message.class;
    }

    /**
     * Expected message envelope type including generic arguments.
     * <p>
     * Generated registrations override this method to retain the complete declared envelope type. The default keeps
     * manually implemented registrations source-compatible and represents their raw {@link #envelopeType()}.
     *
     * @return generic message envelope type
     */
    default GenericType<?> envelopeGenericType() {
        return GenericType.create(envelopeType());
    }

    /**
     * Whether this registration consumes message batches.
     *
     * @return {@code true} for batch consumers
     */
    default boolean batch() {
        return false;
    }

    /**
     * Dispatch the message to the generated consumer invoker.
     * <p>
     * A successful return means the consumer method completed. Consumer failures must be propagated with their causes
     * preserved.
     *
     * @param message message to dispatch
     * @throws RuntimeException if the consumer fails
     */
    void dispatch(Message<?> message);

    /**
     * Dispatch a batch to the generated consumer invoker.
     * <p>
     * The default implementation dispatches messages sequentially and stops at the first failure.
     *
     * @param messages messages to dispatch
     * @throws RuntimeException if the consumer fails
     */
    default void dispatchBatch(List<Message<?>> messages) {
        for (Message<?> message : messages) {
            dispatch(message);
        }
    }
}
