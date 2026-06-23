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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import io.helidon.extensions.messaging.Message;
import io.helidon.extensions.messaging.connectors.file.FileOutgoingConnector;
import io.helidon.service.registry.ServiceRegistryManager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

class MainTest {
    @Test
    void testExampleWritesAuditFile(@TempDir Path tempDir) throws IOException {
        Path auditLog = tempDir.resolve("audit.log");
        configureFileConnector(auditLog);
        ServiceRegistryManager manager = ServiceRegistryManager.start(ApplicationBinding.create());

        try {
            List<Message<String>> consumed = manager.registry().get(AuditConsumer.class).messages();

            assertThat(Files.readString(auditLog), is("user-created\ninvoice-paid\n"));
            assertThat(consumed.stream().map(Message::entity).toList(), is(List.of("user-created", "invoice-paid")));
            assertThat(consumed.getFirst().header("actor").orElseThrow(), is("example"));
        } finally {
            manager.shutdown();
            clearFileConnectorConfig();
        }
    }

    private static void configureFileConnector(Path auditLog) {
        System.setProperty("helidon.messaging.outgoing." + Main.AUDIT_CHANNEL + ".connector",
                           FileOutgoingConnector.CONNECTOR);
        System.setProperty("helidon.messaging.outgoing." + Main.AUDIT_CHANNEL + ".path",
                           auditLog.toString());
        System.setProperty("helidon.messaging.connector." + FileOutgoingConnector.CONNECTOR + ".line-separator",
                           "\n");
    }

    private static void clearFileConnectorConfig() {
        System.clearProperty("helidon.messaging.outgoing." + Main.AUDIT_CHANNEL + ".connector");
        System.clearProperty("helidon.messaging.outgoing." + Main.AUDIT_CHANNEL + ".path");
        System.clearProperty("helidon.messaging.connector." + FileOutgoingConnector.CONNECTOR + ".line-separator");
    }
}
