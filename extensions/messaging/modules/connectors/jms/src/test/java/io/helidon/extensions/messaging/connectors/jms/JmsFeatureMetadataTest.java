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

package io.helidon.extensions.messaging.connectors.jms;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import io.helidon.metadata.MetadataConstants;
import io.helidon.metadata.MetadataDiscovery;

import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;

class JmsFeatureMetadataTest {
    private static final String FEATURE_RESOURCE =
            "META-INF/helidon/io.helidon.extensions.messaging.connectors.jms/feature-registry.json";
    private static final String SERVICE_RESOURCE =
            "META-INF/helidon/io.helidon.extensions.messaging.connectors.jms/service-registry.json";

    @Test
    void declaresPreviewFeature() throws IOException {
        var registry = MetadataDiscovery.create(MetadataDiscovery.Mode.RESOURCES)
                .list(MetadataConstants.FEATURE_REGISTRY_FILE)
                .stream()
                .filter(it -> FEATURE_RESOURCE.equals(it.location()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Feature registry was not discovered"));

        try (var input = registry.inputStream()) {
            var json = new String(input.readAllBytes(), UTF_8);
            assertThat(json, containsString("\"module\":\"io.helidon.extensions.messaging.connectors.jms\""));
            assertThat(json, containsString("\"name\":\"JMS\""));
            assertThat(json, containsString("\"path\":[\"Messaging\",\"Connectors\",\"JMS\"]"));
            assertThat(json, containsString("\"flavor\":[\"SE\"]"));
            assertThat(json, containsString("\"status\":\"PREVIEW\""));
        }
    }

    @Test
    void registersFeatureMetadataAlongsideServiceMetadata() throws IOException {
        var classes = Path.of("target/classes");
        var manifest = Files.readAllLines(classes.resolve("META-INF/helidon/manifest"));

        assertThat(manifest, hasItems(FEATURE_RESOURCE, SERVICE_RESOURCE));
        assertThat(Files.isRegularFile(classes.resolve(FEATURE_RESOURCE)), is(true));
        assertThat(Files.isRegularFile(classes.resolve(SERVICE_RESOURCE)), is(true));
    }
}
