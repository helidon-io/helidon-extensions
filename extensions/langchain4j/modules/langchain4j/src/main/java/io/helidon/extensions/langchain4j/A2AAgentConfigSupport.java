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

import java.net.URI;
import java.util.Locale;
import java.util.stream.Stream;

import io.helidon.common.Api;

import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.declarative.A2AClientAgent;
import dev.langchain4j.agentic.declarative.A2AClientCustomizer;
import dev.langchain4j.agentic.declarative.A2AServerUrlSupplier;
import dev.langchain4j.agentic.declarative.AgentListenerSupplier;
import dev.langchain4j.agentic.declarative.ConditionalAgent;
import dev.langchain4j.agentic.declarative.HumanInTheLoop;
import dev.langchain4j.agentic.declarative.LoopAgent;
import dev.langchain4j.agentic.declarative.ParallelAgent;
import dev.langchain4j.agentic.declarative.ParallelMapperAgent;
import dev.langchain4j.agentic.declarative.PlannerAgent;
import dev.langchain4j.agentic.declarative.SequenceAgent;
import dev.langchain4j.agentic.declarative.SupervisorAgent;
import dev.langchain4j.agentic.internal.A2AClientBuilder;
import dev.langchain4j.agentic.internal.AgentInvoker;
import dev.langchain4j.agentic.internal.AgentUtil;
import dev.langchain4j.agentic.observability.AgentListener;

import static dev.langchain4j.agentic.declarative.DeclarativeUtil.checkReturnType;
import static dev.langchain4j.agentic.declarative.DeclarativeUtil.invokeStatic;
import static dev.langchain4j.agentic.declarative.DeclarativeUtil.selectMethod;
import static dev.langchain4j.agentic.internal.AgentUtil.getAnnotatedMethodOnClass;

/**
 * Runtime support for applying Helidon configuration to LangChain4j A2A client agents.
 */
@Api.Internal
public final class A2AAgentConfigSupport {
    private static final String A2A_DEPENDENCY = "dev.langchain4j:langchain4j-agentic-a2a";

    private A2AAgentConfigSupport() {
    }

    /**
     * Whether the type declares an A2A client agent method.
     *
     * @param agentType agent service type
     * @return whether the type is an A2A client agent
     */
    public static boolean isA2A(Class<?> agentType) {
        return getAnnotatedMethodOnClass(agentType, A2AClientAgent.class).isPresent()
                && Stream.of(SequenceAgent.class,
                             LoopAgent.class,
                             ConditionalAgent.class,
                             ParallelAgent.class,
                             ParallelMapperAgent.class,
                             SupervisorAgent.class,
                             PlannerAgent.class,
                             HumanInTheLoop.class)
                .noneMatch(annotation -> getAnnotatedMethodOnClass(agentType, annotation).isPresent());
    }

    /**
     * Creates an A2A client agent, applying Helidon configuration over annotation defaults.
     *
     * @param agentType agent service type
     * @param config agent configuration
     * @param <T> agent service type
     * @return configured A2A client agent
     */
    public static <T> T create(Class<T> agentType, AgentsConfig config) {
        var agentMethod = getAnnotatedMethodOnClass(agentType, A2AClientAgent.class)
                .orElseThrow(() -> new IllegalArgumentException("Type " + agentType.getName()
                                                                         + " is not an A2A client agent"));
        var annotation = agentMethod.getAnnotation(A2AClientAgent.class);
        String serverUrl = serverUrl(agentType, annotation, config);

        A2AClientBuilder<T> builder;
        try {
            builder = AgenticServices.a2aBuilder(serverUrl, agentType);
        } catch (UnsupportedOperationException e) {
            throw new IllegalStateException("Cannot create A2A agent " + agentType.getName()
                                                    + ": add " + A2A_DEPENDENCY + " to the runtime dependencies",
                                            e);
        }
        String annotationOutputKey = AgentUtil.outputKey(annotation.outputKey(), annotation.typedOutputKey());

        builder.inputKeys(Stream.of(agentMethod.getParameters())
                                  .map(AgentInvoker::parameterName)
                                  .toArray(String[]::new))
                .outputKey(config.outputKey()
                                   .map(outputKey -> configuredOutputKey(agentType, outputKey))
                                   .orElse(annotationOutputKey))
                .async(config.async().orElse(annotation.async()));

        selectMethod(agentType,
                     method -> method.isAnnotationPresent(A2AClientCustomizer.class)
                             && method.getParameterCount() == 1)
                .ifPresent(method -> builder.clientCustomizer(clientBuilder -> invokeStatic(method, clientBuilder)));

        getAnnotatedMethodOnClass(agentType, AgentListenerSupplier.class)
                .ifPresent(method -> {
                    checkReturnType(method, AgentListener.class);
                    builder.listener(invokeStatic(method));
                });

        return builder.build();
    }

    private static String serverUrl(Class<?> agentType, A2AClientAgent annotation, AgentsConfig config) {
        String annotationUrl = annotation.a2aServerUrl();
        var supplierMethod = selectMethod(agentType,
                                          method -> method.isAnnotationPresent(A2AServerUrlSupplier.class)
                                                  && method.getParameterCount() == 0);

        if (!annotationUrl.isBlank() && supplierMethod.isPresent()) {
            throw new IllegalArgumentException("Provide either a2aServerUrl in the @A2AClientAgent annotation or an "
                                                       + "@A2AServerUrlSupplier method, not both.");
        }

        if (config.a2aServerUrl().isPresent()) {
            return validateServerUrl(agentType, config.a2aServerUrl().get());
        }
        if (!annotationUrl.isBlank()) {
            return validateServerUrl(agentType, annotationUrl);
        }
        if (supplierMethod.isPresent()) {
            checkReturnType(supplierMethod.get(), String.class);
            return validateServerUrl(agentType, invokeStatic(supplierMethod.get()));
        }

        throw new IllegalArgumentException("A2A agent " + agentType.getName()
                                                   + " requires langchain4j.agents.<agent-name>.a2a-server-url, "
                                                   + "@A2AClientAgent.a2aServerUrl, or @A2AServerUrlSupplier");
    }

    private static String configuredOutputKey(Class<?> agentType, String outputKey) {
        if (outputKey.isBlank()) {
            throw new IllegalArgumentException("Configured output-key for A2A agent " + agentType.getName()
                                                       + " must not be blank");
        }
        return outputKey;
    }

    private static String validateServerUrl(Class<?> agentType, String serverUrl) {
        if (serverUrl == null || serverUrl.isBlank()) {
            throw invalidServerUrl(agentType);
        }

        URI uri;
        try {
            uri = URI.create(serverUrl);
        } catch (IllegalArgumentException e) {
            throw invalidServerUrl(agentType);
        }

        String scheme = uri.getScheme();
        if (!uri.isAbsolute()
                || scheme == null
                || !(scheme.toLowerCase(Locale.ROOT).equals("http")
                || scheme.toLowerCase(Locale.ROOT).equals("https"))
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getFragment() != null
                || uri.getPort() > 65535) {
            throw invalidServerUrl(agentType);
        }
        return serverUrl;
    }

    private static IllegalArgumentException invalidServerUrl(Class<?> agentType) {
        return new IllegalArgumentException("A2A server URL for agent " + agentType.getName()
                                                    + " must be an absolute HTTP or HTTPS URI without user info or fragment");
    }
}
