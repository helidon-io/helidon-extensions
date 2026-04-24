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

package io.helidon.extensions.gson.examples.imperative;

import java.util.concurrent.atomic.AtomicReference;

import io.helidon.config.Config;
import io.helidon.webserver.http.HttpRules;
import io.helidon.webserver.http.HttpService;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;

final class GreetingService implements HttpService {
    private final AtomicReference<String> greeting;
    private final AtomicReference<String> punctuation;

    private GreetingService(String greeting, String punctuation) {
        this.greeting = new AtomicReference<>(greeting);
        this.punctuation = new AtomicReference<>(punctuation);
    }

    static GreetingService create(Config config) {
        return new GreetingService(config.get("app.greeting")
                                           .asString()
                                           .orElse("Hello"),
                                   config.get("app.punctuation")
                                           .asString()
                                           .orElse(null));
    }

    @Override
    public void routing(HttpRules rules) {
        rules.get("/", this::helloWorld)
                .get("/{name}", this::helloName)
                .post("/", this::updateGreeting);
    }

    private void helloWorld(ServerRequest request, ServerResponse response) {
        response.send(currentGreeting("World"));
    }

    private void helloName(ServerRequest request, ServerResponse response) {
        response.send(currentGreeting(request.path().pathParameters().get("name")));
    }

    private void updateGreeting(ServerRequest request, ServerResponse response) {
        GreetingUpdate update = request.content().as(GreetingUpdate.class);

        if (update.getGreeting() != null) {
            greeting.set(update.getGreeting());
        }
        punctuation.set(update.getPunctuation());

        response.send(currentGreeting("World"));
    }

    private GreetingResponse currentGreeting(String name) {
        return new GreetingResponse(greeting.get(), name, punctuation.get());
    }
}
