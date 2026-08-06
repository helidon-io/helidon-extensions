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

package io.helidon.extensions.messaging.examples.declarative;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.helidon.extensions.messaging.Message;
import io.helidon.extensions.messaging.MessageBatch;
import io.helidon.extensions.messaging.Messaging;
import io.helidon.service.registry.Service;

/**
 * Local audit consumer.
 */
@Service.Singleton
class AuditConsumer {
    private final List<Message<String>> messages = new CopyOnWriteArrayList<>();

    @Messaging.OnMessage(Main.AUDIT_CHANNEL)
    void consume(MessageBatch<String> batch) {
        messages.addAll(batch.messages());
    }

    List<Message<String>> messages() {
        return messages;
    }
}
