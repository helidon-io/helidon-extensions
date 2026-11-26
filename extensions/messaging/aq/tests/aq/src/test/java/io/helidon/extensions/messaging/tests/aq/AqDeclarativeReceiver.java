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

package io.helidon.extensions.messaging.tests.aq;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import io.helidon.extensions.messaging.connectors.aq.AqMessage;
import io.helidon.messaging.Emitter;
import io.helidon.messaging.Messaging;
import io.helidon.service.registry.Service;

@Service.Singleton
class AqDeclarativeReceiver {
    private final BlockingQueue<AqMessage<String>> messages = new LinkedBlockingQueue<>();
    private final Emitter<String> emitter;
    private final DataSource dataSource;

    @Service.Inject
    AqDeclarativeReceiver(@Service.Named("outgoing") Emitter<String> emitter, DataSource dataSource) {
        this.emitter = emitter;
        this.dataSource = dataSource;
    }

    @Messaging.ReceiveFrom("incoming")
    void receive(AqMessage<String> message) {
        messages.add(message);
    }

    void emit(String entity) {
        emitter.emit(entity);
    }

    DataSource dataSource() {
        return dataSource;
    }

    AqMessage<String> await() throws InterruptedException {
        return messages.poll(20, TimeUnit.SECONDS);
    }
}
