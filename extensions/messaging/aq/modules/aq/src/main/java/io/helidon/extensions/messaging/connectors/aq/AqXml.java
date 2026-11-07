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
 * Detached XML payload for an AQ queue whose payload type is {@code SYS.XMLTYPE}.
 */
@Api.Incubating
public final class AqXml {
    private final String text;

    private AqXml(String text) {
        this.text = Objects.requireNonNull(text, "XML text");
    }

    /**
     * Create a detached XML payload without parsing its text.
     *
     * @param text non-null XML document text
     * @return detached XML payload
     * @throws NullPointerException if the text is null
     */
    public static AqXml create(String text) {
        return new AqXml(text);
    }

    /**
     * XML document text.
     *
     * @return non-null XML
     */
    public String text() {
        return text;
    }

    @Override
    public String toString() {
        return "AqXml{text=" + text + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof AqXml other)) {
            return false;
        }
        return Objects.equals(text, other.text());
    }

    @Override
    public int hashCode() {
        return Objects.hash(text);
    }
}
