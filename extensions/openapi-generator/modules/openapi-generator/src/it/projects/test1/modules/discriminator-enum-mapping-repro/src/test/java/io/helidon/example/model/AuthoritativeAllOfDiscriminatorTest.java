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

import java.util.List;
import java.util.stream.IntStream;

import io.helidon.json.binding.JsonBinding;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuthoritativeAllOfDiscriminatorTest {

    private final JsonBinding jsonBinding = JsonBinding.create();

    @Test
    void exactAliasIsTheOnlySerializedDiscriminator() {
        ChangeFreezeConditionShape changeFreeze = new ChangeFreezeConditionShape();
        changeFreeze.changeFreezeDetails("scheduled");

        String json = jsonBinding.serialize((ConditionShapeDetails) changeFreeze, ConditionShapeDetails.class);
        assertThat(occurrences(json, "\"conditionShape\""), is(1));
        assertThat(json.contains("\"conditionShape\":\"CHANGE.FREEZE\""), is(true));
        assertThat(changeFreeze.conditionShape(), is(ConditionShapeDetails.ConditionShapeEnum.CHANGE_FREEZE));

        ConditionShapeDetails restored = jsonBinding.deserialize(json, ConditionShapeDetails.class);
        assertThat(restored, instanceOf(ChangeFreezeConditionShape.class));
        assertThat(restored.conditionShape(), is(ConditionShapeDetails.ConditionShapeEnum.CHANGE_FREEZE));
    }

    @Test
    void hyphenatedAliasRoundTrips() {
        TimeWindowConstraintsConditionShape timeWindow = new TimeWindowConstraintsConditionShape();
        timeWindow.timeWindowDetails("weekday");

        String json = jsonBinding.serialize((ConditionShapeDetails) timeWindow, ConditionShapeDetails.class);
        assertThat(occurrences(json, "\"conditionShape\""), is(1));
        assertThat(json.contains("\"conditionShape\":\"TIME-WINDOW-CONSTRAINTS\""), is(true));

        ConditionShapeDetails restored = jsonBinding.deserialize(json, ConditionShapeDetails.class);
        assertThat(restored, instanceOf(TimeWindowConstraintsConditionShape.class));
        assertThat(restored.conditionShape(),
                   is(ConditionShapeDetails.ConditionShapeEnum.TIME_WINDOW_CONSTRAINTS));
    }

    @Test
    void missingAndUnknownAliasesAreRejected() {
        assertThrows(RuntimeException.class,
                     () -> jsonBinding.deserialize("{\"changeFreezeDetails\":\"scheduled\"}",
                                                   ConditionShapeDetails.class));
        assertThrows(RuntimeException.class,
                     () -> jsonBinding.deserialize("{\"conditionShape\":\"UNKNOWN\"}",
                                                   ConditionShapeDetails.class));
    }

    @Test
    void explicitExtensionValueRoundTrips() {
        String json = jsonBinding.serialize((ExtensionBase) new ExtensionCat(), ExtensionBase.class);

        assertThat(occurrences(json, "\"kind\""), is(1));
        assertThat(json.contains("\"kind\":\"WIRE_CAT\""), is(true));
        assertThat(jsonBinding.deserialize(json, ExtensionBase.class), instanceOf(ExtensionCat.class));
    }

    @Test
    void inheritedEnumDiscriminatorRoundTripsThroughUnion() {
        String json = jsonBinding.serialize((EnumPet) new UnionCat(), EnumPet.class);

        assertThat(occurrences(json, "\"kind\""), is(1));
        assertThat(json.contains("\"kind\":\"cat\""), is(true));
        EnumPet restored = jsonBinding.deserialize(json, EnumPet.class);
        assertThat(restored, instanceOf(UnionCat.class));
        assertThat(restored.kind(), is(KindHolder.KindEnum.CAT));
    }

    @Test
    void directMappingToTransitiveLeafRoundTrips() {
        String json = jsonBinding.serialize((TransitiveBase) new TransitiveLeaf(), TransitiveBase.class);

        assertThat(occurrences(json, "\"kind\""), is(1));
        assertThat(json.contains("\"kind\":\"leaf\""), is(true));
        assertThat(jsonBinding.deserialize(json, TransitiveBase.class), instanceOf(TransitiveLeaf.class));
    }

    @Test
    void sharedBindingRoundTripsDiscriminatorAliasesConcurrently() {
        List<ConditionShapeDetails> restored = IntStream.range(0, 2_000)
                .parallel()
                .mapToObj(index -> index % 2 == 0
                        ? new ChangeFreezeConditionShape()
                        : new TimeWindowConstraintsConditionShape())
                .map(value -> jsonBinding.serialize((ConditionShapeDetails) value, ConditionShapeDetails.class))
                .peek(json -> assertThat(occurrences(json, "\"conditionShape\""), is(1)))
                .map(json -> jsonBinding.deserialize(json, ConditionShapeDetails.class))
                .toList();

        for (int i = 0; i < restored.size(); i++) {
            assertThat(restored.get(i), instanceOf(i % 2 == 0
                                                           ? ChangeFreezeConditionShape.class
                                                           : TimeWindowConstraintsConditionShape.class));
        }
    }

    private int occurrences(String value, String token) {
        return (value.length() - value.replace(token, "").length()) / token.length();
    }
}
