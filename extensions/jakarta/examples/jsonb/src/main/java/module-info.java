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
 * Independently built Jakarta JSON-B client and server example.
 */
module io.helidon.extensions.jakarta.examples.jsonb {
    requires io.helidon.webserver;
    requires io.helidon.webclient;
    requires io.helidon.extensions.jakarta.http.media.jsonb;
    requires jakarta.json;
    requires jakarta.json.bind;

    exports io.helidon.extensions.jakarta.examples.jsonb;

    uses io.helidon.http.media.spi.MediaSupportProvider;

    opens io.helidon.extensions.jakarta.examples.jsonb to org.eclipse.yasson;
}
