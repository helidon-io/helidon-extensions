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

import java.util.Objects;
import java.util.concurrent.Executor;

import io.helidon.common.context.Context;
import io.helidon.common.context.Contexts;

import dev.langchain4j.spi.CapturedContext;
import dev.langchain4j.spi.ExecutorProvider;

/**
 * LangChain4j service provider that preserves the caller's Helidon context across deferred work.
 * Native WebClient services use this context for header and tracing propagation.
 */
public final class HelidonExecutorProvider implements ExecutorProvider {
    /**
     * Creates the provider. Public construction is required by {@link java.util.ServiceLoader}.
     */
    public HelidonExecutorProvider() {
    }

    /**
     * Retains LangChain4j's shared default executor.
     *
     * @return {@code null}, as required by the external SPI contract for a context-only provider
     */
    @Override
    public Executor executor() {
        return null;
    }

    @Override
    public CapturedContext captureContext() {
        Context context = Contexts.context().orElseGet(Context::create);
        return task -> {
            Objects.requireNonNull(task);
            return () -> Contexts.runInContext(context, task);
        };
    }
}
