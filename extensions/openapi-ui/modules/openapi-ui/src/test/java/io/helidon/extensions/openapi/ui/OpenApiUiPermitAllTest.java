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
package io.helidon.extensions.openapi.ui;

import io.helidon.common.media.type.MediaTypes;
import io.helidon.http.Status;
import io.helidon.openapi.OpenApiFeature;
import io.helidon.webclient.api.ClientResponseTyped;
import io.helidon.webclient.api.WebClient;
import io.helidon.webserver.WebServerConfig;
import io.helidon.webserver.http.HttpRouting;
import io.helidon.webserver.testing.junit5.ServerTest;
import io.helidon.webserver.testing.junit5.SetUpRoute;
import io.helidon.webserver.testing.junit5.SetUpServer;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

@ServerTest
class OpenApiUiPermitAllTest {
    private final WebClient client;

    OpenApiUiPermitAllTest(WebClient client) {
        this.client = client;
    }

    @SetUpServer
    static void setupServer(WebServerConfig.Builder server) {
        // @ServerTest bootstraps features from the service registry before invoking this callback.
        server.featuresDiscoverServices(false)
                .clearFeatures()
                .addFeature(OpenApiFeature.builder()
                                  .servicesDiscoverServices(false)
                                  .staticFile("src/test/resources/greeting.yml")
                                  .permitAll(false)
                                  .addService(OpenApiUi.create())
                                  .build())
                .addFeature(OpenApiFeature.builder()
                                  .servicesDiscoverServices(false)
                                  .staticFile("src/test/resources/greeting.yml")
                                  .webContext("/my-openapi")
                                  .name("my-openapi")
                                  .permitAll(false)
                                  .addService(OpenApiUi.builder()
                                                      .webContext("/my-ui")
                                                      .build())
                                  .build())
                .addFeature(OpenApiFeature.builder()
                                  .servicesDiscoverServices(false)
                                  .staticFile("src/test/resources/greeting.yml")
                                  .webContext("/")
                                  .name("root-openapi")
                                  .permitAll(false)
                                  .build());
    }

    @SetUpRoute
    static void setup(HttpRouting.Builder routing) {
    }

    @Test
    void exactPathRequiresAuthorization() {
        ClientResponseTyped<String> response = client.get("/openapi")
                .accept(MediaTypes.TEXT_YAML)
                .request(String.class);

        assertThat(response.status(), is(Status.FORBIDDEN_403));
    }

    @Test
    void trailingSlashRequiresAuthorization() {
        ClientResponseTyped<String> response = client.get("/openapi/")
                .accept(MediaTypes.TEXT_YAML)
                .request(String.class);

        assertThat(response.status(), is(Status.FORBIDDEN_403));
    }

    @Test
    void uiIndexRequiresAuthorization() {
        ClientResponseTyped<String> response = client.get("/openapi/ui/index.html")
                .accept(MediaTypes.TEXT_HTML)
                .request(String.class);

        assertThat(response.status(), is(Status.FORBIDDEN_403));
    }

    @Test
    void uiRedirectRequiresAuthorization() {
        ClientResponseTyped<String> response = client.get("/openapi/ui")
                .accept(MediaTypes.TEXT_HTML)
                .request(String.class);

        assertThat(response.status(), is(Status.FORBIDDEN_403));
    }

    @Test
    void uiStaticContentRequiresAuthorization() {
        ClientResponseTyped<String> response = client.get("/openapi/ui/logo.png")
                .request(String.class);

        assertThat(response.status(), is(Status.FORBIDDEN_403));
    }

    @Test
    void customUiIndexRequiresAuthorization() {
        ClientResponseTyped<String> response = client.get("/my-ui/index.html")
                .accept(MediaTypes.TEXT_HTML)
                .request(String.class);

        assertThat(response.status(), is(Status.FORBIDDEN_403));
    }

    @Test
    void customUiRedirectRequiresAuthorization() {
        ClientResponseTyped<String> response = client.get("/my-ui")
                .accept(MediaTypes.TEXT_HTML)
                .request(String.class);

        assertThat(response.status(), is(Status.FORBIDDEN_403));
    }

    @Test
    void customUiStaticContentRequiresAuthorization() {
        ClientResponseTyped<String> response = client.get("/my-ui/logo.png")
                .request(String.class);

        assertThat(response.status(), is(Status.FORBIDDEN_403));
    }

    @Test
    void rootContextDoesNotProtectUnrelatedPaths() {
        ClientResponseTyped<String> response = client.get("/not-openapi")
                .accept(MediaTypes.TEXT_PLAIN)
                .request(String.class);

        assertThat(response.status(), is(Status.NOT_FOUND_404));
    }
}
