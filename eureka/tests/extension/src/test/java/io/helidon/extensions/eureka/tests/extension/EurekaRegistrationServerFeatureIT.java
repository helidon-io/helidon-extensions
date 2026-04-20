/*
 * Copyright (c) 2025, 2026 Oracle and/or its affiliates.
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
package io.helidon.extensions.eureka.tests.extension;

import java.util.concurrent.TimeUnit;

import io.helidon.config.Config;
import io.helidon.extensions.eureka.EurekaRegistrationConfig;
import io.helidon.extensions.eureka.EurekaRegistrationServerFeature;
import io.helidon.json.JsonObject;
import io.helidon.json.JsonValue;
import io.helidon.service.registry.Services;
import io.helidon.webclient.api.WebClient;
import io.helidon.webserver.WebServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.helidon.common.media.type.MediaTypes.APPLICATION_JSON;
import static io.helidon.http.HeaderNames.ACCEPT_ENCODING;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

class EurekaRegistrationServerFeatureIT {

    private static final long REGISTRATION_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final long REGISTRATION_POLL_INTERVAL_MILLIS = 250L;

    /**
     * A {@link io.helidon.webserver.WebServer} emulating the usual Helidon Quickstart SE behavior that is used only as a vehicle for causing
     * its affiliated {@link io.helidon.extensions.eureka.EurekaRegistrationServerFeature} to be installed and started.
     */
    private WebServer ws;

    /**
     * A {@link io.helidon.webclient.api.WebClient} to use to verify that Eureka service instance registration took place successfully.
     */
    private WebClient wc;
    private EurekaRegistrationServerFeature feature;

    /**
     * Creates a new {@link EurekaRegistrationServerFeatureIT}.
     *
     * <p>Note that a Spring Eureka Server instance will have already been started by Maven Failsafe.</p>
     */
    EurekaRegistrationServerFeatureIT() {
        super();
    }

    /**
     * Sets up the verification {@link io.helidon.webclient.api.WebClient} and starts the {@link io.helidon.webserver.WebServer}.
     */
    @BeforeEach
    void beforeEach() {
        Config c = Services.get(Config.class);

        // Build the WebClient that will be used only by this test to verify that service instance registration
        // successfully occurred. For fidelity, use the same configuration as will be used by the ClientInfoConfig
        // class.
        this.wc = WebClient.builder()
                .config(c.get("helidon.server.features.eureka.client"))
                .sendExpectContinue(false) // Spring/Eureka can't handle it
                .build();

        // Configuration: ../../../../../resources/application.properties
        //
        // This is deliberately just the quickstart SE recipe; installing our server feature should be automagic if the
        // configuration is set up properly.
        //
        // The only difference is we use "helidon.server" as a root key instead of just "server" because the Spring
        // Eureka server uses a "server.port" property, and for convenience and ease of maintenance we want to share the
        // single application.properties classpath resource.
        this.ws = WebServer.builder()
                .config(c.get("helidon.server"))
                .routing(rb -> rb.get("/hello", (req, res) -> res.send("Hello World!")))
                .build();

        EurekaRegistrationServerFeature feature = null;

        var serverFeatures = ws.prototype().features();
        for (Object serverFeature : serverFeatures) {
            if (serverFeature instanceof EurekaRegistrationServerFeature ersf) {
                feature = ersf;
            }
        }

        assertThat("Eureka Server Feature not registered with webserver", feature, notNullValue());
        EurekaRegistrationConfig prototype = feature.prototype();
        assertThat("Eureka Server Feature is not enabled", prototype.enabled(), is(true));
        this.feature = feature;

        this.ws.start();
    }

    @AfterEach
    void afterEach() {
        if (this.wc != null) {
            this.wc.closeResource();
        }
        if (this.ws != null) {
            this.ws.stop();
        }
    }

    @Test
    void test() throws InterruptedException {
        assertThat(this.ws.isRunning(), is(true));
        int statusCode = -1;
        JsonObject responseEntity = null;
        long deadline = System.nanoTime() + REGISTRATION_TIMEOUT_NANOS;
        String appName = this.feature.prototype()
                .instanceInfo()
                .appName();

        do {
            try (var response = this.wc
                    .get("/v2/apps/" + appName)
                    .accept(APPLICATION_JSON)
                    .header(ACCEPT_ENCODING, "gzip")
                    .request()) {
                statusCode = response.status().code();
                if (statusCode == 200) {
                    assertThat(response.entity().hasEntity(), is(true));
                    responseEntity = response.entity().as(JsonObject.class);
                    break;
                }
            }
            if (System.nanoTime() < deadline) {
                Thread.sleep(REGISTRATION_POLL_INTERVAL_MILLIS);
            }
        } while (System.nanoTime() < deadline);

        assertThat("Expected Eureka registration to become visible", statusCode, is(200));
        assertThat(responseEntity, notNullValue());
        assertThat(responseEntity.objectValue("application")
                           .flatMap(it -> it.arrayValue("instance"))
                           .map(it -> it.values().getFirst())
                           .map(JsonValue::asObject)
                           .flatMap(it -> it.stringValue("status"))
                           .orElse(null),
                   is("UP"));
    }
}
