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

/**
 * Declarative Jakarta JSON-B greeting server.
 */
module io.helidon.extensions.jakarta.examples.declarative {
    requires io.helidon.webserver;
    requires io.helidon.service.registry;
    requires io.helidon.config.yaml;
    requires io.helidon.logging.common;
    requires io.helidon.extensions.jakarta.http.media.jsonb;
    requires jakarta.json.bind;

    exports io.helidon.extensions.jakarta.examples.jsonb;

    opens io.helidon.extensions.jakarta.examples.jsonb to org.eclipse.yasson;
}
