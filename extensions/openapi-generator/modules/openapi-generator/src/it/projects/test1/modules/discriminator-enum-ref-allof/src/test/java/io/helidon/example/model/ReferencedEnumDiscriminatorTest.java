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

import io.helidon.json.binding.JsonBinding;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReferencedEnumDiscriminatorTest {
    private final JsonBinding jsonBinding = JsonBinding.create();

    @Test
    void mutableSubtypeRoundTripsThroughParentWithInheritedProperties() {
        CreateCustomSoftwareSourceDetails custom = new CreateCustomSoftwareSourceDetails();
        custom.compartmentId("compartment");
        custom.displayName("Custom source");
        custom.softwareSourceSubType(SoftwareSourceSubType.FILTER);
        assertThat(custom.softwareSourceType(), is(SoftwareSourceType.CUSTOM));

        String json = jsonBinding.serialize((CreateSoftwareSourceDetails) custom, CreateSoftwareSourceDetails.class);
        assertThat(json, containsString("\"softwareSourceType\":\"CUSTOM\""));
        assertThat(occurrences(json, "\"softwareSourceType\""), is(1));
        CreateSoftwareSourceDetails restored = jsonBinding.deserialize(json, CreateSoftwareSourceDetails.class);
        assertThat(restored, instanceOf(CreateCustomSoftwareSourceDetails.class));
        assertThat(restored.softwareSourceType(), is(SoftwareSourceType.CUSTOM));
        assertThat(restored.compartmentId(), is("compartment"));
        assertThat(restored.displayName(), is("Custom source"));
        assertThat(((CreateCustomSoftwareSourceDetails) restored).softwareSourceSubType(), is(SoftwareSourceSubType.FILTER));
    }

    @Test
    void builderAcceptsOnlyTheCanonicalEnumValue() {
        CreateCustomSoftwareSourceDetails.Builder builder = new CreateCustomSoftwareSourceDetails.Builder();
        builder.compartmentId("compartment");
        builder.softwareSourceType(SoftwareSourceType.CUSTOM);
        builder.softwareSourceSubType(SoftwareSourceSubType.MANIFEST);
        CreateCustomSoftwareSourceDetails custom = builder.build();
        assertThat(custom.softwareSourceType(), is(SoftwareSourceType.CUSTOM));
        assertThat(custom.softwareSourceSubType(), is(SoftwareSourceSubType.MANIFEST));
        assertThrows(IllegalArgumentException.class,
                     () -> builder.softwareSourceType(SoftwareSourceType.VENDOR));
    }

    @Test
    void inheritedEnumDiscriminatorRoundTripsThroughUnion() {
        CreateVendorSoftwareSourceDetails vendor = CreateVendorSoftwareSourceDetails.builder()
                .compartmentId("compartment")
                .vendorName("Vendor")
                .build();
        String json = jsonBinding.serialize((SoftwareSourceChoice) vendor, SoftwareSourceChoice.class);
        SoftwareSourceChoice restored = jsonBinding.deserialize(json, SoftwareSourceChoice.class);
        assertThat(restored, instanceOf(CreateVendorSoftwareSourceDetails.class));
        assertThat(restored.softwareSourceType(), is(SoftwareSourceType.VENDOR));
    }

    @Test
    void escapedEnumAliasRoundTripsExactlyOnce() {
        EscapedCustomSource custom = new EscapedCustomSource();
        custom.description("Escaped alias");
        String json = jsonBinding.serialize((EscapedSource) custom, EscapedSource.class);
        assertThat(occurrences(json, "\"kind\""), is(1));
        EscapedSource restored = jsonBinding.deserialize(json, EscapedSource.class);
        assertThat(restored, instanceOf(EscapedCustomSource.class));
        assertThat(restored.kind().value(), is("custom\"mode\\path"));
    }

    @Test
    void missingAndUnknownDiscriminatorsAreRejected() {
        assertThrows(RuntimeException.class,
                     () -> jsonBinding.deserialize("{\"compartmentId\":\"compartment\"}", CreateSoftwareSourceDetails.class));
        assertThrows(RuntimeException.class,
                     () -> jsonBinding.deserialize("{\"softwareSourceType\":\"UNKNOWN\"}", CreateSoftwareSourceDetails.class));
    }

    private int occurrences(String value, String fragment) {
        return (value.length() - value.replace(fragment, "").length()) / fragment.length();
    }
}
