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

package io.helidon.extensions.messaging.connectors.aq;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import javax.jms.ConnectionFactory;
import javax.sql.DataSource;

import io.helidon.common.types.ResolvedType;
import io.helidon.common.types.TypeName;
import io.helidon.messaging.MessagingException;
import io.helidon.service.registry.DependencyContext;
import io.helidon.service.registry.FactoryType;
import io.helidon.service.registry.InterceptionMetadata;
import io.helidon.service.registry.Qualifier;
import io.helidon.service.registry.Service;
import io.helidon.service.registry.ServiceDescriptor;
import io.helidon.service.registry.ServiceRegistry;
import io.helidon.service.registry.ServiceRegistryConfig;
import io.helidon.service.registry.ServiceRegistryManager;

import oracle.jms.AQjmsConnectionFactory;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

class AqResourceResolverTest {
    @Test
    void testDirectDataSourceDoesNotBorrowConnectionDuringResolution() {
        DataSource source = mock(DataSource.class);
        ServiceRegistry registry = mock(ServiceRegistry.class);
        var config = config(AqConnectorConfig.builder().name("aq").dataSource(source).buildPrototype());

        assertThat(new AqResourceResolver(registry).resolve(config), instanceOf(AQjmsConnectionFactory.class));
        verifyZeroInteractions(source, registry);
    }

    @Test
    void testNamedDataSourceIsResolvedExactly() {
        DataSource source = mock(DataSource.class);
        ServiceRegistry registry = mock(ServiceRegistry.class);
        when(registry.firstNamed(DataSource.class, "shared-db")).thenReturn(Optional.of(source));
        var config = config(AqConnectorConfig.builder().name("aq").dataSourceName("shared-db").buildPrototype());

        assertThat(new AqResourceResolver(registry).resolve(config), instanceOf(AQjmsConnectionFactory.class));
        verify(registry).firstNamed(DataSource.class, "shared-db");
        verifyNoMoreInteractions(registry);
        verifyZeroInteractions(source);
    }

    @Test
    void testMissingNamedDataSourceDoesNotFallBack() {
        ServiceRegistry registry = mock(ServiceRegistry.class);
        when(registry.firstNamed(DataSource.class, "missing")).thenReturn(Optional.empty());
        var config = config(AqConnectorConfig.builder().name("aq").dataSourceName("missing").buildPrototype());

        var failure = assertThrows(MessagingException.class, () -> new AqResourceResolver(registry).resolve(config));
        assertThat(failure.getMessage(), containsString("DataSource named missing"));
        verify(registry).firstNamed(DataSource.class, "missing");
        verifyNoMoreInteractions(registry);
    }

    @Test
    void testDirectAndNamedFactoriesArePreserved() {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        ServiceRegistry registry = mock(ServiceRegistry.class);
        var direct = config(AqConnectorConfig.builder().name("aq").connectionFactory(factory).buildPrototype());

        assertThat(new AqResourceResolver(registry).resolve(direct), sameInstance(factory));
        verifyZeroInteractions(registry, factory);

        when(registry.firstNamed(ConnectionFactory.class, "factory")).thenReturn(Optional.of(factory));
        var named = config(AqConnectorConfig.builder().name("aq").connectionFactoryName("factory").buildPrototype());
        assertThat(new AqResourceResolver(registry).resolve(named), sameInstance(factory));
        verify(registry).firstNamed(ConnectionFactory.class, "factory");
        verifyNoMoreInteractions(registry);
        verifyZeroInteractions(factory);
    }

    @Test
    void testMissingNamedFactoryDoesNotFallBack() {
        ServiceRegistry registry = mock(ServiceRegistry.class);
        when(registry.firstNamed(ConnectionFactory.class, "missing")).thenReturn(Optional.empty());
        var config = config(AqConnectorConfig.builder().name("aq").connectionFactoryName("missing").buildPrototype());

        var failure = assertThrows(MessagingException.class, () -> new AqResourceResolver(registry).resolve(config));
        assertThat(failure.getMessage(), containsString("ConnectionFactory named missing"));
        verify(registry).firstNamed(ConnectionFactory.class, "missing");
        verifyNoMoreInteractions(registry);
    }

