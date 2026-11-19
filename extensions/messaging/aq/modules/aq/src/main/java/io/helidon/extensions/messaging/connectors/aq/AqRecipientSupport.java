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

import java.util.Optional;

import io.helidon.builder.api.Prototype;

final class AqRecipientSupport {
    private AqRecipientSupport() {
    }

    private static void requireName(String option, Optional<String> value) {
        if (value.isPresent() && value.orElseThrow().isBlank()) {
            throw new IllegalArgumentException("AQ recipient " + option + " must not be blank");
        }
    }

    static final class BuilderDecorator implements Prototype.BuilderDecorator<AqRecipient.BuilderBase<?, ?>> {
        @Override
        public void decorate(AqRecipient.BuilderBase<?, ?> target) {
            requireName("name", target.name());
            requireName("address", target.address());
            if (target.name().isEmpty() && target.address().isEmpty()) {
                throw new IllegalArgumentException("AQ recipient requires a name or address");
            }
            if (target.protocol() < 0) {
                throw new IllegalArgumentException("AQ recipient protocol must not be negative");
            }
        }
    }
}
