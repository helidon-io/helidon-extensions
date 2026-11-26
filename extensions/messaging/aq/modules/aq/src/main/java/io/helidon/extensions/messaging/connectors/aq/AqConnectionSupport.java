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

import io.helidon.builder.api.Prototype;

final class AqConnectionSupport {
    private AqConnectionSupport() {
    }

    static final class BuilderDecorator implements Prototype.BuilderDecorator<AqConnectionConfig.BuilderBase<?, ?>> {
        @Override
        public void decorate(AqConnectionConfig.BuilderBase<?, ?> builder) {
            builder.url().ifPresent(url -> {
                if (url.isBlank()) {
                    throw new IllegalArgumentException("connection.url must not be blank");
                }
            });
            builder.username().ifPresent(username -> {
                if (username.isBlank()) {
                    throw new IllegalArgumentException("connection.username must not be blank");
                }
            });
            if (builder.username().isPresent() != builder.password().isPresent()) {
                throw new IllegalArgumentException("connection.username and connection.password must be configured together");
            }
        }
    }
}
