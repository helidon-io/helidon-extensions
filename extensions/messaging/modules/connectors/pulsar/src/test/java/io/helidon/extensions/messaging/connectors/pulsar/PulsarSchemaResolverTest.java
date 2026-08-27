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

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.helidon.messaging.ConnectorDirection;

import org.apache.pulsar.client.api.Schema;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PulsarSchemaResolverTest {
    private static final String CHANNEL = "orders";

    @Test
    void builtInSchemaDoesNotLoadCustomProviders() {
        AtomicBoolean loaded = new AtomicBoolean();
        PulsarSchemaResolver.ResolvedSchema resolved = PulsarSchemaResolver.resolve(
                config(ConnectorDirection.OUTGOING, null),
                ConnectorDirection.OUTGOING,
                () -> {
                    loaded.set(true);
                    throw new AssertionError("schema providers must stay lazy for built-ins");
                });

        assertThat(loaded.get(), is(false));
        assertThat(resolved.schema(), sameInstance(Schema.STRING));
        assertThat(resolved.builtIn(), is(PulsarSchemaType.STRING));
        assertThat(resolved.name(), is("STRING"));
        assertThat(resolved.direction(), is(ConnectorDirection.OUTGOING));
    }

    @Test
    void selectedProviderOverridesBuiltInAndIsExactAndCaseSensitive() {
        TestProvider provider = new TestProvider("order-json", Schema.INT32);
        PulsarConnectorConfig config = PulsarConnectorConfig.builder(
                        config(ConnectorDirection.OUTGOING, "order-json"))
                .schema(PulsarSchemaType.BYTES)
                .build();

        PulsarSchemaResolver.ResolvedSchema resolved = PulsarSchemaResolver.resolve(
                config,
                ConnectorDirection.OUTGOING,
                () -> List.of(provider));

        assertThat(resolved.schema(), sameInstance(Schema.INT32));
        assertThat(resolved.builtIn(), nullValue());
        assertThat(resolved.name(), is("order-json"));
        assertThat(resolved.snapshot(42), is(42));

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> PulsarSchemaResolver.resolve(config(ConnectorDirection.OUTGOING, "ORDER-JSON"),
                                                   ConnectorDirection.OUTGOING,
                                                   () -> List.of(provider)));
        assertThat(failure.getMessage(), containsString("No Pulsar schema provider named 'ORDER-JSON'"));
        assertThat(failure.getMessage(), containsString(CHANNEL));
    }

    @Test
    void sameProviderCanServeBothDirectionsAndIsInvokedOncePerBinding() {
        AtomicInteger invocations = new AtomicInteger();
        PulsarSchemaProvider provider = provider("shared", () -> {
            invocations.incrementAndGet();
            return Schema.INT64;
        });

        PulsarSchemaResolver.ResolvedSchema incoming = PulsarSchemaResolver.resolve(
                config(ConnectorDirection.INCOMING, "shared"),
                ConnectorDirection.INCOMING,
                () -> List.of(provider));
        PulsarSchemaResolver.ResolvedSchema outgoing = PulsarSchemaResolver.resolve(
                config(ConnectorDirection.OUTGOING, "shared"),
                ConnectorDirection.OUTGOING,
                () -> List.of(provider));

        assertThat(incoming.schema(), sameInstance(Schema.INT64));
        assertThat(outgoing.schema(), sameInstance(Schema.INT64));
        assertThat(invocations.get(), is(2));
    }

    @Test
    void explicitProviderRejectsMissingAndDuplicateNames() {
        IllegalArgumentException missing = assertThrows(
                IllegalArgumentException.class,
                () -> resolve("missing", List.of(new TestProvider("other", Schema.STRING))));
        assertThat(missing.getMessage(), containsString("No Pulsar schema provider named 'missing'"));
        assertThat(missing.getMessage(), containsString(CHANNEL));

        AtomicInteger schemaCalls = new AtomicInteger();
        PulsarSchemaProvider first = provider("duplicate", () -> {
            schemaCalls.incrementAndGet();
            return Schema.STRING;
        });
        PulsarSchemaProvider second = new TestProvider("duplicate", Schema.BYTES);
        IllegalArgumentException duplicate = assertThrows(
                IllegalArgumentException.class,
                () -> resolve("duplicate", List.of(first, second)));
        assertThat(duplicate.getMessage(), containsString("Multiple Pulsar schema providers are named 'duplicate'"));
        assertThat(duplicate.getMessage(), containsString(CHANNEL));
        assertThat(schemaCalls.get(), is(0));
    }

    @Test
    void explicitProviderRejectsInvalidRegistryResults() {
        IllegalArgumentException nullList = assertThrows(
                IllegalArgumentException.class,
                () -> PulsarSchemaResolver.resolve(config(ConnectorDirection.OUTGOING, "custom"),
                                                   ConnectorDirection.OUTGOING,
                                                   () -> null));
        assertThat(nullList.getMessage(), containsString("lookup returned null"));
        assertThat(nullList.getMessage(), containsString(CHANNEL));

        IllegalArgumentException nullProvider = assertThrows(
                IllegalArgumentException.class,
                () -> resolve("custom", java.util.Arrays.asList((PulsarSchemaProvider) null)));
        assertThat(nullProvider.getMessage(), containsString("lookup contains null"));
        assertThat(nullProvider.getMessage(), containsString(CHANNEL));

        IllegalArgumentException blankName = assertThrows(
                IllegalArgumentException.class,
                () -> resolve("custom", List.of(new TestProvider(" ", Schema.STRING))));
        assertThat(blankName.getMessage(), containsString("null or blank name"));
        assertThat(blankName.getMessage(), containsString(CHANNEL));
    }

    @Test
    void explicitProviderRejectsNullOrFailedSchema() {
        IllegalArgumentException nullSchema = assertThrows(
                IllegalArgumentException.class,
                () -> resolve("null-schema", List.of(new TestProvider("null-schema", null))));
        assertThat(nullSchema.getMessage(), containsString("'null-schema'"));
        assertThat(nullSchema.getMessage(), containsString("returned null"));
        assertThat(nullSchema.getMessage(), containsString(CHANNEL));

        IllegalStateException cause = new IllegalStateException("schema construction failed");
        IllegalArgumentException failedSchema = assertThrows(
                IllegalArgumentException.class,
                () -> resolve("failed-schema", List.of(provider("failed-schema", () -> {
                    throw cause;
                }))));
        assertThat(failedSchema.getCause(), sameInstance(cause));
        assertThat(failedSchema.getMessage(), containsString("'failed-schema'"));
        assertThat(failedSchema.getMessage(), containsString("failed for channel " + CHANNEL));
    }

    @Test
    void providerChecksDirectionBeforeResolvingSchema() {
        AtomicInteger supplierCalls = new AtomicInteger();
        AtomicInteger schemaCalls = new AtomicInteger();
        PulsarConnectorProvider provider = new PulsarConnectorProvider(() -> {
            supplierCalls.incrementAndGet();
            return List.of(provider("custom", () -> {
                schemaCalls.incrementAndGet();
                return Schema.STRING;
            }));
        });
        PulsarConnectorConfig outgoing = config(ConnectorDirection.OUTGOING, "custom");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                                                         () -> provider.createIncomingConnector(outgoing));

        assertThat(failure.getMessage(), containsString("expected INCOMING"));
        assertThat(supplierCalls.get(), is(0));
        assertThat(schemaCalls.get(), is(0));
    }

    @Test
    void publicVarargsConstructorSnapshotsProviders() {
        AtomicInteger invocations = new AtomicInteger();
        PulsarSchemaProvider custom = provider("custom", () -> {
            invocations.incrementAndGet();
            return Schema.STRING;
        });
        PulsarSchemaProvider[] providers = {custom};
        PulsarConnectorProvider connectorProvider = new PulsarConnectorProvider(providers);
        providers[0] = new TestProvider("replacement", Schema.BYTES);

        connectorProvider.createOutgoingConnector(config(ConnectorDirection.OUTGOING, "custom"));

        assertThat(invocations.get(), is(1));
        assertThrows(NullPointerException.class,
                     () -> new PulsarConnectorProvider((PulsarSchemaProvider[]) null));
        assertThrows(NullPointerException.class,
                     () -> new PulsarConnectorProvider(new PulsarSchemaProvider[] {null}));
    }

    private static PulsarSchemaResolver.ResolvedSchema resolve(String name, List<PulsarSchemaProvider> providers) {
        return PulsarSchemaResolver.resolve(config(ConnectorDirection.OUTGOING, name),
                                            ConnectorDirection.OUTGOING,
                                            () -> providers);
    }

    private static PulsarConnectorConfig config(ConnectorDirection direction, String schemaProvider) {
        PulsarConnectorConfig.Builder builder = PulsarConnectorConfig.builder()
                .direction(direction)
                .channel(CHANNEL)
                .connector(PulsarConnectorProvider.CONNECTOR_TYPE)
                .serviceUrl("pulsar://127.0.0.1:6650")
                .topic("persistent://public/default/orders");
        if (schemaProvider != null) {
            builder.schemaProvider(schemaProvider);
        }
        return builder.build();
    }

    private static PulsarSchemaProvider provider(String name, SchemaSupplier schemaSupplier) {
        return new PulsarSchemaProvider() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Schema<?> schema() {
                return schemaSupplier.get();
            }
        };
    }

    private record TestProvider(String name, Schema<?> schema) implements PulsarSchemaProvider {
    }

    @FunctionalInterface
    private interface SchemaSupplier {
        Schema<?> get();
    }
}