    @Test
    void testDefaultDataSourcePrecedesDefaultFactory() {
        DataSource source = mock(DataSource.class);
        ConnectionFactory factory = mock(ConnectionFactory.class);
        var config = config(AqConnectorConfig.builder().name("aq").buildPrototype());
        var manager = ServiceRegistryManager.create(registryConfig()
                .putContractInstance(DataSource.class, source)
                .putContractInstance(ConnectionFactory.class, factory)
                .build());
        try {
            assertThat(new AqResourceResolver(manager.registry()).resolve(config), instanceOf(AQjmsConnectionFactory.class));
            verifyZeroInteractions(source, factory);
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void testNamedDataSourceDoesNotInterceptDefaultFactory() {
        DataSource analytics = mock(DataSource.class);
        ConnectionFactory factory = mock(ConnectionFactory.class);
        var manager = ServiceRegistryManager.create(registryConfig()
                .putContractInstance(ConnectionFactory.class, factory)
                .putServiceInstance(new NamedDescriptor<>(DataSource.class, "analytics"), analytics)
                .build());
        try {
            var config = config(AqConnectorConfig.builder().name("aq").buildPrototype());
            assertThat(new AqResourceResolver(manager.registry()).resolve(config), sameInstance(factory));
            verifyZeroInteractions(analytics, factory);
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void testLazyNamedDataSourceIsNotInitializedForDefaultLookup() {
        assertLazyNamedResourceIgnored(DataSource.class);
    }

    @Test
    void testLazyNamedFactoryIsNotInitializedForDefaultLookup() {
        assertLazyNamedResourceIgnored(ConnectionFactory.class);
    }

    @Test
    void testUnqualifiedDataSourceFailureDoesNotFallBackToFactory() {
        var expected = new IllegalStateException("default database unavailable");
        ConnectionFactory factory = mock(ConnectionFactory.class);
        var descriptor = new ResourceDescriptor<>(DataSource.class, "defaultSource", Set.of(), FactoryType.SERVICE, 100, () -> {
            throw expected;
        });
        var manager = ServiceRegistryManager.create(registryConfig()
                .putContractInstance(ConnectionFactory.class, factory)
                .addServiceDescriptor(descriptor)
                .build());
        try {
            var config = config(AqConnectorConfig.builder().name("aq").buildPrototype());
            var failure = assertThrows(IllegalStateException.class,
                                       () -> new AqResourceResolver(manager.registry()).resolve(config));
            assertThat(failure, sameInstance(expected));
            verifyZeroInteractions(factory);
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void testHighestRankedDefaultFactoryIsSelectedWithoutInitializingFallback() {
        ConnectionFactory preferred = mock(ConnectionFactory.class);
        var fallbackInitializations = new AtomicInteger();
        var fallback = new ResourceDescriptor<>(ConnectionFactory.class, "fallback", Set.of(), FactoryType.SERVICE, 100, () -> {
            fallbackInitializations.incrementAndGet();
            throw new IllegalStateException("unused fallback factory unavailable");
        });
        var higherRanked = new ResourceDescriptor<>(ConnectionFactory.class, "preferred", Set.of(), FactoryType.SERVICE, 200,
                                                   () -> preferred);
        var manager = ServiceRegistryManager.create(registryConfig()
                .addServiceDescriptor(fallback)
                .addServiceDescriptor(higherRanked)
                .build());
        try {
            var config = config(AqConnectorConfig.builder().name("aq").buildPrototype());
            assertThat(new AqResourceResolver(manager.registry()).resolve(config), sameInstance(preferred));
            assertThat(fallbackInitializations.get(), is(0));
            verifyZeroInteractions(preferred);
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void testServicesFactoryUsesInstanceQualifiers() {
        ConnectionFactory named = mock(ConnectionFactory.class);
        ConnectionFactory defaults = mock(ConnectionFactory.class);
        Service.ServicesFactory<ConnectionFactory> provider = () -> List.of(
                Service.QualifiedInstance.create(named, Qualifier.createNamed("analytics")),
                Service.QualifiedInstance.create(defaults));
        var descriptor = new ResourceDescriptor<>(ConnectionFactory.class, "provider", Set.of(Qualifier.createNamed("provider")),
                                                 FactoryType.SERVICES, 200, () -> provider);
        var manager = ServiceRegistryManager.create(registryConfig()
                .addServiceDescriptor(descriptor)
                .build());
        try {
            var resolver = new AqResourceResolver(manager.registry());
            var config = config(AqConnectorConfig.builder().name("aq").buildPrototype());
            assertThat(resolver.resolve(config), sameInstance(defaults));
            var namedConfig = config(AqConnectorConfig.builder().name("aq").connectionFactoryName("analytics").buildPrototype());
            assertThat(resolver.resolve(namedConfig), sameInstance(named));
            verifyZeroInteractions(named, defaults);
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void testServicesFactoryWithOnlyNamedInstancesDoesNotInterceptDefault() {
        ConnectionFactory named = mock(ConnectionFactory.class);
        ConnectionFactory defaults = mock(ConnectionFactory.class);
        Service.ServicesFactory<ConnectionFactory> provider = () -> List.of(
                Service.QualifiedInstance.create(named, Qualifier.createNamed("analytics")));
        var descriptor = new ResourceDescriptor<>(ConnectionFactory.class, "provider", Set.of(), FactoryType.SERVICES, 200,
                                                 () -> provider);
        var fallback = new ResourceDescriptor<>(ConnectionFactory.class, "fallback", Set.of(), FactoryType.SERVICE, 100,
                                               () -> defaults);
        var manager = ServiceRegistryManager.create(registryConfig()
                .addServiceDescriptor(descriptor)
                .addServiceDescriptor(fallback)
                .build());
        try {
            var config = config(AqConnectorConfig.builder().name("aq").buildPrototype());
            assertThat(new AqResourceResolver(manager.registry()).resolve(config), sameInstance(defaults));
            verifyZeroInteractions(named, defaults);
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void testInjectionPointFactoryUsesInstanceQualifiers() {
        ConnectionFactory defaults = mock(ConnectionFactory.class);
        Service.InjectionPointFactory<ConnectionFactory> provider = _ -> Optional.of(Service.QualifiedInstance.create(defaults));
        var descriptor = new ResourceDescriptor<>(ConnectionFactory.class, "provider", Set.of(Qualifier.createNamed("provider")),
                                                 FactoryType.INJECTION_POINT, 200, () -> provider);
        var manager = ServiceRegistryManager.create(registryConfig()
                .addServiceDescriptor(descriptor)
                .build());
        try {
            var config = config(AqConnectorConfig.builder().name("aq").buildPrototype());
            assertThat(new AqResourceResolver(manager.registry()).resolve(config), sameInstance(defaults));
            verifyZeroInteractions(defaults);
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void testNamedDataSourceRequiresExplicitSelection() {
        DataSource analytics = mock(DataSource.class);
        var manager = ServiceRegistryManager.create(registryConfig()
                .putServiceInstance(new NamedDescriptor<>(DataSource.class, "analytics"), analytics)
                .build());
        try {
            var resolver = new AqResourceResolver(manager.registry());
            var defaults = config(AqConnectorConfig.builder().name("aq").buildPrototype());
            assertThrows(MessagingException.class, () -> resolver.resolve(defaults));

            var named = config(AqConnectorConfig.builder().name("aq").dataSourceName("analytics").buildPrototype());
            assertThat(resolver.resolve(named), instanceOf(AQjmsConnectionFactory.class));
            verifyZeroInteractions(analytics);
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void testNamedFactoryRequiresExplicitSelection() {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        var manager = ServiceRegistryManager.create(registryConfig()
                .putServiceInstance(new NamedDescriptor<>(ConnectionFactory.class, "orders"), factory)
                .build());
        try {
            var resolver = new AqResourceResolver(manager.registry());
            var defaults = config(AqConnectorConfig.builder().name("aq").buildPrototype());
            assertThrows(MessagingException.class, () -> resolver.resolve(defaults));

            var named = config(AqConnectorConfig.builder().name("aq").connectionFactoryName("orders").buildPrototype());
            assertThat(resolver.resolve(named), sameInstance(factory));
            verifyZeroInteractions(factory);
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void testJdbcUrlDoesNotRequireRegistryOrConnection() {
        ServiceRegistry registry = mock(ServiceRegistry.class);
        var config = config(AqConnectorConfig.builder()
                                    .name("aq")
                                    .jdbcUrl("jdbc:oracle:thin:@localhost:1521/FREEPDB1")
                                    .buildPrototype());

        assertThat(new AqResourceResolver(registry).resolve(config), instanceOf(AQjmsConnectionFactory.class));
        verifyZeroInteractions(registry);
    }

    private static AqRuntimeConfig config(AqConnectorConfig defaults) {
        return AqRuntimeConfig.outgoing(defaults, AqOutgoingConfig.builder()
                .connector("aq")
                .channelName("orders")
                .destination("ORDERS")
                .build());
    }

    private static ServiceRegistryConfig.Builder registryConfig() {
        return ServiceRegistryConfig.builder().discoverServices(false).discoverServicesFromServiceLoader(false);
    }

    private static void assertLazyNamedResourceIgnored(Class<?> contract) {
        ConnectionFactory defaults = mock(ConnectionFactory.class);
        var initializations = new AtomicInteger();
        var expected = new IllegalStateException("analytics resource unavailable");
        var descriptor = new ResourceDescriptor<>(contract, "analytics", Set.of(Qualifier.createNamed("analytics")),
                                                 FactoryType.SERVICE, 200, () -> {
                                                     initializations.incrementAndGet();
                                                     throw expected;
                                                 });
        var defaultDescriptor = new ResourceDescriptor<>(ConnectionFactory.class, "defaultFactory", Set.of(),
                                                        FactoryType.SERVICE, 100, () -> defaults);
        var manager = ServiceRegistryManager.create(registryConfig()
                .addServiceDescriptor(defaultDescriptor)
                .addServiceDescriptor(descriptor)
                .build());
        try {
            var resolver = new AqResourceResolver(manager.registry());
            var config = config(AqConnectorConfig.builder().name("aq").buildPrototype());
            assertThat(resolver.resolve(config), sameInstance(defaults));
            assertThat(initializations.get(), is(0));

            var namedConfig = AqConnectorConfig.builder().name("aq");
            if (contract == DataSource.class) {
                namedConfig.dataSourceName("analytics");
            } else {
                namedConfig.connectionFactoryName("analytics");
            }
            var failure = assertThrows(IllegalStateException.class,
                                       () -> resolver.resolve(config(namedConfig.buildPrototype())));
            assertThat(failure, sameInstance(expected));
            assertThat(initializations.get(), is(1));
            verifyZeroInteractions(defaults);
        } finally {
            manager.shutdown();
        }
    }

    private static final class ResourceDescriptor<T> implements ServiceDescriptor<T> {
        private final Class<T> contract;
        private final String name;
        private final Set<Qualifier> qualifiers;
        private final FactoryType factoryType;
        private final double weight;
        private final Supplier<?> supplier;

        private ResourceDescriptor(Class<T> contract,
                                   String name,
                                   Set<Qualifier> qualifiers,
                                   FactoryType factoryType,
                                   double weight,
                                   Supplier<?> supplier) {
            this.contract = contract;
            this.name = name;
            this.qualifiers = qualifiers;
            this.factoryType = factoryType;
            this.weight = weight;
            this.supplier = supplier;
        }

        @Override
        public TypeName serviceType() {
            return TypeName.create(AqResourceResolverTest.class.getName() + "." + name);
        }

        @Override
        public TypeName descriptorType() {
            return TypeName.create(ResourceDescriptor.class);
        }

        @Override
        public Set<ResolvedType> contracts() {
            return Set.of(ResolvedType.create(contract));
        }

        @Override
        public Set<Qualifier> qualifiers() {
            return qualifiers;
        }

        @Override
        public FactoryType factoryType() {
            return factoryType;
        }

        @Override
        public double weight() {
            return weight;
        }

        @Override
        public Object instantiate(DependencyContext ctx, InterceptionMetadata interceptionMetadata) {
            return supplier.get();
        }
    }

    private static final class NamedDescriptor<T> implements ServiceDescriptor<T> {
        private final Class<T> contract;
        private final String name;

        private NamedDescriptor(Class<T> contract, String name) {
            this.contract = contract;
            this.name = name;
        }

        @Override
        public TypeName serviceType() {
            return TypeName.create(AqResourceResolverTest.class.getName() + "." + name);
        }

        @Override
        public TypeName descriptorType() {
            return TypeName.create(NamedDescriptor.class);
        }

        @Override
        public Set<ResolvedType> contracts() {
            return Set.of(ResolvedType.create(contract));
        }

        @Override
        public Set<Qualifier> qualifiers() {
            return Set.of(Qualifier.createNamed(name));
        }
    }
}
