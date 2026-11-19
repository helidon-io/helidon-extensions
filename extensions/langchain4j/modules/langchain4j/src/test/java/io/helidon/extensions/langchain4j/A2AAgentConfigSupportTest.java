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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;

import io.helidon.common.media.type.MediaTypes;
import io.helidon.config.Config;
import io.helidon.config.ConfigMappingException;
import io.helidon.config.ConfigSources;
import io.helidon.service.registry.ServiceRegistryConfig;
import io.helidon.service.registry.ServiceRegistryManager;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.declarative.A2AClientAgent;
import dev.langchain4j.agentic.declarative.A2AClientCustomizer;
import dev.langchain4j.agentic.declarative.A2AServerUrlSupplier;
import dev.langchain4j.agentic.declarative.AgentListenerSupplier;
import dev.langchain4j.agentic.declarative.HumanInTheLoop;
import dev.langchain4j.agentic.declarative.SequenceAgent;
import dev.langchain4j.agentic.declarative.TypedKey;
import dev.langchain4j.agentic.internal.A2AClientBuilder;
import dev.langchain4j.agentic.internal.A2AService;
import dev.langchain4j.agentic.internal.AgentExecutor;
import dev.langchain4j.agentic.internal.InternalAgent;
import dev.langchain4j.agentic.observability.A2AStreamingClientListener;
import dev.langchain4j.agentic.observability.AgentListener;
import dev.langchain4j.agentic.planner.AgenticSystemConfigurationException;
import dev.langchain4j.agentic.scope.AgenticScope;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayContaining;
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

        var agent = config.createA2AAgent(AnnotatedAgent.class);

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
        AgentsConfig.create().createA2AAgent(AnnotatedAgent.class);

        assertThat(recordingService.serverUrl, is("a2a+test:annotation"));
        assertThat(recordingService.builder.outputKey, is("annotation-output"));
        assertThat(recordingService.builder.async, is(true));
    }

    @Test
    void supplierAndTypedOutputKeyAreFallbacks() {
        AgentsConfig.create().createA2AAgent(SuppliedAgent.class);

        assertThat(recordingService.serverUrl, is("urn:a2a:supplied"));
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
                                 () -> AgentsConfig.builder()
                                         .a2aServerUrl("https://override.example.test")
                                         .build()
                                         .createA2AAgent(ConflictingAgent.class));

        assertThat(error.getMessage(), containsString("not both"));
    }

    @Test
    void rejectsMissingAndInvalidUrls() {
        var missing = assertThrows(IllegalArgumentException.class,
                                   () -> AgentsConfig.create().createA2AAgent(MissingUrlAgent.class));
        assertThat(missing.getMessage(), containsString("requires"));

        var invalid = assertThrows(IllegalArgumentException.class,
                                   () -> AgentsConfig.builder()
                                           .a2aServerUrl("a2a+test:configured")
                                           .build()
                                           .createA2AAgent(MissingUrlAgent.class));
        assertThat(invalid.getMessage(), containsString("absolute HTTP or HTTPS URI"));

        var invalidPort = assertThrows(IllegalArgumentException.class,
                                       () -> AgentsConfig.builder()
                                               .a2aServerUrl("http://example.test:99999")
                                               .build()
                                               .createA2AAgent(MissingUrlAgent.class));
        assertThat(invalidPort.getMessage(), containsString("absolute HTTP or HTTPS URI"));
    }

    @Test
    void configuredOutputDoesNotMaskInvalidAnnotationOutput() {
        var error = assertThrows(AgenticSystemConfigurationException.class,
                                 () -> AgentsConfig.builder()
                                         .outputKey("configured-output")
                                         .build()
                                         .createA2AAgent(InvalidOutputAgent.class));

        assertThat(error.getMessage(), containsString("Both outputKey and typedOutputKey"));
    }

    @Test
    void reportsMissingA2AProvider() {
        A2AService.setA2AService(new MissingA2AService());

        var error = assertThrows(IllegalStateException.class,
                                 () -> AgentsConfig.create().createA2AAgent(AnnotatedAgent.class));

        assertThat(error.getMessage(), containsString("dev.langchain4j:langchain4j-agentic-a2a"));
    }

    @Test
    void rejectsNullAgentType() {
        var error = assertThrows(NullPointerException.class,
                                 () -> AgentsConfig.create().createA2AAgent(null));

        assertThat(error.getMessage(), is("agentType"));
        assertThat(recordingService.builder, is(nullValue()));
    }

    @Test
    void createsTopLevelA2AAgentThroughRegistryWithoutChatModel() {
        var manager = registryManager(Config.empty());
        try {
            var agent = manager.registry().get(HumanAndA2AAgent.class);

            assertThat(agent, is(notNullValue()));
            assertThat(recordingService.builder.agentType.getName(), is(HumanAndA2AAgent.class.getName()));
            assertThat(recordingService.serverUrl, is("https://human-a2a.example.test"));
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void disabledTopLevelA2AAgentDoesNotStartDiscovery() {
        var manager = registryManager(disabledHumanAgentConfig());
        try {
            var error = assertThrows(IllegalStateException.class,
                                     () -> manager.registry().get(HumanAndA2AAgent.class));

            assertThat(error.getMessage(), containsString("disabled"));
            assertThat(recordingService.builder, is(nullValue()));
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void composedAgentTakesPrecedenceOverInheritedA2AAgent() {
        var invalidConfig = invalidHumanAgentConfig();
        assertThrows(ConfigMappingException.class,
                     () -> AgentsConfig.create(invalidConfig.get("langchain4j.agents.human-a2a")));

        var manager = registryManager(invalidConfig);
        try {
            var agent = manager.registry().get(ComposedAgent.class);

            assertThat(agent, is(notNullValue()));
            assertThat(recordingService.builder, is(nullValue()));
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void overriddenA2AAnnotationUsesLocalAgent() {
        var chatModel = new RecordingChatModel();
        var manager = registryManager(Config.empty(), chatModel);
        try {
            var agent = manager.registry().get(OverrideShadowingAgent.class);

            assertThat(agent.ask("local question"), is("local-model-response"));
            assertThat(chatModel.invocations, is(1));
            assertThat(recordingService.builder, is(nullValue()));
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void disabledNestedA2AAgentDoesNotStartDiscovery() {
        var manager = registryManager(disabledAnnotatedAgentConfig());
        try {
            var error = assertThrows(IllegalStateException.class,
                                     () -> manager.registry().get(NestedA2AWorkflow.class));

            assertThat(error.getMessage(), containsString("disabled"));
            assertThat(recordingService.builder, is(nullValue()));
        } finally {
            manager.shutdown();
        }
    }

    private static ServiceRegistryManager registryManager(Config config) {
        return ServiceRegistryManager.create(ServiceRegistryConfig.builder()
                                                     .putContractInstance(Config.class, config)
                                                     .build());
    }

    private static ServiceRegistryManager registryManager(Config config, ChatModel chatModel) {
        return ServiceRegistryManager.create(ServiceRegistryConfig.builder()
                                                     .putContractInstance(Config.class, config)
                                                     .putContractInstance(ChatModel.class, chatModel)
                                                     .build());
    }

    private static Config disabledAnnotatedAgentConfig() {
        // language=YAML
        String yaml = """
                langchain4j:
                  agents:
                    annotated-a2a:
                      enabled: false
                """;
        return Config.just(ConfigSources.create(yaml, MediaTypes.APPLICATION_X_YAML));
    }

    private static Config disabledHumanAgentConfig() {
        // language=YAML
        String yaml = """
                langchain4j:
                  agents:
                    human-a2a:
                      enabled: false
                """;
        return Config.just(ConfigSources.create(yaml, MediaTypes.APPLICATION_X_YAML));
    }

    private static Config invalidHumanAgentConfig() {
        // language=YAML
        String yaml = """
                langchain4j:
                  agents:
                    human-a2a:
                      tools:
                        - missing.test.Tool
                """;
        return Config.just(ConfigSources.create(yaml, MediaTypes.APPLICATION_X_YAML));
    }

    @Ai.Agent("annotated-a2a")
    public interface AnnotatedAgent {
        @A2AClientCustomizer
        static void customize(Object ignored) {
            CUSTOMIZED.set(true);
        }

        @AgentListenerSupplier
        static AgentListener listener() {
            return LISTENER;
        }

        @A2AClientAgent(a2aServerUrl = "a2a+test:annotation",
                        outputKey = "annotation-output",
                        async = true)
        String ask(@V("question") String question);
    }

    public interface SuppliedAgent {
        @A2AServerUrlSupplier
        static String serverUrl() {
            return "urn:a2a:supplied";
        }

        @A2AClientAgent(typedOutputKey = TypedOutput.class)
        String ask(@V("question") String question);
    }

    public interface ConflictingAgent {
        @A2AServerUrlSupplier
        static String serverUrl() {
            return "https://supplier.example.test/a2a";
        }

        @A2AClientAgent(a2aServerUrl = "https://annotation.example.test/a2a")
        String ask(@V("question") String question);
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
        @SequenceAgent(subAgents = HumanAndA2AAgent.class)
        String compose(@V("question") String question);
    }

    @Ai.Agent("human-a2a")
    public interface HumanAndA2AAgent {
        @HumanInTheLoop
        static String review(@V("question") String question) {
            return question;
        }

        @A2AClientAgent(a2aServerUrl = "https://human-a2a.example.test")
        String ask(@V("question") String question);
    }

    @Ai.Agent("nested-a2a-workflow")
    public interface NestedA2AWorkflow {
        @SequenceAgent(subAgents = AnnotatedAgent.class)
        String compose(@V("question") String question);
    }

    public interface ParentA2AAgent {
        @A2AClientAgent(a2aServerUrl = "a2a+test:shadowed-parent")
        String ask(@V("question") String question);
    }

    @Ai.Agent("override-shadowing-agent")
    public interface OverrideShadowingAgent extends ParentA2AAgent {
        @Override
        @Agent(description = "Local override", outputKey = "answer")
        @UserMessage("{{question}}")
        String ask(@V("question") String question);
    }

    public static class TypedOutput implements TypedKey<String> {
    }

    private static final class RecordingChatModel implements ChatModel {
        private int invocations;

        @Override
        public ChatResponse doChat(ChatRequest request) {
            invocations++;
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from("local-model-response"))
                    .build();
        }
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
        public A2AClientBuilder<T> activationCondition(String description, Predicate<AgenticScope> condition) {
            return this;
        }

        @Override
        public A2AClientBuilder<T> clientCustomizer(Consumer<?> customizer) {
            this.customizer = customizer;
            return this;
        }

        @Override
        public A2AClientBuilder<T> streamingClientListener(A2AStreamingClientListener<?> listener) {
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
