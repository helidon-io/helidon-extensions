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

final class AqEnqueueTransformationSupport {
    private AqEnqueueTransformationSupport() {
    }

    static final class BuilderDecorator
            implements Prototype.BuilderDecorator<AqEnqueueTransformationConfig.BuilderBase<?, ?>> {
        @Override
        public void decorate(AqEnqueueTransformationConfig.BuilderBase<?, ?> builder) {
            builder.name().ifPresent(name -> {
                if (name.isBlank()) {
                    throw new IllegalArgumentException("enqueue-transformation.name must not be blank");
                }
            });
            if (builder.enabled() && builder.name().isEmpty()) {
                throw new IllegalArgumentException("enqueue-transformation.name is required when enabled");
            }
        }
    }
}
