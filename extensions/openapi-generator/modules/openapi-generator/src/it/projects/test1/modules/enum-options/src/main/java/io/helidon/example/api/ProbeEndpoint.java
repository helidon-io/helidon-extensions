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
package io.helidon.example.api;

import java.util.Optional;

import io.helidon.example.model.Mode;
import io.helidon.http.Http;
import io.helidon.service.registry.Service;
import io.helidon.webserver.http.RestServer;

@Service.Singleton
@RestServer.Endpoint
@Http.Path("/probe")
class ProbeEndpoint implements ModesApi {
    @Override
    public String inspect(Optional<Mode> mode, Optional<InspectSortByEnum> sortBy) {
        return mode.map(Mode::value).orElse("absent") + ":"
                + sortBy.map(InspectSortByEnum::value).orElse("absent");
    }
}
