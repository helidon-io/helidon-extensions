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

import io.helidon.builder.api.Prototype;

final class AqBlobSupport {
    private AqBlobSupport() {
    }

    @Prototype.RuntimeTypeFactoryMethod("blob")
    static AqBlob create(AqBlobConfig config) {
        return AqBlob.create(config);
    }

    static final class BytesDecorator implements Prototype.OptionDecorator<AqBlobConfig.BuilderBase<?, ?>, byte[]> {
        @Override
        public void decorate(AqBlobConfig.BuilderBase<?, ?> builder, byte[] value) {
            Objects.requireNonNull(value, "BLOB bytes");
        }
    }
}
