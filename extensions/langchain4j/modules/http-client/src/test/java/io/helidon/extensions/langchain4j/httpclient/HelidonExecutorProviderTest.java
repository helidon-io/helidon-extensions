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

package io.helidon.extensions.langchain4j.httpclient;

import io.helidon.common.context.Context;
import io.helidon.common.context.Contexts;

import dev.langchain4j.spi.CapturedContext;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HelidonExecutorProviderTest {
    @Test
    void restoresCompletionContextAfterExceptionalTask() {
        Context caller = Context.create();
        Context completion = Context.create();
        CapturedContext captured = Contexts.runInContext(caller, () -> new HelidonExecutorProvider().captureContext());

        Contexts.runInContext(completion, () -> {
            assertThrows(IllegalStateException.class, () -> captured.wrap(() -> {
                assertThat(Contexts.context().orElseThrow(), sameInstance(caller));
                throw new IllegalStateException("task failed");
            }).run());
            assertThat(Contexts.context().orElseThrow(), sameInstance(completion));
        });
    }

    @Test
    void unboundCallerDoesNotInheritCompletionIdentity() {
        CapturedContext captured = new HelidonExecutorProvider().captureContext();
        Context completion = Context.create();
        completion.register("identity", "completion-user");

        Contexts.runInContext(completion, () -> {
            captured.wrap(() -> assertThat(Contexts.context().orElseThrow()
                                                  .get("identity", String.class).isEmpty(), is(true))).run();
            assertThat(Contexts.context().orElseThrow(), sameInstance(completion));
        });
    }
}
