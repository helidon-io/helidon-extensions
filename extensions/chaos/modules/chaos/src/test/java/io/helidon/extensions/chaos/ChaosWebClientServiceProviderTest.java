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
package io.helidon.extensions.chaos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import io.helidon.common.Weight;
import io.helidon.common.Weighted;
import io.helidon.config.Config;
import io.helidon.webclient.spi.WebClientService;
import io.helidon.webclient.spi.WebClientServiceProvider;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChaosWebClientServiceProviderTest {
    @Test
    void createsNamedChaosService() {
        WebClientServiceProvider provider = new ChaosWebClientServiceProvider();

        WebClientService service = provider.create(Config.empty(), "outbound-chaos");

        assertThat(provider.configKey(), is("chaos"));
        assertThat(service, instanceOf(ChaosWebClientService.class));
        assertThat(service.type(), is("chaos"));
        assertThat(service.name(), is("outbound-chaos"));
    }

    @Test
    void rejectsNullPublicInputs() {
        ChaosWebClientServiceProvider provider = new ChaosWebClientServiceProvider();

        assertThrows(NullPointerException.class, () -> provider.create(null, "chaos"));
        assertThrows(NullPointerException.class, () -> provider.create(Config.empty(), null));
    }

    @Test
    void hasExpectedWeightAndServiceDescriptor() throws IOException {
        Weight weight = ChaosWebClientServiceProvider.class.getAnnotation(Weight.class);

        assertThat(weight.value(), is(Weighted.DEFAULT_WEIGHT + 50));
        try (var descriptor = ChaosWebClientServiceProvider.class.getClassLoader()
                .getResourceAsStream("META-INF/services/io.helidon.webclient.spi.WebClientServiceProvider")) {
            assertThat(new String(descriptor.readAllBytes(), StandardCharsets.UTF_8),
                       containsString("io.helidon.extensions.chaos.ChaosWebClientServiceProvider"));
        }
    }
}
