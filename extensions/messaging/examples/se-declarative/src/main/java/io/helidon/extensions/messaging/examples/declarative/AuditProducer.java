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

import io.helidon.extensions.messaging.Emitter;
import io.helidon.extensions.messaging.Message;
import io.helidon.extensions.messaging.Messaging;
import io.helidon.extensions.messaging.MessagingRuntime;
import io.helidon.service.registry.Service;

/**
 * Audit producer using a generated named emitter.
 */
@Service.Singleton
@Service.RunLevel(MessagingRuntime.RUN_LEVEL + 1)
class AuditProducer {
    @Service.Inject
    @Service.Named(Main.AUDIT_CHANNEL)
    Emitter<String> audit;

    @Service.PostConstruct
    void publishSampleEvents() {
        audit.emitBatch(List.of(auditEvent("user-created", "example"),
                                auditEvent("invoice-paid", "example")));
    }

    void audit(String event, String actor) {
        audit.emitMessage(auditEvent(event, actor));
    }

    private Message<String> auditEvent(String event, String actor) {
        return Messaging.message(event)
                .header("actor", actor)
                .build();
    }
}
