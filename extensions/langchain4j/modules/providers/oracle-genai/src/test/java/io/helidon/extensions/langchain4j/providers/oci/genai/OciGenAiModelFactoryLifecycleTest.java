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

package io.helidon.extensions.langchain4j.providers.oci.genai;

import io.helidon.service.registry.ServiceRegistryManager;
import io.helidon.testing.junit5.Testing;

import dev.langchain4j.community.model.oracle.oci.genai.OciGenAiChatModel;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Testing.Test
class OciGenAiModelFactoryLifecycleTest {
    @Test
    void cachesServicesAndClosesModelsOnShutdown() {
        var manager = ServiceRegistryManager.create();
        try {
            var factory = manager.registry().get(OciGenAiChatModelFactory.class);

            var first = factory.services();
            var second = factory.services();

            assertThat(first, hasSize(1));
            assertThat(second, sameInstance(first));
            var model = first.getFirst().get();
            assertThat(second.getFirst().get(), sameInstance(model));

            manager.shutdown();
            assertThat(factory.services(), is(empty()));
            assertClosed(model);
            factory.preDestroy();
            assertThat(factory.services(), is(empty()));
            assertClosed(model);
        } finally {
            manager.shutdown();
        }
    }

    private static void assertClosed(OciGenAiChatModel model) {
        var failure = assertThrows(IllegalStateException.class, () -> model.chat("ignored"));
        assertThat(failure.getMessage(), is("OCI GenAI model is closed."));
    }
}
