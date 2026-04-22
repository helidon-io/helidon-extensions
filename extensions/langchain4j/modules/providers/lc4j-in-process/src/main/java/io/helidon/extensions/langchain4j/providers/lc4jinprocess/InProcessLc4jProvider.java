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

package io.helidon.extensions.langchain4j.providers.lc4jinprocess;

import io.helidon.extensions.langchain4j.AiProvider;

@AiProvider.CustomModelFactories
final class InProcessLc4jProvider {
    static final String PROVIDER_KEY = "lc4j-in-process";
    static final String CONFIG_ROOT = "langchain4j.providers.lc4j-in-process";

    private InProcessLc4jProvider() {
    }
}
