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

package io.helidon.extensions.langchain4j.providers.oci.genai;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.helidon.common.media.type.MediaTypes;
import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.service.registry.ServiceRegistry;
import io.helidon.testing.junit5.Testing;

import com.oracle.bmc.Region;
import com.oracle.bmc.auth.BasicAuthenticationDetailsProvider;
import com.oracle.bmc.generativeaiinference.GenerativeAiInferenceAsyncClient;
import com.oracle.bmc.generativeaiinference.GenerativeAiInferenceClient;
import dev.langchain4j.community.model.oracle.oci.genai.OciGenAiChatModel;
import dev.langchain4j.community.model.oracle.oci.genai.OciGenAiStreamingChatModel;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayContaining;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@Testing.Test(perMethod = true)
class OciGenAiModelFactoryLifecycleTest {
    @Test
    void cachesServicesAndLeavesSharedRegistryClientOpenOnShutdown(ServiceRegistry registry) {
        var client = registry.get(GenerativeAiInferenceClient.class);
        registry.get(MockGenAiUtilBean.class);
        var factory = new OciGenAiChatModelFactory(twoModelConfig());

        var first = factory.services();
        var second = factory.services();

        assertThat(first, hasSize(2));
        assertThat(second, sameInstance(first));
        assertThat(second.getFirst().get(), sameInstance(first.getFirst().get()));
        assertThat(first.get(1).get(), not(sameInstance(first.getFirst().get())));
        long closesBeforeShutdown = closeInvocationCount(client);

        factory.preDestroy();
        assertThat(factory.services(), is(empty()));
        assertThat(closeInvocationCount(client), is(closesBeforeShutdown));
        assertThat(first.getFirst().get().chat("after shutdown"), is("OK"));
        assertThat(first.get(1).get().chat("after shutdown"), is("OK"));

        factory.preDestroy();
        assertThat(factory.services(), is(empty()));
        assertThat(closeInvocationCount(client), is(closesBeforeShutdown));
    }

    @Test
    void closesInternallyOwnedModelOnShutdown() {
        var factory = new OciGenAiChatModelFactory(ownedModelConfig());
        var model = factory.services().getFirst().get();

        factory.preDestroy();

        assertThat(factory.services(), is(empty()));
        assertClosed(model);
        factory.preDestroy();
        assertClosed(model);
    }

    @Test
    void preservesOwnershipForMixedAuthenticationAndClientConfigurations() {
        var authProvider = Mockito.mock(BasicAuthenticationDetailsProvider.class);
        var syncClient = Mockito.mock(GenerativeAiInferenceClient.class);
        var asyncClient = Mockito.mock(GenerativeAiInferenceAsyncClient.class);
        var ownedSyncConfig = OciGenAiChatModelConfig.builder()
                .modelName("model-name")
                .compartmentId("compartment-id")
                .region(Region.US_ASHBURN_1)
                .authProvider(authProvider)
                .genAiClientDiscoverServices(false)
                .build();
        var borrowedSyncConfig = OciGenAiChatModelConfig.builder(ownedSyncConfig)
                .genAiClient(syncClient)
                .build();
        var ownedStreamingConfig = OciGenAiStreamingChatModelConfig.builder()
                .modelName("model-name")
                .compartmentId("compartment-id")
                .region(Region.US_ASHBURN_1)
                .authProvider(authProvider)
                .genAiClientDiscoverServices(false)
                .genAiAsyncClientDiscoverServices(false)
                .build();
        var borrowedStreamingSyncConfig = OciGenAiStreamingChatModelConfig.builder(ownedStreamingConfig)
                .genAiClient(syncClient)
                .build();
        var borrowedStreamingAsyncConfig = OciGenAiStreamingChatModelConfig.builder(ownedStreamingConfig)
                .genAiAsyncClient(asyncClient)
                .build();
        var ownedCohereStreamingConfig = OciGenAiCohereStreamingChatModelConfig.builder()
                .modelName("model-name")
                .compartmentId("compartment-id")
                .region(Region.US_ASHBURN_1)
                .authProvider(authProvider)
                .genAiClientDiscoverServices(false)
                .genAiAsyncClientDiscoverServices(false)
                .build();
        var borrowedCohereStreamingConfig = OciGenAiCohereStreamingChatModelConfig.builder(ownedCohereStreamingConfig)
                .genAiAsyncClient(asyncClient)
                .build();

        assertThat(ownedSyncConfig.closeModelOnShutdown(), is(true));
        assertThat(borrowedSyncConfig.closeModelOnShutdown(), is(false));
        assertThat(ownedStreamingConfig.closeModelOnShutdown(), is(true));
        assertThat(borrowedStreamingSyncConfig.closeModelOnShutdown(), is(false));
        assertThat(borrowedStreamingAsyncConfig.closeModelOnShutdown(), is(false));
        assertThat(ownedCohereStreamingConfig.closeModelOnShutdown(), is(true));
        assertThat(borrowedCohereStreamingConfig.closeModelOnShutdown(), is(false));
    }

