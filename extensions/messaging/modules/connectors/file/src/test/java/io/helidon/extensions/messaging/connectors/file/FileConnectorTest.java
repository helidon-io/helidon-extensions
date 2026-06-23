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
import java.util.Map;

import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.extensions.messaging.ConnectorConfig;
import io.helidon.extensions.messaging.Message;
import io.helidon.extensions.messaging.MessagingChannel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileConnectorTest {
    @Test
    void testConnectorName() {
        assertThat(new FileOutgoingConnector().connectorName(), is("file"));
        assertThat(new FileIncomingConnector().connectorName(), is("file"));
    }

    @Test
    void testMissingPathFails() {
        assertThrows(RuntimeException.class,
                     () -> FileConnectorConfig.builder()
                             .direction(ConnectorConfig.Direction.OUTGOING)
                             .channel("audit")
                             .connector(FileOutgoingConnector.CONNECTOR)
                             .build());
    }

    @Test
    void testCreateFromConfig(@TempDir Path tempDir) {
        Path auditLog = tempDir.resolve("audit.log");
        FileConnectorConfig config = FileConnectorConfig.create(Config.just(ConfigSources.create(Map.of(
                "direction", "OUTGOING",
                ConnectorConfig.CHANNEL_NAME_ATTRIBUTE, "audit",
                ConnectorConfig.CONNECTOR_ATTRIBUTE, FileOutgoingConnector.CONNECTOR,
                FileConnectorConfig.PATH_PROPERTY, auditLog.toString(),
                FileConnectorConfig.LINE_SEPARATOR_PROPERTY, "|"))));

        assertThat(config.direction(), is(ConnectorConfig.Direction.OUTGOING));
        assertThat(config.channel(), is("audit"));
        assertThat(config.connector(), is(FileOutgoingConnector.CONNECTOR));
        assertThat(config.path(), is(auditLog));
        assertThat(config.lineSeparator(), is("|"));
    }

    @Test
    void testDefaultLineSeparatorWritesOneMessagePerLine(@TempDir Path tempDir) throws Exception {
        Path auditLog = tempDir.resolve("audit.log");
        var sink = new FileOutgoingConnector().createSink(config(auditLog));

        sink.send(Message.create("first audit event"));
        sink.send(Message.create("second audit event"));

        assertThat(Files.readString(auditLog), is("first audit event\nsecond audit event\n"));
    }

    @Test
    void testCustomLineSeparator(@TempDir Path tempDir) throws Exception {
        Path auditLog = tempDir.resolve("audit.log");
        var sink = new FileOutgoingConnector().createSink(config(auditLog, "|"));

        sink.send(Message.create("first audit event"));
        sink.send(Message.create("second audit event"));

        assertThat(Files.readString(auditLog), is("first audit event|second audit event|"));
    }

    @Test
    void testParentDirectoriesAreCreated(@TempDir Path tempDir) throws Exception {
        Path auditLog = tempDir.resolve("logs").resolve("audit.log");

        new FileOutgoingConnector().createSink(config(auditLog))
                .send(Message.create("audit event"));

        assertThat(Files.readString(auditLog), is("audit event\n"));
    }

    @Test
    void testFileConnectorCanBeUsedAsChannelOutput(@TempDir Path tempDir) throws IOException {
        Path auditLog = tempDir.resolve("audit.log");

        MessagingChannel<String> channel = MessagingChannel.<String>builder()
                .payloadType(String.class)
                .addOutgoingConnector(new FileOutgoingConnector().createSink(config(auditLog, "|")))
                .build();

        channel.emit("first audit event");
        channel.emit(Message.builder("second audit event")
                             .header("key", "value")
                             .build());

        assertThat(Files.readString(auditLog), is("first audit event|second audit event|"));
    }

    private static FileConnectorConfig config(Path path) {
        return config(path, FileConnectorConfig.DEFAULT_LINE_SEPARATOR);
    }

    private static FileConnectorConfig config(Path path, String lineSeparator) {
        return FileConnectorConfig.builder()
                .direction(ConnectorConfig.Direction.OUTGOING)
                .channel("audit")
                .connector(FileOutgoingConnector.CONNECTOR)
                .path(path)
                .lineSeparator(lineSeparator)
                .build();
    }
}
