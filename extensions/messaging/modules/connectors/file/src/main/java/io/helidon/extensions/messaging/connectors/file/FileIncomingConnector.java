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
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.helidon.extensions.messaging.ConnectorSource;
import io.helidon.extensions.messaging.ConnectorSourceContext;
import io.helidon.extensions.messaging.IncomingConnector;
import io.helidon.extensions.messaging.MessagingException;
import io.helidon.service.registry.Service;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

/**
 * File incoming connector.
 */
@Service.Singleton
public class FileIncomingConnector implements IncomingConnector<FileConnectorConfig> {
    private final AtomicBoolean closed = new AtomicBoolean();

    @Override
    public String connectorName() {
        return FileOutgoingConnector.CONNECTOR;
    }

    @Override
    public ConnectorSource createSource(FileConnectorConfig config, ConnectorSourceContext context) {
        return new FileSource(config, context, closed);
    }

    @Override
    public void close() {
        closed.set(true);
    }

    private record FileSource(FileConnectorConfig config,
                              ConnectorSourceContext context,
                              AtomicBoolean closed) implements ConnectorSource {
        @Override
        public void run() {
            try {
                tailFile();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MessagingException("File incoming connector failed", e);
            } catch (IOException e) {
                throw new MessagingException("File incoming connector failed", e);
            }
        }

        private void tailFile() throws IOException, InterruptedException {
            Path path = config.path();
            Path directory = path.toAbsolutePath().getParent();
            if (directory != null) {
                Files.createDirectories(directory);
            }
            if (!Files.exists(path)) {
                Files.createFile(path);
            }

            int offset = Files.readString(path).length();
            try (WatchService watcher = path.getFileSystem().newWatchService()) {
                directory.register(watcher, ENTRY_CREATE, ENTRY_MODIFY);
                while (!closed.get()) {
                    WatchKey key = watcher.poll(100, TimeUnit.MILLISECONDS);
                    if (key == null) {
                        continue;
                    }
                    offset = emitAppendedLines(path, offset);
                    key.reset();
                }
            }
        }

        private int emitAppendedLines(Path path, int offset) throws IOException {
            String content = Files.readString(path);
            if (content.length() < offset) {
                offset = 0;
            }
            String added = content.substring(offset);
            String lineSeparator = config.lineSeparator();
            int index;
            while ((index = added.indexOf(lineSeparator)) >= 0) {
                context.emit(added.substring(0, index));
                added = added.substring(index + lineSeparator.length());
                offset += index + lineSeparator.length();
            }
            return offset;
        }
    }
}
