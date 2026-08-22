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

import io.helidon.builder.api.Prototype;

/**
 * Support methods and constants for {@link FileConnectorConfig}.
 */
final class FileConnectorConfigSupport {
    /**
     * Config property for the output path.
     */
    @Prototype.Constant
    static final String PATH_PROPERTY = "path";

    /**
     * Config property for the line separator.
     */
    @Prototype.Constant
    static final String LINE_SEPARATOR_PROPERTY = "line-separator";

    /**
     * Config property for the maximum incoming line payload size.
     */
    @Prototype.Constant
    static final String MAX_LINE_BYTES_PROPERTY = "max-line-bytes";

    /**
     * Config property for the maximum incoming delivery-batch payload size.
     */
    @Prototype.Constant
    static final String MAX_BATCH_BYTES_PROPERTY = "max-batch-bytes";

    /**
     * Default line separator.
     */
    @Prototype.Constant
    static final String DEFAULT_LINE_SEPARATOR = "\n";

    /**
     * Default maximum incoming line payload size, one MiB.
     */
    @Prototype.Constant
    static final int DEFAULT_MAX_LINE_BYTES = 1_048_576;

    /**
     * Default maximum incoming delivery-batch payload size, 64 MiB.
     */
    @Prototype.Constant
    static final long DEFAULT_MAX_BATCH_BYTES = 67_108_864L;

    private FileConnectorConfigSupport() {
    }

    /**
     * Validates file connector configuration.
     */
    static final class BuilderDecorator implements Prototype.BuilderDecorator<FileConnectorConfig.BuilderBase<?, ?>> {
        @Override
        public void decorate(FileConnectorConfig.BuilderBase<?, ?> target) {
            if (target.lineSeparator().isEmpty()) {
                throw new IllegalArgumentException("line-separator must not be empty");
            }
            if (target.maxLineBytes() <= 0) {
                throw new IllegalArgumentException("max-line-bytes must be greater than zero");
            }
            if (target.maxBatchBytes() <= 0) {
                throw new IllegalArgumentException("max-batch-bytes must be greater than zero");
            }
            if (target.maxLineBytes() > target.maxBatchBytes()) {
                throw new IllegalArgumentException("max-line-bytes must not exceed max-batch-bytes");
            }
        }
    }
}
