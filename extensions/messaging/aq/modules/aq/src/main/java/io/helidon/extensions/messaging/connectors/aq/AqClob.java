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
 * Detached SQL CLOB or NCLOB attribute in an {@link AqAdt} object or array.
 * Unlike a plain string, this value preserves its SQL LOB type when forwarded.
 * The content is immutable and holds no JDBC resources.
 */
@Api.Incubating
public final class AqClob {
    private final String text;
    private final boolean national;

    private AqClob(String text, boolean national) {
        this.text = Objects.requireNonNull(text, "CLOB text");
        this.national = national;
    }

    /**
     * Create a detached SQL CLOB using the database character set.
     *
     * @param text non-null character content
     * @return detached SQL CLOB
     * @throws NullPointerException if the content is null
     */
    public static AqClob create(String text) {
        return new AqClob(text, false);
    }

    /**
     * Create a detached SQL NCLOB using the database national character set.
     *
     * @param text non-null character content
     * @return detached SQL NCLOB
     * @throws NullPointerException if the content is null
     */
    public static AqClob createNational(String text) {
        return new AqClob(text, true);
    }

    /**
     * Character content.
     *
     * @return non-null text
     */
    public String text() {
        return text;
    }

    /**
     * Whether this value represents an NCLOB, using the database national character set.
     *
     * @return true for NCLOB, false for CLOB
     */
    public boolean national() {
        return national;
    }

    @Override
    public String toString() {
        return "AqClob{text=" + text + ",national=" + national + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof AqClob other)) {
            return false;
        }
        return Objects.equals(text, other.text()) && national == other.national();
    }

    @Override
    public int hashCode() {
        return Objects.hash(text, national);
    }
}
