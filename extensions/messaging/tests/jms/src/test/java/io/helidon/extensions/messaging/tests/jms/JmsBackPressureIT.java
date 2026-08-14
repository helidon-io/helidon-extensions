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

package io.helidon.extensions.messaging.tests.jms;

import java.nio.file.Path;
import java.time.Duration;

import io.helidon.extensions.messaging.MessagingRuntime;
import io.helidon.extensions.messaging.connectors.jms.JmsMessage;
import io.helidon.service.registry.ServiceRegistry;
import io.helidon.service.registry.ServiceRegistryManager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

class JmsBackPressureIT {
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration NO_DELIVERY_TIMEOUT = Duration.ofMillis(500);

    @TempDir
    private Path temporaryDirectory;

    @Test
    @Timeout(60)
    void testIncomingConnectorDoesNotDispatchAnotherMessageWhileTheFirstDeliveryIsActive() throws Exception {
        String queue = "back-pressure-" + System.nanoTime();
        try (ArtemisBroker broker = ArtemisBroker.create(temporaryDirectory)) {
            broker.start();
            ServiceRegistryManager manager = JmsScenarioRegistry.create(incomingConfig(queue),
                                                                         broker.connectionFactory(),
                                                                         JmsMessagingTypes.BackPressureReceiver.class);
            ServiceRegistry registry = manager.registry();
            JmsMessagingTypes.BackPressureReceiver receiver = registry.get(JmsMessagingTypes.BackPressureReceiver.class);

            try {
                registry.get(MessagingRuntime.class);
                JmsTestClient.sendText(broker.connectionFactory(), queue, false, "first", ignored -> { });
                JmsTestClient.sendText(broker.connectionFactory(), queue, false, "second", ignored -> { });

                JmsMessage<String> first = receiver.awaitMessage(WAIT_TIMEOUT);
                assertThat("first message", first, notNullValue());
                assertThat(first.entity(), is("first"));
                assertThat("no second application delivery while the first handler is blocked",
                           receiver.awaitMessage(NO_DELIVERY_TIMEOUT), nullValue());
                assertThat(receiver.deliveryCount(), is(1));

                receiver.releaseFirstMessage();
                JmsMessage<String> second = receiver.awaitMessage(WAIT_TIMEOUT);
                assertThat("second message", second, notNullValue());
                assertThat(second.entity(), is("second"));
                assertThat(receiver.deliveryCount(), is(2));
            } finally {
                receiver.releaseFirstMessage();
                manager.shutdown();
            }
        }
    }

    private static String incomingConfig(String destination) {
        return """
                helidon:
                  messaging:
                    incoming:
                      %s:
                        connector: jms
                        destination: "%s"
                        receive-timeout: PT0.05S
                """.formatted(JmsMessagingTypes.BACK_PRESSURE_INCOMING_CHANNEL, destination);
    }
}
