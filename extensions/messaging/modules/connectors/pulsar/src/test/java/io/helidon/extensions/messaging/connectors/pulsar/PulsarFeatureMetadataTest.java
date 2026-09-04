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

package io.helidon.extensions.messaging.connectors.pulsar;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import io.helidon.common.features.metadata.FeatureRegistry;
import io.helidon.common.features.metadata.FeatureStatus;
import io.helidon.common.features.metadata.Flavor;
import io.helidon.metadata.MetadataConstants;
import io.helidon.metadata.MetadataDiscovery;
import io.helidon.metadata.hson.Hson;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;

class PulsarFeatureMetadataTest {
    private static final String FEATURE_RESOURCE =
            "META-INF/helidon/unnamed/io.helidon.extensions.messaging.connectors.pulsar/feature-registry.json";
    private static final String SERVICE_RESOURCE =
            "META-INF/helidon/unnamed/io.helidon.extensions.messaging.connectors.pulsar/service-registry.json";

    @Test
    void declaresPreviewFeature() throws IOException {
        var registry = MetadataDiscovery.create(MetadataDiscovery.Mode.RESOURCES)
                .list(MetadataConstants.FEATURE_REGISTRY_FILE)
                .stream()
                .filter(it -> FEATURE_RESOURCE.equals(it.location()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Feature registry was not discovered"));

        try (var input = registry.inputStream()) {
            var metadata = FeatureRegistry.metadata(registry.absoluteLocation(), Hson.parse(input).asArray());

            assertThat(metadata.size(), is(1));
            var feature = metadata.getFirst();
            assertThat(feature.module(), is("unnamed/io.helidon.extensions.messaging.connectors.pulsar"));
            assertThat(feature.name(), is("Pulsar"));
            assertThat(feature.path(), is(List.of("Messaging", "Connectors", "Pulsar")));
            assertThat(feature.flavors(), is(List.of(Flavor.SE)));
            assertThat(feature.status(), is(FeatureStatus.PREVIEW));
        }
    }

    @Test
    void registersFeatureMetadataAlongsideServiceMetadata() throws IOException {
        var classes = Path.of("target/classes");
        var manifest = Files.readAllLines(classes.resolve("META-INF/helidon/manifest"));

        assertThat(manifest, hasItems(FEATURE_RESOURCE, SERVICE_RESOURCE));
        assertThat(Files.isRegularFile(classes.resolve(SERVICE_RESOURCE)), is(true));
    }
}
