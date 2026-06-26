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

package io.helidon.extensions.messaging.connectors.file;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import io.helidon.extensions.messaging.ConnectorSink;
import io.helidon.extensions.messaging.Message;
import io.helidon.extensions.messaging.MessagingException;
import io.helidon.extensions.messaging.OutgoingConnector;
import io.helidon.service.registry.Service;

/**
 * File outgoing connector.
 */
@Service.Singleton
public class FileOutgoingConnector implements OutgoingConnector<FileConnectorConfig> {
    /**
     * Connector name used in messaging configuration.
     */
    public static final String CONNECTOR = "file";

    @Override
    public String connectorName() {
        return CONNECTOR;
    }

    @Override
    public ConnectorSink createSink(FileConnectorConfig config) {
        return new FileSink(config);
    }

    private record FileSink(FileConnectorConfig config) implements ConnectorSink {
        @Override
        public <T> void send(Message<T> message) {
            write(String.valueOf(message.entity()) + config.lineSeparator());
        }

        @Override
        public <T> void sendBatch(List<Message<T>> messages) {
            if (messages.isEmpty()) {
                return;
            }
            StringBuilder content = new StringBuilder();
            for (Message<T> message : messages) {
                content.append(message.entity())
                        .append(config.lineSeparator());
            }
            write(content.toString());
        }

        private void write(String content) {
            Path parent = config.path().getParent();
            try {
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(config.path(),
                                  content,
                                  StandardOpenOption.CREATE,
                                  StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new MessagingException("File outgoing connector failed", e);
            }
        }
    }
}
