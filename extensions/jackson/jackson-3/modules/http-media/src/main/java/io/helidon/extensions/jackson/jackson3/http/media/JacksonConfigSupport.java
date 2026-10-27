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

package io.helidon.extensions.jackson.jackson3.http.media;

import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import io.helidon.builder.api.Prototype;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.core.json.JsonWriteFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

final class JacksonConfigSupport {
    private JacksonConfigSupport() {
    }

    static class Decorator implements Prototype.BuilderDecorator<JacksonSupportConfig.BuilderBase<?, ?>> {

        @Override
        public void decorate(JacksonSupportConfig.BuilderBase<?, ?> target) {
            if (target.objectMapper().isEmpty()) {
                JsonMapper.Builder builder = JsonMapper.builderWithJackson2Defaults()
                        .enable(MapperFeature.DETECT_PARAMETER_NAMES)
                        .enable(MapperFeature.DEFAULT_VIEW_INCLUSION)
                        .enable(StreamReadFeature.USE_FAST_DOUBLE_PARSER)
                        .enable(StreamReadFeature.USE_FAST_BIG_NUMBER_PARSER);
                configureJsonMapper(builder, target.properties());
                target.objectMapper(builder.build());
            }
        }

        private void configureJsonMapper(JsonMapper.Builder jsonMapper, Map<String, Boolean> properties) {
            configure(StreamReadFeature.values(), properties, jsonMapper::configure);
            configure(StreamWriteFeature.values(), properties, jsonMapper::configure);
            configure(DeserializationFeature.values(), properties, jsonMapper::configure);
            configure(SerializationFeature.values(), properties, jsonMapper::configure);
            configure(JsonNodeFeature.values(), properties, jsonMapper::configure);
            configure(JsonReadFeature.values(), properties, jsonMapper::configure);
            configure(MapperFeature.values(), properties, jsonMapper::configure);
            configure(JsonWriteFeature.values(), properties, jsonMapper::configure);
            configure(EnumFeature.values(), properties, jsonMapper::configure);
            configure(DateTimeFeature.values(), properties, jsonMapper::configure);
        }

        private static <T extends Enum<?>> void configure(T[] values,
                                                          Map<String, Boolean> properties,
                                                          BiConsumer<T, Boolean> consumer) {
            Stream.of(values)
                    .forEach(enumValue -> Optional.ofNullable(properties.get(enumValue.name()))
                            .ifPresent(value -> consumer.accept(enumValue, value)));
        }

    }
}
