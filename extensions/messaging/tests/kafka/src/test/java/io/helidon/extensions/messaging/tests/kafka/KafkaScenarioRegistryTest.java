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

package io.helidon.extensions.messaging.tests.kafka;

import java.util.List;

import io.helidon.extensions.messaging.ConsumerRegistration;
import io.helidon.extensions.messaging.EmitterRegistration;
import io.helidon.extensions.messaging.MessagingRuntime;
import io.helidon.extensions.messaging.ProcessorRegistration;
import io.helidon.extensions.messaging.tests.kafka.KafkaMessagingTypes.ForwardingReceiver;
import io.helidon.extensions.messaging.tests.kafka.KafkaMessagingTypes.IncomingAnnotatedReceiver;
import io.helidon.extensions.messaging.tests.kafka.KafkaMessagingTypes.IncomingBatchReceiver;
import io.helidon.extensions.messaging.tests.kafka.KafkaMessagingTypes.IncomingMessageReceiver;
import io.helidon.extensions.messaging.tests.kafka.KafkaMessagingTypes.IncomingPayloadReceiver;
import io.helidon.extensions.messaging.tests.kafka.KafkaMessagingTypes.IncomingReceiver;
import io.helidon.extensions.messaging.tests.kafka.KafkaMessagingTypes.OutgoingSender;
import io.helidon.service.registry.ServiceRegistry;
import io.helidon.service.registry.ServiceRegistryManager;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class KafkaScenarioRegistryTest {
    @Test
    void activatesOnlySelectedFixtureTopologyWithoutKafkaBroker() {
        String yaml = """
                helidon:
                  messaging:
                    incoming:
                      kafka-in:
                        topology-only: true
                """;
        ServiceRegistryManager manager = KafkaScenarioRegistry.create(yaml,
                                                                       IncomingReceiver.class,
                                                                       IncomingPayloadReceiver.class,
                                                                       IncomingMessageReceiver.class,
                                                                       IncomingAnnotatedReceiver.class,
                                                                       IncomingBatchReceiver.class);
        try {
            ServiceRegistry registry = manager.registry();

            assertDoesNotThrow(() -> registry.get(MessagingRuntime.class));
            assertThat(registry.first(ForwardingReceiver.class).isEmpty(), is(true));
            assertThat(registry.all(EmitterRegistration.class).isEmpty(), is(true));
            assertThat(registry.all(ProcessorRegistration.class).isEmpty(), is(true));

            List<ConsumerRegistration> registrations = registry.all(ConsumerRegistration.class);
            assertThat(registrations.size(), is(4));
            assertThat(registrations.stream()
                               .allMatch(registration -> KafkaMessagingTypes.INCOMING_CHANNEL
                                       .equals(registration.channel())),
                       is(true));
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void retainsGeneratedRegistrationsOwnedBySelectedProducerFixtures() {
        ServiceRegistryManager emitterManager = KafkaScenarioRegistry.create("{}", OutgoingSender.class);
        try {
            ServiceRegistry registry = emitterManager.registry();
            assertThat(registry.all(EmitterRegistration.class).size(), is(1));
            assertThat(registry.all(ConsumerRegistration.class).isEmpty(), is(true));
        } finally {
            emitterManager.shutdown();
        }

        ServiceRegistryManager processorManager = KafkaScenarioRegistry.create("{}", ForwardingReceiver.class);
        try {
            ServiceRegistry registry = processorManager.registry();
            assertThat(registry.all(ProcessorRegistration.class).size(), is(1));
            assertThat(registry.all(ConsumerRegistration.class).size(), is(1));
            assertThat(registry.all(EmitterRegistration.class).isEmpty(), is(true));
        } finally {
            processorManager.shutdown();
        }
    }
}