    @Test
    void synchronousConfigsDoNotExposeIrrelevantAsyncClient() {
        assertThrows(NoSuchMethodException.class,
                     () -> OciGenAiChatModelConfig.class.getMethod("genAiAsyncClient"));
        assertThrows(NoSuchMethodException.class,
                     () -> OciGenAiCohereChatModelConfig.class.getMethod("genAiAsyncClient"));
    }

    @Test
    void closesSyncModelButKeepsRegistryAsyncClientOpenForMixedProviderConfig(ServiceRegistry registry) {
        var asyncClient = registry.get(GenerativeAiInferenceAsyncClient.class);
        var config = mixedAuthAndAsyncClientConfig();
        var streamingConfig = OciGenAiStreamingChatModelConfig.builder()
                .serviceRegistry(registry)
                .config(OciGenAiConstants.create(config, OciGenAiStreamingChatModel.class, "mixed"))
                .build();
        var syncFactory = new OciGenAiChatModelFactory(config);
        var streamingFactory = new OciGenAiStreamingChatModelFactory(config);
        var syncModel = syncFactory.services().getFirst().get();
        long closesBeforeShutdown = closeInvocationCount(asyncClient);

        assertThat(streamingConfig.genAiAsyncClient().orElseThrow(), sameInstance(asyncClient));
        assertThat(streamingConfig.closeModelOnShutdown(), is(false));
        assertThat(streamingFactory.services(), hasSize(1));

        syncFactory.preDestroy();
        assertClosed(syncModel);
        assertThat(closeInvocationCount(asyncClient), is(closesBeforeShutdown));

        streamingFactory.preDestroy();
        assertThat(closeInvocationCount(asyncClient), is(closesBeforeShutdown));
    }

    @Test
    void closesEarlierOwnedModelsWhenLaterConstructionFails() {
        var model = Mockito.mock(OciGenAiChatModel.class);
        var constructionFailure = new IllegalArgumentException("model construction failed");
        var cleanupFailure = new IllegalStateException("model cleanup failed");
        doThrow(cleanupFailure).when(model).close();
        var factory = new FailingModelFactory(twoOwnedModelConfig(), model, constructionFailure);

        var actual = assertThrows(IllegalArgumentException.class, factory::services);

        assertThat(actual, sameInstance(constructionFailure));
        assertThat(actual.getSuppressed(), arrayContaining(cleanupFailure));
        verify(model, times(1)).close();

        factory.preDestroy();
        verify(model, times(1)).close();
    }

    @Test
    void retriesInitializationAfterConstructionFailure() {
        var model = Mockito.mock(OciGenAiChatModel.class);
        var constructionFailure = new IllegalArgumentException("first construction failed");
        var factory = new RetryingModelFactory(ownedModelConfig(), model, constructionFailure);

        assertThat(assertThrows(IllegalArgumentException.class, factory::services), sameInstance(constructionFailure));
        assertThat(factory.services(), hasSize(1));
        assertThat(factory.createCount(), is(2));

        factory.preDestroy();
        verify(model, times(1)).close();
    }

