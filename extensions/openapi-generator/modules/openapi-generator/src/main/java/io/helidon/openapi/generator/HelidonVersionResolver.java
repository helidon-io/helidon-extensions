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

package io.helidon.openapi.generator;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

final class HelidonVersionResolver {
    static final URI RESOLUTION_URI = URI.create("https://helidon.io/api/versions/");
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private HelidonVersionResolver() {
    }

    static String resolve(String version) {
        return resolve(version, RESOLUTION_URI);
    }

    static String resolve(String version, URI baseUri) {
        if (!version.startsWith("v")) {
            return version;
        }

        String requestedVersion = version.trim();
        URI requestUri = null;
        try {
            requestUri = baseUri.resolve(requestedVersion);
            HttpRequest request = HttpRequest.newBuilder(requestUri)
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("HTTP " + response.statusCode());
            }
            return parseResolvedHelidonVersion(requestedVersion, requestUri, response.body());
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalArgumentException(helidonVersionResolutionFailure(requestedVersion, requestUri, e.getMessage()));
        }
    }

    private static String parseResolvedHelidonVersion(String shorthand, URI requestUri, String body) {
        String resolvedVersion = body == null ? "" : body.trim();
        if (resolvedVersion.isEmpty()) {
            throw new IllegalArgumentException(
                    helidonVersionResolutionFailure(
                            shorthand, requestUri, "Helidon version service returned an empty response body"));
        }
        return resolvedVersion;
    }

    private static String helidonVersionResolutionFailure(
            String shorthand,
            URI requestUri,
            String reason) {
        return String.format(
                "Failed to resolve Helidon version '%s' from %s: %s. "
                        + "Set helidonVersion to a specific Helidon release instead of a version shorthand.",
                shorthand, requestUri, reason);
    }
}
