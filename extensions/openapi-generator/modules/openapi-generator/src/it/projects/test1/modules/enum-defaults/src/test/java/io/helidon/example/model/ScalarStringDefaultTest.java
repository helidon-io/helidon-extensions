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

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

class ScalarStringDefaultTest {
    @Test
    void constructorPreservesSchemaValues() {
        assertSchemaValues(new StringDefaults());
    }

    @Test
    void builderPreservesSchemaValues() {
        assertSchemaValues(StringDefaults.builder().build());
    }

    @Test
    void explicitBuilderValueOverridesDefault() {
        assertThat(StringDefaults.builder().plain("custom").build().plain(), is("custom"));
    }

    private static void assertSchemaValues(StringDefaults defaults) {
        assertThat(defaults.plain(), is("plain"));
        assertThat(defaults.empty(), is(""));
        assertThat(defaults.quoted(), is("say \"hello\""));
        assertThat(defaults.backslash(), is("C:\\temp\\file"));
        assertThat(defaults.multiline(), is("line1\nline2\tend"));
        assertThat(defaults.unicode(), is("café 日本語"));
        assertThat(defaults.absent(), nullValue());
    }
}
