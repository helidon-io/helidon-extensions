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

package io.helidon.example.model;

import java.util.stream.IntStream;

import io.helidon.common.GenericType;
import io.helidon.common.mapper.Mapper;
import io.helidon.json.binding.JsonBinding;
import io.helidon.json.binding.JsonBindingFactory;
import io.helidon.service.registry.ServiceRegistryManager;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EnumServicesTest {
    private final JsonBinding json = JsonBinding.create();

    @Test
    void companionFrameworkTypesDoNotShadowModelNames() {
        assertThat(json.deserialize("\"MAPPED\"", io.helidon.example.model.Mapper.class),
                   is(io.helidon.example.model.Mapper.MAPPED));
        assertThat(json.serialize(io.helidon.example.model.Mapper.MAPPED), is("\"mapped\""));
        assertThat(json.deserialize("\"SELECTED\"", Set.class), is(Set.SELECTED));
        assertThat(json.serialize(Set.SELECTED), is("\"selected\""));
    }

    @Test
    void jsonNormalizesInputButPreservesDeclaredWireValues() {
        Envelope envelope = json.deserialize("{\"mode\":\"AUTHZ\",\"state\":\"ON-HOLD\"}", Envelope.class);
        assertThat(envelope.mode(), is(Mode.AUTH_Z));
        assertThat(envelope.state(), is(Envelope.StateEnum.ON_HOLD));
        assertThat(json.serialize(envelope), containsString("\"mode\":\"authZ\""));
        assertThat(json.serialize(envelope), containsString("\"state\":\"on-hold\""));
        assertThat(json.deserialize("null", Mode.class), nullValue());
        assertThat(json.serialize((Mode) null, Mode.class), is("null"));
        assertThrows(IllegalArgumentException.class, () -> Mode.fromValue("unknown"));
        assertThrows(NullPointerException.class, () -> Mode.fromValue(null));
    }

    @Test
    void discoversModelOnlyEnumMappersAndUnionFactory() {
        var manager = ServiceRegistryManager.create();
        try {
            var registry = manager.registry();
            for (Class<?> type : new Class<?>[] {Mode.class, Envelope.StateEnum.class,
                    io.helidon.example.model.Mapper.class, Set.class}) {
                var mappers = registry.all(Mapper.class).stream()
                        .filter(mapper -> mapper.sourceType().equals(GenericType.create(String.class)))
                        .filter(mapper -> mapper.targetType().equals(GenericType.create(type)))
                        .toList();
                assertThat(mappers.size(), is(1));
                assertThat(mappers.getFirst().getClass().getEnclosingClass() != null, is(true));
                if (type == io.helidon.example.model.Mapper.class) {
                    assertThat(mappers.getFirst().map("MAPPED"), is(io.helidon.example.model.Mapper.MAPPED));
                }
            }
            for (Class<?> type : new Class<?>[] {Selection.class, Base.class}) {
                var factories = registry.all(JsonBindingFactory.class).stream()
                        .filter(factory -> factory.supportedTypes().contains(type)).toList();
                assertThat(type.getName(), factories.size(), is(1));
                assertThat(factories.getFirst().getClass().getEnclosingClass() != null, is(true));
            }
            assertThat(registry.get(JsonBinding.class).deserialize("{\"code\":\"xaX\"}", Selection.class),
                       instanceOf(Alpha.class));
            assertThat(registry.get(JsonBinding.class).deserialize("{\"kind\":\"child.special\",\"name\":\"example\"}", Base.class),
                       instanceOf(Child.class));
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void unionSelectionUsesTheSameEnumComparisonAsTheMember() {
        assertThat(json.deserialize("{\"value\":\"SMALL\"}", Small.class).value(), is(Small.ValueEnum.SMALL));
        assertThat(json.deserialize("{\"value\":\"SMALL\"}", EnumSelection.class), instanceOf(Small.class));
        assertThat(json.deserialize("{\"value\":\"LARGE\"}", EnumSelection.class), instanceOf(Large.class));
    }

    @Test
    void unionPatternsRemainUnanchoredAndSafeForConcurrentReads() {
        var selections = IntStream.range(0, 200).parallel()
                .mapToObj(index -> json.deserialize(index % 2 == 0 ? "{\"code\":\"xaX\"}" : "{\"code\":\"xbX\"}",
                                                   Selection.class)).toList();
        for (int i = 0; i < selections.size(); i++) {
            assertThat(selections.get(i), instanceOf(i % 2 == 0 ? Alpha.class : Beta.class));
        }
    }
}
