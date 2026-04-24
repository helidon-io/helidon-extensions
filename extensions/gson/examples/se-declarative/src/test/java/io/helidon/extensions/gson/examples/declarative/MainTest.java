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

import io.helidon.common.media.type.MediaTypes;
import io.helidon.http.Status;
import io.helidon.webclient.http1.Http1Client;
import io.helidon.webserver.testing.junit5.ServerTest;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

@ServerTest
class MainTest {
    private final Http1Client client;

    MainTest(Http1Client client) {
        this.client = client;
    }

    @Test
    void testHelloWorld() {
        var response = client.get("/hello")
                .accept(MediaTypes.APPLICATION_JSON)
                .request(GreetingDto.class);

        assertThat(response.status(), is(Status.OK_200));
        GreetingDto entity = response.entity();
        assertThat(entity.getGreeting(), is("Hello"));
        assertThat(entity.getName(), is("World"));
        assertThat(entity.getPunctuation(), is((String) null));
    }

    @Test
    void testHelloNamed() {
        var response = client.get("/hello/Reader")
                .accept(MediaTypes.APPLICATION_JSON)
                .request(GreetingDto.class);

        assertThat(response.status(), is(Status.OK_200));
        GreetingDto entity = response.entity();
        assertThat(entity.getGreeting(), is("Hello"));
        assertThat(entity.getName(), is("Reader"));
        assertThat(entity.getPunctuation(), is((String) null));
    }

    @Test
    void testUpdateGreeting() {
        var response = client.post("/hello")
                .contentType(MediaTypes.APPLICATION_JSON)
                .accept(MediaTypes.APPLICATION_JSON)
                .submit("{\"greeting\":\"Ahoj\",\"punctuation\":\"!\"}", GreetingDto.class);

        assertThat(response.status(), is(Status.OK_200));
        GreetingDto entity = response.entity();
        assertThat(entity.getGreeting(), is("Ahoj"));
        assertThat(entity.getName(), is("World"));
        assertThat(entity.getPunctuation(), is("!"));

        try {
            GreetingDto updated = client.get("/hello/Reader")
                    .accept(MediaTypes.APPLICATION_JSON)
                    .requestEntity(GreetingDto.class);
            assertThat(updated.getGreeting(), is("Ahoj"));
            assertThat(updated.getName(), is("Reader"));
            assertThat(updated.getPunctuation(), is("!"));
        } finally {
            client.post("/hello")
                    .contentType(MediaTypes.APPLICATION_JSON)
                    .accept(MediaTypes.APPLICATION_JSON)
                    .submit("{\"greeting\":\"Hello\"}")
                    .close();
        }
    }
}
