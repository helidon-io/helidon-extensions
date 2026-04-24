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

package io.helidon.extensions.gson.examples.declarative;

import java.util.concurrent.atomic.AtomicReference;

import io.helidon.common.Default;
import io.helidon.common.media.type.MediaTypes;
import io.helidon.config.Configuration;
import io.helidon.http.Http;
import io.helidon.service.registry.Service;
import io.helidon.webserver.http.RestServer;

@RestServer.Endpoint
@Http.Path("/hello")
@Service.Singleton
class GreetingEndpoint {
    private final AtomicReference<String> greeting = new AtomicReference<>();
    private final AtomicReference<String> punctuation = new AtomicReference<>();

    @Service.Inject
    GreetingEndpoint(@Default.Value("Hello") @Configuration.Value("app.greeting") String greeting) {
        this.greeting.set(greeting);
    }

    @Http.GET
    @Http.Produces(MediaTypes.APPLICATION_JSON_VALUE)
    GreetingDto hello() {
        return currentGreeting("World");
    }

    @Http.GET
    @Http.Path("/{name}")
    @Http.Produces(MediaTypes.APPLICATION_JSON_VALUE)
    GreetingDto hello(@Http.PathParam("name") String name) {
        return currentGreeting(name);
    }

    @Http.POST
    @Http.Consumes(MediaTypes.APPLICATION_JSON_VALUE)
    @Http.Produces(MediaTypes.APPLICATION_JSON_VALUE)
    GreetingDto updateGreeting(@Http.Entity GreetingUpdate update) {
        if (update.getGreeting() != null) {
            greeting.set(update.getGreeting());
        }
        punctuation.set(update.getPunctuation());
        return currentGreeting("World");
    }

    private GreetingDto currentGreeting(String name) {
        return new GreetingDto(greeting.get(), name, punctuation.get());
    }
}
