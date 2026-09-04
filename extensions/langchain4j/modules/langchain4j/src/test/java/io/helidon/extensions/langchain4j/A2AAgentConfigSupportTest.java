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

package io.helidon.extensions.langchain4j;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import io.helidon.common.media.type.MediaTypes;
import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.service.registry.ServiceRegistry;
import io.helidon.service.registry.ServiceRegistryManager;

import dev.langchain4j.agentic.declarative.A2AClientAgent;
import dev.langchain4j.agentic.declarative.A2AClientCustomizer;
import dev.langchain4j.agentic.declarative.A2AServerUrlSupplier;
import dev.langchain4j.agentic.declarative.AgentListenerSupplier;
import dev.langchain4j.agentic.declarative.SequenceAgent;
import dev.langchain4j.agentic.declarative.TypedKey;
import dev.langchain4j.agentic.internal.A2AClientBuilder;
import dev.langchain4j.agentic.internal.A2AService;
import dev.langchain4j.agentic.internal.AgentExecutor;
import dev.langchain4j.agentic.internal.InternalAgent;
import dev.langchain4j.agentic.observability.AgentListener;
import dev.langchain4j.agentic.planner.AgenticSystemConfigurationException;
import dev.langchain4j.service.V;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayContaining;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class A2AAgentConfigSupportTest {
    private static final AtomicBoolean CUSTOMIZED = new AtomicBoolean();
    private static final AgentListener LISTENER = new AgentListener() { };

    private A2AService previousService;
    private RecordingA2AService recordingService;

    @BeforeEach
    void setUp() {
        previousService = A2AService.get();
        recordingService = new RecordingA2AService();
        A2AService.setA2AService(recordingService);
        CUSTOMIZED.set(false);
    }

    @AfterEach
    void tearDown() {
        A2AService.setA2AService(previousService);
    }

    @Test
    void configurationOverridesAnnotationValues() {
        var config = AgentsConfig.builder()
                .a2aServerUrl("https://configured.example.test/a2a")
                .outputKey("configured-output")
                .async(false)
                .build();

        var agent = A2AAgentConfigSupport.create(AnnotatedAgent.class, config);

        assertThat(agent, is(notNullValue()));
        assertThat(recordingService.serverUrl, is("https://configured.example.test/a2a"));
        assertThat(recordingService.builder.inputKeys, arrayContaining("question"));
        assertThat(recordingService.builder.outputKey, is("configured-output"));
        assertThat(recordingService.builder.async, is(false));
        assertThat(recordingService.builder.listener, sameInstance(LISTENER));
        assertThat(CUSTOMIZED.get(), is(true));
    }

    @Test
    void annotationValuesAreFallbacks() {
        A2AAgentConfigSupport.create(AnnotatedAgent.class, AgentsConfig.create());

        assertThat(recordingService.serverUrl, is("https://annotation.example.test/a2a"));
        assertThat(recordingService.builder.outputKey, is("annotation-output"));
        assertThat(recordingService.builder.async, is(true));
    }

    @Test
    void supplierAndTypedOutputKeyAreFallbacks() {
        A2AAgentConfigSupport.create(SuppliedAgent.class, AgentsConfig.create());

        assertThat(recordingService.serverUrl, is("https://supplier.example.test/a2a"));
        assertThat(recordingService.builder.outputKey, is("TypedOutput"));
        assertThat(recordingService.builder.async, is(false));
    }

    @Test
    void readsA2AConfiguration() {
        // language=YAML
        String yaml = """
                a2a-server-url: https://configured.example.test/a2a
                output-key: configured-output
                async: true
                """;
        var config = AgentsConfig.create(Config.just(ConfigSources.create(yaml, MediaTypes.APPLICATION_X_YAML)));

        assertThat(config.a2aServerUrl().orElseThrow(), is("https://configured.example.test/a2a"));
        assertThat(config.outputKey().orElseThrow(), is("configured-output"));
        assertThat(config.async().orElseThrow(), is(true));
    }

    @Test
    void rejectsConflictingAnnotationSources() {
        var error = assertThrows(IllegalArgumentException.class,
                                 () -> A2AAgentConfigSupport.create(ConflictingAgent.class,
                                                                   AgentsConfig.builder()
                                                                           .a2aServerUrl("https://override.example.test")
                                                                           .build()));

        assertThat(error.getMessage(), containsString("not both"));
    }

    @Test
    void rejectsMissingAndInvalidUrls() {
        var missing = assertThrows(IllegalArgumentException.class,
                                   () -> A2AAgentConfigSupport.create(MissingUrlAgent.class, AgentsConfig.create()));
        assertThat(missing.getMessage(), containsString("requires"));

        var invalid = assertThrows(IllegalArgumentException.class,
                                   () -> A2AAgentConfigSupport.create(MissingUrlAgent.class,
                                                                     AgentsConfig.builder()
                                                                             .a2aServerUrl("file:///tmp/agent")
                                                                             .build()));
        assertThat(invalid.getMessage(), containsString("absolute HTTP or HTTPS URI"));

        var invalidPort = assertThrows(IllegalArgumentException.class,
                                       () -> A2AAgentConfigSupport.create(MissingUrlAgent.class,
                                                                         AgentsConfig.builder()
                                                                                 .a2aServerUrl("http://example.test:99999")
                                                                                 .build()));
        assertThat(invalidPort.getMessage(), containsString("absolute HTTP or HTTPS URI"));
    }

    @Test
    void configuredOutputDoesNotMaskInvalidAnnotationOutput() {
        var error = assertThrows(AgenticSystemConfigurationException.class,
                                 () -> A2AAgentConfigSupport.create(InvalidOutputAgent.class,
                                                                   AgentsConfig.builder()
                                                                           .outputKey("configured-output")
                                                                           .build()));

        assertThat(error.getMessage(), containsString("Both outputKey and typedOutputKey"));
    }

    @Test
    void reportsMissingA2AProvider() {
        A2AService.setA2AService(new MissingA2AService());

        var error = assertThrows(IllegalStateException.class,
                                 () -> A2AAgentConfigSupport.create(AnnotatedAgent.class, AgentsConfig.create()));

        assertThat(error.getMessage(), containsString("dev.langchain4j:langchain4j-agentic-a2a"));
    }

    @Test
    void generatedTopLevelA2AAgentDoesNotRequireChatModel() throws ReflectiveOperationException {
        var generatedType = Class.forName(getClass().getPackageName()
                                                  + ".A2AAgentConfigSupportTest_AnnotatedAgent__AiAgent");
        var constructor = generatedType.getDeclaredConstructors()[0];

        assertThat(Arrays.asList(constructor.getParameterTypes()), contains(Config.class, ServiceRegistry.class));
    }

    @Test
    void composedAgentTakesPrecedenceOverInheritedA2AAgent() throws ReflectiveOperationException {
        assertThat(A2AAgentConfigSupport.isA2A(ComposedAgent.class), is(false));

        var generatedType = Class.forName(getClass().getPackageName()
                                                  + ".A2AAgentConfigSupportTest_ComposedAgent__AiAgent");
        var constructor = generatedType.getDeclaredConstructors()[0];

        assertThat(Arrays.asList(constructor.getParameterTypes()),
                   contains(Config.class, ServiceRegistry.class, Optional.class));
    }

    @Test
    void unannotatedA2ASubAgentFallsThroughToLangChain4j() throws ReflectiveOperationException {
        var manager = ServiceRegistryManager.create();
        try {
            var generatedType = Class.forName(getClass().getPackageName()
                                                      + ".A2AAgentConfigSupportTest_UnannotatedWorkflow__AiAgent");
            var constructor = generatedType.getDeclaredConstructor(Config.class, ServiceRegistry.class, Optional.class);
            constructor.setAccessible(true);
            var supplier = constructor.newInstance(Config.empty(), manager.registry(), Optional.empty());
            var resolver = generatedType.getDeclaredMethod("resolveSubAgent", Class.class);
            resolver.setAccessible(true);

            assertThat(resolver.invoke(supplier, UnannotatedAgent.class), is(nullValue()));
        } finally {
            manager.shutdown();
        }
    }

    @Ai.Agent("annotated-a2a")
    public interface AnnotatedAgent {
        @A2AClientAgent(a2aServerUrl = "https://annotation.example.test/a2a",
                        outputKey = "annotation-output",
                        async = true)
        String ask(@V("question") String question);

        @A2AClientCustomizer
        static void customize(Object ignored) {
            CUSTOMIZED.set(true);
        }

        @AgentListenerSupplier
        static AgentListener listener() {
            return LISTENER;
        }
    }

    public interface SuppliedAgent {
        @A2AClientAgent(typedOutputKey = TypedOutput.class)
        String ask(@V("question") String question);

        @A2AServerUrlSupplier
        static String serverUrl() {
            return "https://supplier.example.test/a2a";
        }
    }

    public interface ConflictingAgent {
        @A2AClientAgent(a2aServerUrl = "https://annotation.example.test/a2a")
        String ask(@V("question") String question);

        @A2AServerUrlSupplier
        static String serverUrl() {
            return "https://supplier.example.test/a2a";
        }
    }

    public interface MissingUrlAgent {
        @A2AClientAgent
        String ask(@V("question") String question);
    }

    public interface InvalidOutputAgent {
        @A2AClientAgent(a2aServerUrl = "https://annotation.example.test/a2a",
                outputKey = "annotation-output",
                typedOutputKey = TypedOutput.class)
        String ask(@V("question") String question);
    }

    @Ai.Agent("composed-agent")
    public interface ComposedAgent extends AnnotatedAgent {
        @SequenceAgent(subAgents = AnnotatedAgent.class)
        String compose(@V("question") String question);
    }

    public interface UnannotatedAgent {
        @A2AClientAgent(a2aServerUrl = "https://annotation.example.test/a2a")
        String ask(@V("question") String question);
    }

    @Ai.Agent("unannotated-workflow")
    public interface UnannotatedWorkflow {
        @SequenceAgent(subAgents = UnannotatedAgent.class)
        String compose(@V("question") String question);
    }

    public static class TypedOutput implements TypedKey<String> {
    }

    private static final class RecordingA2AService implements A2AService {
        private String serverUrl;
        private RecordingA2AClientBuilder<?> builder;

        @Override
        public <T> A2AClientBuilder<T> a2aBuilder(String serverUrl, Class<T> agentServiceClass) {
            this.serverUrl = serverUrl;
            var builder = new RecordingA2AClientBuilder<>(agentServiceClass);
            this.builder = builder;
            return builder;
        }

        @Override
        public Optional<AgentExecutor> methodToAgentExecutor(InternalAgent agent, Method method) {
            return Optional.empty();
        }
    }

    private static final class MissingA2AService implements A2AService {
        @Override
        public <T> A2AClientBuilder<T> a2aBuilder(String serverUrl, Class<T> agentServiceClass) {
            throw new UnsupportedOperationException("No A2A service implementation found");
        }

        @Override
        public Optional<AgentExecutor> methodToAgentExecutor(InternalAgent agent, Method method) {
            return Optional.empty();
        }
    }

    private static final class RecordingA2AClientBuilder<T> implements A2AClientBuilder<T> {
        private final Class<T> agentType;
        private String[] inputKeys;
        private String outputKey;
        private boolean async;
        private AgentListener listener;
        private Consumer<?> customizer;

        private RecordingA2AClientBuilder(Class<T> agentType) {
            this.agentType = agentType;
        }

        @Override
        public A2AClientBuilder<T> inputKeys(String... inputKeys) {
            this.inputKeys = inputKeys;
            return this;
        }

        @Override
        public A2AClientBuilder<T> outputKey(String outputKey) {
            this.outputKey = outputKey;
            return this;
        }

        @Override
        public A2AClientBuilder<T> async(boolean async) {
            this.async = async;
            return this;
        }

        @Override
        public A2AClientBuilder<T> listener(AgentListener listener) {
            this.listener = listener;
            return this;
        }

        @Override
        public A2AClientBuilder<T> clientCustomizer(Consumer<?> customizer) {
            this.customizer = customizer;
            return this;
        }

        @Override
        @SuppressWarnings({"rawtypes", "unchecked"})
        public T build() {
            if (customizer != null) {
                ((Consumer) customizer).accept(new Object());
            }
            return agentType.cast(Proxy.newProxyInstance(agentType.getClassLoader(),
                                                         new Class<?>[] {agentType},
                                                         (proxy, method, args) -> null));
        }
    }
}
