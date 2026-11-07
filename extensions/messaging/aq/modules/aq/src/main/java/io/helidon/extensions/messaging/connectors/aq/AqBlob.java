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

package io.helidon.extensions.messaging.connectors.aq;

import java.util.Objects;

import io.helidon.common.Api;

/**
 * Detached SQL BLOB attribute in an {@link AqAdt} object or array.
 * Unlike a plain byte array, this value preserves the SQL BLOB type when forwarded.
 * The content is defensively copied and holds no JDBC resources.
 */
@Api.Incubating
public final class AqBlob {
    private final byte[] bytes;

    private AqBlob(byte[] bytes) {
        this.bytes = Objects.requireNonNull(bytes, "BLOB bytes").clone();
    }

    /**
     * Create a detached SQL BLOB by copying its binary content.
     *
     * @param bytes non-null binary content
     * @return detached SQL BLOB
     * @throws NullPointerException if the content is null
     */
    public static AqBlob create(byte[] bytes) {
        return new AqBlob(bytes);
    }

    /**
     * BLOB content snapshot.
     *
     * @return content copy
     */
    public byte[] bytes() {
        return bytes.clone();
    }
}
