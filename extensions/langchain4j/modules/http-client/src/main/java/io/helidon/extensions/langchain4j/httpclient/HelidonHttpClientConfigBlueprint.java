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

import io.helidon.builder.api.Option;
import io.helidon.builder.api.Prototype;
import io.helidon.webclient.api.WebClient;

/**
 * Configuration for the LangChain4j HTTP adapter backed by Helidon WebClient.
 */
@Prototype.Blueprint
interface HelidonHttpClientConfigBlueprint extends Prototype.Factory<HelidonHttpClient> {
    /**
     * The configured WebClient. The caller retains ownership of this client, including
     * when a generated builder overload creates it from WebClient configuration. Such
     * a client remains available through {@code adapter.prototype().webClient()}.
     *
     * @return configured WebClient
     */
    @Option.Required
    WebClient webClient();
}