    @Test
    void initializesOnlyOnceWithoutHoldingTheFactoryMonitor() throws Exception {
        var model = Mockito.mock(OciGenAiChatModel.class);
        var factory = new BlockingModelFactory(ownedModelConfig(), model);
        var start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                await(start);
                return factory.services();
            });
            var second = executor.submit(() -> {
                await(start);
                return factory.services();
            });

            synchronized (factory) {
                start.countDown();
                assertThat(factory.awaitConstruction(), is(true));
            }

            assertThat(factory.createCount(), is(1));
            factory.continueConstruction();

            var firstServices = first.get(10, TimeUnit.SECONDS);
            var secondServices = second.get(10, TimeUnit.SECONDS);
            assertThat(firstServices, sameInstance(secondServices));
            assertThat(firstServices, hasSize(1));
            assertThat(factory.createCount(), is(1));
        }

        factory.preDestroy();
        verify(model, times(1)).close();
    }

    @Test
    void shutdownWakesServicesWaiterAndClosesLateModel() throws Exception {
        var model = Mockito.mock(OciGenAiChatModel.class);
        var factory = new BlockingModelFactory(ownedModelConfig(), model);
        var servicesWaiterThread = new AtomicReference<Thread>();
        var servicesWaiterStarted = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(3)) {
            try {
                var services = executor.submit(factory::services);
                assertThat(factory.awaitConstruction(), is(true));
                var waitingServices = executor.submit(() -> {
                    servicesWaiterThread.set(Thread.currentThread());
                    servicesWaiterStarted.countDown();
                    try {
                        return factory.services();
                    } finally {
                        factory.continueConstruction();
                    }
                });

                assertThat(servicesWaiterStarted.await(10, TimeUnit.SECONDS), is(true));
                assertThat(awaitWaiting(servicesWaiterThread.get()), is(true));
                var shutdown = executor.submit(factory::preDestroy);

                assertThat(waitingServices.get(10, TimeUnit.SECONDS), is(empty()));
                assertThat(services.get(10, TimeUnit.SECONDS), is(empty()));
                shutdown.get(10, TimeUnit.SECONDS);
            } finally {
                factory.continueConstruction();
            }
        }

        assertThat(factory.services(), is(empty()));
        verify(model, times(1)).close();
        factory.preDestroy();
        verify(model, times(1)).close();
    }

    @Test
    void keepsOwnershipPrivateAndDerivedFromConfiguration(ServiceRegistry registry) {
        registry.get(GenerativeAiInferenceClient.class);
        var ownedModel = Mockito.mock(OciGenAiChatModel.class);
        var borrowedModel = Mockito.mock(OciGenAiChatModel.class);
        var ownedFactory = new FixedModelFactory(twoOwnedModelConfig(), ownedModel);
        var borrowedFactory = new FixedModelFactory(twoModelConfig(), borrowedModel);

        assertThat(ownedFactory.services(), hasSize(2));
        assertThat(borrowedFactory.services(), hasSize(2));

        ownedFactory.preDestroy();
        borrowedFactory.preDestroy();

        verify(ownedModel, times(1)).close();
        verify(borrowedModel, never()).close();
    }

    private static long closeInvocationCount(Object client) {
        return Mockito.mockingDetails(client).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("close"))
                .count();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for the test latch.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the test latch.", e);
        }
    }

    private static boolean awaitWaiting(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (thread.getState() == Thread.State.WAITING) {
                return true;
            }
            Thread.onSpinWait();
        }
        return false;
    }

    private static void assertClosed(OciGenAiChatModel model) {
        var failure = assertThrows(IllegalStateException.class, () -> model.chat("ignored"));
        assertThat(failure.getMessage(), is("OCI GenAI model is closed."));
    }

    private static Config ownedModelConfig() {
        // language=YAML
        var yaml = """
                langchain4j:
                  models:
                    owned:
                      provider: oci-gen-ai
                  providers:
                    oci-gen-ai:
                      model-name: model-name
                      compartment-id: compartment-id
                      region: us-ashburn-1
                      gen-ai-client-discover-services: false
                """;
        return Config.just(ConfigSources.create(yaml, MediaTypes.APPLICATION_X_YAML));
    }

    private static Config twoModelConfig() {
        // language=YAML
        var yaml = """
                langchain4j:
                  models:
                    first:
                      provider: oci-gen-ai
                    second:
                      provider: oci-gen-ai
                  providers:
                    oci-gen-ai:
                      model-name: model-name
                      compartment-id: compartment-id
                      region: us-ashburn-1
                """;
        return Config.just(ConfigSources.create(yaml, MediaTypes.APPLICATION_X_YAML));
    }

    private static Config twoOwnedModelConfig() {
        // language=YAML
        var yaml = """
                langchain4j:
                  models:
                    first:
                      provider: oci-gen-ai
                    second:
                      provider: oci-gen-ai
                  providers:
                    oci-gen-ai:
                      model-name: model-name
                      compartment-id: compartment-id
                      region: us-ashburn-1
                      gen-ai-client-discover-services: false
                """;
        return Config.just(ConfigSources.create(yaml, MediaTypes.APPLICATION_X_YAML));
    }

    private static Config mixedAuthAndAsyncClientConfig() {
        // language=YAML
        var yaml = """
                langchain4j:
                  models:
                    mixed:
                      provider: oci-gen-ai
                  providers:
                    oci-gen-ai:
                      model-name: model-name
                      compartment-id: compartment-id
                      region: us-ashburn-1
                      gen-ai-client-discover-services: false
                """;
        return Config.just(ConfigSources.create(yaml, MediaTypes.APPLICATION_X_YAML));
    }

    private static final class FailingModelFactory extends OciGenAiChatModelFactory {
        private final OciGenAiChatModel model;
        private final RuntimeException constructionFailure;
        private int buildCount;

        private FailingModelFactory(Config config, OciGenAiChatModel model, RuntimeException constructionFailure) {
            super(config);
            this.model = model;
            this.constructionFailure = constructionFailure;
        }

        @Override
        protected OciGenAiChatModel create(OciGenAiChatModelConfig config) {
            if (buildCount++ == 0) {
                return model;
            }
            throw constructionFailure;
        }
    }

    private static final class RetryingModelFactory extends OciGenAiChatModelFactory {
        private final OciGenAiChatModel model;
        private final RuntimeException constructionFailure;
        private final AtomicInteger createCount = new AtomicInteger();

        private RetryingModelFactory(Config config, OciGenAiChatModel model, RuntimeException constructionFailure) {
            super(config);
            this.model = model;
            this.constructionFailure = constructionFailure;
        }

        @Override
        protected OciGenAiChatModel create(OciGenAiChatModelConfig config) {
            if (createCount.getAndIncrement() == 0) {
                throw constructionFailure;
            }
            return model;
        }

        private int createCount() {
            return createCount.get();
        }
    }

    private static final class BlockingModelFactory extends OciGenAiChatModelFactory {
        private final OciGenAiChatModel model;
        private final CountDownLatch constructionStarted = new CountDownLatch(1);
        private final CountDownLatch continueConstruction = new CountDownLatch(1);
        private final AtomicInteger createCount = new AtomicInteger();

        private BlockingModelFactory(Config config, OciGenAiChatModel model) {
            super(config);
            this.model = model;
        }

        @Override
        protected OciGenAiChatModel create(OciGenAiChatModelConfig config) {
            createCount.incrementAndGet();
            constructionStarted.countDown();
            await(continueConstruction);
            return model;
        }

        private boolean awaitConstruction() throws InterruptedException {
            return constructionStarted.await(10, TimeUnit.SECONDS);
        }

        private void continueConstruction() {
            continueConstruction.countDown();
        }

        private int createCount() {
            return createCount.get();
        }
    }

    private static final class FixedModelFactory extends OciGenAiChatModelFactory {
        private final OciGenAiChatModel model;

        private FixedModelFactory(Config config, OciGenAiChatModel model) {
            super(config);
            this.model = model;
        }

        @Override
        protected OciGenAiChatModel create(OciGenAiChatModelConfig config) {
            return model;
        }
    }
}
