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

package io.helidon.extensions.messaging.tests.poc;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.helidon.extensions.messaging.Emitter;
import io.helidon.extensions.messaging.Message;
import io.helidon.extensions.messaging.Messaging;
import io.helidon.service.registry.Service;

class ChannelMessagingTypes {
    static final String CHANNEL_ONE = "channel-one";
    static final String CHANNEL_TWO = "channel-two";

    private ChannelMessagingTypes() {
    }

    @Service.Singleton
    static class Producer {
        @Service.Named(CHANNEL_ONE)
        @Service.Inject
        Emitter<String> channelOne;

        @Service.Named(CHANNEL_TWO)
        @Service.Inject
        Emitter<String> channelTwo;

        void emitChannelOne(String entity) {
            channelOne.emit(Messaging.message(entity)
                                    .header("key", "value")
                                    .build());
        }

        void emitChannelTwo(String entity) {
            channelTwo.emit(entity);
        }

        boolean emittersInjected() {
            return channelOne != null && channelTwo != null;
        }
    }

    @Service.Singleton
    static class FirstChannelOneConsumer extends MessageConsumer {
        private final List<String> keys = new CopyOnWriteArrayList<>();

        @Messaging.OnMessage(CHANNEL_ONE)
        void consume(@Messaging.HeaderParam("key") String key,
                     @Messaging.Entity String payload,
                     Message<String> message) {
            keys.add(key);
            messages().add(Message.builder(payload)
                                   .header("key", message.header("key").orElseThrow())
                                   .build());
        }

        List<String> keys() {
            return keys;
        }
    }

    @Service.Singleton
    static class SecondChannelOneConsumer extends MessageConsumer {
        @Messaging.OnMessage(CHANNEL_ONE)
        void consume(Message<String> message) {
            messages().add(message);
        }
    }

    @Service.Singleton
    static class ChannelTwoConsumer extends MessageConsumer {
        @Messaging.OnMessage(CHANNEL_TWO)
        void consume(String payload) {
            messages().add(Message.builder(payload).build());
        }
    }

    abstract static class MessageConsumer {
        private final List<Message<String>> messages = new CopyOnWriteArrayList<>();

        List<Message<String>> messages() {
            return messages;
        }
    }
}
