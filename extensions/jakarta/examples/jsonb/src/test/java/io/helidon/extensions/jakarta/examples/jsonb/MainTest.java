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

package io.helidon.extensions.jakarta.examples.jsonb;

import java.util.Map;

import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.extensions.jakarta.http.media.jsonb.JsonbSupport;

import jakarta.json.bind.JsonbBuilder;
import jakarta.json.bind.JsonbConfig;
import jakarta.json.bind.config.PropertyNamingStrategy;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

class MainTest {
    @Test
    void clientServerRoundTrip() {
        var response = Main.roundTrip(JsonbSupport.builder().build(), new Greeting("Příliš žluťoučký kůň"));
        assertThat(response.getMessage(), is("Příliš žluťoučký kůň"));
    }

    @Test
    void customJsonbInstance() throws Exception {
        try (var jsonb = JsonbBuilder.create(new JsonbConfig()
                .withPropertyNamingStrategy(PropertyNamingStrategy.UPPER_CAMEL_CASE))) {
            var support = JsonbSupport.builder().jsonb(jsonb).build();
            assertThat(jsonb.toJson(new Greeting("custom")), is("{\"Message\":\"custom\"}"));
            var response = Main.roundTrip(support, new Greeting("custom"));
            assertThat(response.getMessage(), is("custom"));
        }
    }

    @Test
    void configurationProperties() {
        var config = Config.create(ConfigSources.create(Map.of(
                "properties.jsonb.property-naming-strategy", PropertyNamingStrategy.UPPER_CAMEL_CASE)));
        var support = JsonbSupport.builder().config(config).build();
        assertThat(support.prototype().jsonb().toJson(new Greeting("configured")), is("{\"Message\":\"configured\"}"));
        var response = Main.roundTrip(support, new Greeting("configured"));
        assertThat(response.getMessage(), is("configured"));
    }
}
