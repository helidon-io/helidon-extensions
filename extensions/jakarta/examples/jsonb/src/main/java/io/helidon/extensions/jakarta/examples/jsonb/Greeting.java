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

package io.helidon.extensions.jakarta.examples.jsonb;

/**
 * Mutable JSON-B example entity.
 */
public class Greeting {
    private String message;

    /**
     * Creates an entity for JSON-B.
     */
    public Greeting() {
    }

    /**
     * Creates an entity with a message.
     *
     * @param message message
     */
    public Greeting(String message) {
        this.message = message;
    }

    /**
     * Returns the message.
     *
     * @return message
     */
    public String getMessage() {
        return message;
    }

    /**
     * Sets the message.
     *
     * @param message message
     */
    public void setMessage(String message) {
        this.message = message;
    }
}
