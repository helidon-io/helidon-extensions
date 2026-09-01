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

import java.util.Optional;
import java.util.concurrent.ExecutorService;

import io.helidon.builder.api.Option;
import io.helidon.extensions.langchain4j.AiProvider;

/**
 * Test provider whose configuration selects an observable model-construction plan.
 */
@AiProvider.ModelConfig(LifecycleTestModel.class)
interface LifecycleTestLc4jProvider extends AiProvider.ModelLifecycle {

    /**
     * Model construction plan registered by the lifecycle test.
     *
     * @return plan name
     */
    @Option.Configured
    Optional<String> plan();

    /**
     * Executor service resolved from the registry that owns the generated model factory.
     *
     * @return executor service
     */
    @Option.Configured
    @Option.RegistryService
    Optional<ExecutorService> executorService();

    @Override
    default boolean closeModelOnInitializationFailure() {
        return true;
    }

    @Override
    default boolean closeModelOnShutdown() {
        return plan().map(it -> !it.startsWith("rollback-only-")).orElse(true);
    }
}
