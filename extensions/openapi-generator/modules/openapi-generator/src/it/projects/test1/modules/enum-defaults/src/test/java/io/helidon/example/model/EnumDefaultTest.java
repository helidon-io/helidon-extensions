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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class EnumDefaultTest {
    @Test
    void newModelUsesSchemaDefaults() {
        Item item = new Item();
        assertEquals(Mode.NONE, item.typedMode());
        assertEquals(Mode.NONE, item.mode());
        assertEquals(Item.InlineModeEnum.NONE, item.inlineMode());
        assertEquals(Integer.valueOf(7), item.count());
        assertEquals(Boolean.TRUE, item.enabled());
        assertNull(item.label());
    }
}
