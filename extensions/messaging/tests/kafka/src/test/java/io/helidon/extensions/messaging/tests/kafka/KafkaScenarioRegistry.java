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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import io.helidon.common.media.type.MediaTypes;
import io.helidon.config.Config;
import io.helidon.service.registry.DescriptorHandler;
import io.helidon.service.registry.ServiceDescriptor;
import io.helidon.service.registry.ServiceDiscovery;
import io.helidon.service.registry.ServiceRegistryConfig;
import io.helidon.service.registry.ServiceRegistryManager;

final class KafkaScenarioRegistry {
    private static final String FIXTURE_TYPE = KafkaMessagingTypes.class.getName();
    private static final String NESTED_FIXTURE_PREFIX = FIXTURE_TYPE + ".";
    private static final String GENERATED_FIXTURE_PREFIX = FIXTURE_TYPE + "_";
    private static final List<ServiceDescriptor<?>> DISCOVERED_DESCRIPTORS = ServiceDiscovery.create()
            .allMetadata()
            .stream()
            .map(DescriptorHandler::descriptor)
            .toList();

    private KafkaScenarioRegistry() {
    }

    static ServiceRegistryManager create(String yaml, Class<?>... fixtureTypes) {
        Set<String> scenarioTypes = scenarioTypes(fixtureTypes);
        List<ServiceDescriptor<?>> descriptors = DISCOVERED_DESCRIPTORS.stream()
                .filter(descriptor -> includeDescriptor(descriptor, scenarioTypes))
                .toList();
        Config config = Config.just(yaml, MediaTypes.APPLICATION_YAML);
        ServiceRegistryConfig registryConfig = ServiceRegistryConfig.builder()
                .discoverServices(false)
                .discoverServicesFromServiceLoader(false)
                .addServiceDescriptors(descriptors)
                .putContractInstance(Config.class, config)
                .build();
        return ServiceRegistryManager.create(registryConfig);
    }

    private static Set<String> scenarioTypes(Class<?>[] fixtureTypes) {
        Set<String> result = new LinkedHashSet<>();
        for (Class<?> fixtureType : fixtureTypes) {
            Class<?> actualType = Objects.requireNonNull(fixtureType, "fixtureType");
            if (!KafkaMessagingTypes.class.equals(actualType.getEnclosingClass())) {
                throw new IllegalArgumentException("Not a Kafka messaging fixture type: " + actualType.getName());
            }
            result.add(actualType.getSimpleName());
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("At least one Kafka messaging fixture type is required");
        }
        return Set.copyOf(result);
    }

    private static boolean includeDescriptor(ServiceDescriptor<?> descriptor, Set<String> scenarioTypes) {
        String serviceType = descriptor.serviceType().fqName();
        if (serviceType.startsWith(NESTED_FIXTURE_PREFIX)) {
            return scenarioTypes.contains(serviceType.substring(NESTED_FIXTURE_PREFIX.length()));
        }
        if (!serviceType.startsWith(GENERATED_FIXTURE_PREFIX)) {
            return true;
        }
        int ownerEnd = serviceType.indexOf("__", GENERATED_FIXTURE_PREFIX.length());
        return ownerEnd > 0
                && scenarioTypes.contains(serviceType.substring(GENERATED_FIXTURE_PREFIX.length(), ownerEnd));
    }
}
