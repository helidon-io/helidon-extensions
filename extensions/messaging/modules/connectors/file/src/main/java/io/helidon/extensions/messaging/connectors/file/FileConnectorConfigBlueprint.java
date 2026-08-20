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

import java.nio.file.Path;

import io.helidon.builder.api.Option;
import io.helidon.builder.api.Prototype;
import io.helidon.messaging.ConnectorConfig;

/**
 * File connector configuration.
 */
@Prototype.Blueprint(decorator = FileConnectorConfigSupport.BuilderDecorator.class)
@Prototype.Configured
@Prototype.CustomMethods(FileConnectorConfigSupport.class)
interface FileConnectorConfigBlueprint extends ConnectorConfig {
    /**
     * Target file path.
     *
     * @return target file path
     */
    @Option.Required
    @Option.Configured(FileConnectorConfigSupport.PATH_PROPERTY)
    Path path();

    /**
     * Line separator appended after each message.
     *
     * @return line separator appended after each message
     */
    @Option.Configured(FileConnectorConfigSupport.LINE_SEPARATOR_PROPERTY)
    @Option.DefaultCode("\"\\n\"")
    String lineSeparator();

    /**
     * Maximum UTF-8 payload bytes accepted for one incoming line. The configured line separator is not included. This
     * value must not exceed {@link #maxBatchBytes()}.
     *
     * @return maximum incoming line payload size in bytes
     */
    @Option.Configured(FileConnectorConfigSupport.MAX_LINE_BYTES_PROPERTY)
    @Option.DefaultCode("FileConnectorConfigSupport.DEFAULT_MAX_LINE_BYTES")
    int maxLineBytes();

    /**
     * Maximum total UTF-8 payload bytes retained in one incoming delivery batch. Configured line separators are not
     * included. This value must be at least {@link #maxLineBytes()}.
     *
     * @return maximum incoming batch payload size in bytes
     */
    @Option.Configured(FileConnectorConfigSupport.MAX_BATCH_BYTES_PROPERTY)
    @Option.DefaultCode("FileConnectorConfigSupport.DEFAULT_MAX_BATCH_BYTES")
    long maxBatchBytes();
}
