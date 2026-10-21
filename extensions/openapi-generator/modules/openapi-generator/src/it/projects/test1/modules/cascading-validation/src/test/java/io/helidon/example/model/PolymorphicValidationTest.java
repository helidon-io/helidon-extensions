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

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.helidon.service.registry.Services;
import io.helidon.validation.TypeValidation;
import io.helidon.validation.ValidationResponse;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

/** Exercises freshly generated native validators through their declared polymorphic types. */
class PolymorphicValidationTest {
    private final TypeValidation validation = Services.get(TypeValidation.class);

    /** The actual child constraint must run even when the requested validator is the base type. */
    @Test
    void retainsSubtypeCollectionBoundary() {
        CappedRecord record = capped(255);
        assertThat(validation.validate(PolymorphicRecord.class, record).valid(), is(true));
        record.values(Collections.nCopies(256, "item"));
        assertInvalidAt(validation.validate(PolymorphicRecord.class, record), "values");
    }

    /** Inherited fields and descendants without new constraints still receive native validators. */
    @Test
    void retainsInheritedConstraintsAndUnconstrainedSibling() {
        OpenRecord record = new OpenRecord();
        record.compartmentId("ocid1.test");
        assertThat(validation.validate(PolymorphicRecord.class, record).valid(), is(true));
        record.compartmentId("x");
        assertInvalidAt(validation.validate(PolymorphicRecord.class, record), "compartmentId");
    }

    /** Multi-level inheritance must dispatch directly to the most concrete generated validator. */
    @Test
    void retainsGrandchildAndInheritedChildConstraints() {
        DeepRecord record = new DeepRecord();
        record.compartmentId("ocid1.test");
        record.values(List.of("item"));
        record.label("valid");
        record.suffix("valid");
        assertThat(validation.validate(PolymorphicRecord.class, record).valid(), is(true));
        record.suffix("x");
        assertInvalidAt(validation.validate(PolymorphicRecord.class, record), "suffix");
        record.suffix("valid");
        record.values(Collections.nCopies(256, "item"));
        assertInvalidAt(validation.validate(PolymorphicRecord.class, record), "values");
    }

    /** Reusing the native context must preserve the property and container locations. */
    @Test
    void cascadesThroughDeclaredBaseContainers() {
        CappedRecord invalid = capped(256);
        PolymorphicHolder holder = new PolymorphicHolder();
        holder.direct(invalid);
        holder.nullableRecord(Optional.of(invalid));
        holder.records(List.of(invalid));
        holder.recordsByName(Map.of("production", invalid));
        ValidationResponse response = validation.validate(PolymorphicHolder.class, holder);
        for (String boundary : List.of("direct", "nullableRecord", "records", "recordsByName")) {
            assertInvalidAt(response, boundary);
        }
        assertInvalidAt(response, "values");
    }

    /** An absent optional value must not create a spurious subtype lookup or constraint failure. */
    @Test
    void acceptsAbsentOptionalBase() {
        PolymorphicHolder holder = new PolymorphicHolder();
        holder.direct(capped(255));
        assertThat(holder.nullableRecord(), is(Optional.empty()));
        assertThat(validation.validate(PolymorphicHolder.class, holder).valid(), is(true));
    }

    /** Both native property APIs remain usable, including the API without an object instance. */
    @Test
    void retainsNativePropertyValidation() {
        CappedRecord record = capped(256);
        assertInvalidAt(validation.validate(PolymorphicRecord.class, record, "values"), "values");
        assertThat(validation.validateProperty(PolymorphicRecord.class, "compartmentId", "valid").valid(), is(true));
        assertInvalidAt(validation.validateProperty(PolymorphicRecord.class, "compartmentId", "x"), "compartmentId");
    }

    /** Abstract-base property metadata must keep nested cascades without intercepting abstract getters. */
    @Test
    void retainsOptionalCascadeDeclaredOnAbstractBase() {
        CappedRecord record = capped(1);
        ConstrainedLeaf invalid = new ConstrainedLeaf();
        invalid.code("x");
        invalid.label("y");
        record.baseLeaf(Optional.of(invalid));
        assertInvalidAt(validation.validate(PolymorphicRecord.class, record), "baseLeaf");
        assertInvalidAt(validation.validateProperty(PolymorphicRecord.class, "baseLeaf", Optional.of(invalid)), "code");
        record.baseLeaf(Optional.empty());
        assertThat(validation.validate(PolymorphicRecord.class, record).valid(), is(true));
    }

    /** Creates a record with valid inherited fields and a selectable collection size. */
    private static CappedRecord capped(int size) {
        CappedRecord record = new CappedRecord();
        record.compartmentId("ocid1.test");
        record.label("valid");
        record.values(Collections.nCopies(size, "item"));
        return record;
    }

    /** Checks the native violation locations without replacing or flattening the validation response. */
    private static void assertInvalidAt(ValidationResponse response, String property) {
        assertThat(response.valid(), is(false));
        String paths = response.violations().stream()
                .flatMap(violation -> violation.location().stream())
                .map(location -> location.name())
                .reduce("", (left, right) -> left + "/" + right);
        assertThat(paths, containsString(property));
    }
}
