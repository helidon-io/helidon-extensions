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
package io.helidon.extensions.chaos;

import io.helidon.builder.api.Prototype;

/**
 * Cross-property validation for {@link ChaosConfig}.
 */
final class ChaosConfigSupport {

    private ChaosConfigSupport() {
    }

    static final class BuilderDecorator implements Prototype.BuilderDecorator<ChaosConfig.BuilderBase<?, ?>> {

        @Override
        public void decorate(ChaosConfig.BuilderBase<?, ?> builder) {
            requireText(builder.name(), "Chaos feature name must not be blank");
            builder.controlSocket().ifPresent(socket -> requireText(socket, "Control socket name must not be blank"));
            builder.applicationSockets()
                    .forEach(socket -> requireText(socket, "Application socket name must not be blank"));

            if (!builder.enabled()) {
                return;
            }
            String controlSocket = builder.controlSocket()
                    .orElseThrow(() -> new IllegalArgumentException("Enabled Chaos requires control-socket"));
            if (builder.applicationSockets().isEmpty()) {
                throw new IllegalArgumentException("Enabled Chaos requires at least one application-socket");
            }
            if (builder.applicationSockets().contains(controlSocket)) {
                throw new IllegalArgumentException("Control socket must not be an application socket");
            }
        }

        private static void requireText(String value, String message) {
            if (value.isBlank()) {
                throw new IllegalArgumentException(message);
            }
        }
    }
}
