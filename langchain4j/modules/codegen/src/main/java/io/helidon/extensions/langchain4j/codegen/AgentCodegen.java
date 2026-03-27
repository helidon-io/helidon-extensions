/*
 * Copyright (c) 2025, 2026 Oracle and/or its affiliates.
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

package io.helidon.extensions.langchain4j.codegen;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Stream;

import io.helidon.codegen.CodegenException;
import io.helidon.codegen.CodegenUtil;
import io.helidon.codegen.RoundContext;
import io.helidon.codegen.classmodel.ClassModel;
import io.helidon.codegen.classmodel.Constructor;
import io.helidon.codegen.classmodel.Method;
import io.helidon.codegen.classmodel.Parameter;
import io.helidon.codegen.spi.CodegenExtension;
import io.helidon.common.types.AccessModifier;
import io.helidon.common.types.Annotation;
import io.helidon.common.types.Annotations;
import io.helidon.common.types.ElementKind;
import io.helidon.common.types.TypeInfo;
import io.helidon.common.types.TypeName;
import io.helidon.common.types.TypeNames;
import io.helidon.common.types.TypedElementInfo;
import io.helidon.service.codegen.ServiceCodegenTypes;

import static io.helidon.common.types.AccessModifier.PACKAGE_PRIVATE;
import static io.helidon.common.types.AccessModifier.PRIVATE;
import static io.helidon.common.types.TypeNames.CLASS_WILDCARD;
import static io.helidon.common.types.TypeNames.PRIMITIVE_BOOLEAN;
import static io.helidon.common.types.TypeNames.STRING;
import static io.helidon.extensions.langchain4j.codegen.LangchainTypes.AGENTS_CONFIG;
import static io.helidon.extensions.langchain4j.codegen.LangchainTypes.AGENT_METADATA;
import static io.helidon.extensions.langchain4j.codegen.LangchainTypes.AI_AGENT;
import static io.helidon.extensions.langchain4j.codegen.LangchainTypes.AI_CHAT_MODEL;
import static io.helidon.extensions.langchain4j.codegen.LangchainTypes.AI_STREAMING_CHAT_MODEL;
import static io.helidon.extensions.langchain4j.codegen.LangchainTypes.CONFIG;
import static io.helidon.extensions.langchain4j.codegen.LangchainTypes.LC_A2A_CLIENT_AGENT;
import static io.helidon.extensions.langchain4j.codegen.LangchainTypes.LC_AGENTIC_AGENT;
import static io.helidon.extensions.langchain4j.codegen.LangchainTypes.LC_AGENTIC_SERVICES;
import static io.helidon.extensions.langchain4j.codegen.LangchainTypes.LC_CHAT_MODEL;
import static io.helidon.extensions.langchain4j.codegen.LangchainTypes.LC_CONDITIONAL_AGENT;
import static io.helidon.extensions.langchain4j.codegen.LangchainTypes.LC_DECLARATIVE_AGENT_CREATION_CONTEXT;
import static io.helidon.extensions.langchain4j.codegen.LangchainTypes.LC_HUMAN_IN_THE_LOOP;
import static io.helidon.extensions.langchain4j.codegen.LangchainTypes.LC_LOOP_AGENT;
import static io.helidon.extensions.langchain4j.codegen.LangchainTypes.LC_MCP_CLIENT_AGENT;
import static io.helidon.extensions.langchain4j.codegen.LangchainTypes.LC_PARALLEL_AGENT;
import static io.helidon.extensions.langchain4j.codegen.LangchainTypes.LC_PARALLEL_MAPPER_AGENT;
import static io.helidon.extensions.langchain4j.codegen.LangchainTypes.LC_PLANNER_AGENT;
import static io.helidon.extensions.langchain4j.codegen.LangchainTypes.LC_SEQUENCE_AGENT;
import static io.helidon.extensions.langchain4j.codegen.LangchainTypes.LC_STREAMING_CHAT_MODEL;
import static io.helidon.extensions.langchain4j.codegen.LangchainTypes.LC_SUPERVISOR_AGENT;
import static io.helidon.service.codegen.ServiceCodegenTypes.SERVICE_ANNOTATION_NAMED;
import static io.helidon.service.codegen.ServiceCodegenTypes.SERVICE_ANNOTATION_SINGLETON;
import static io.helidon.service.codegen.ServiceCodegenTypes.SERVICE_QUALIFIER;
import static io.helidon.service.codegen.ServiceCodegenTypes.SERVICE_REGISTRY;

class AgentCodegen implements CodegenExtension {
    private static final TypeName GENERATOR = TypeName.create(AgentCodegen.class);
    private static final Set<TypeName> AGENT_METHOD_ANNOTATIONS = Set.of(
            LC_AGENTIC_AGENT,
            LC_SEQUENCE_AGENT,
            LC_CONDITIONAL_AGENT,
            LC_LOOP_AGENT,
            LC_PARALLEL_AGENT,
            LC_PARALLEL_MAPPER_AGENT,
            LC_PLANNER_AGENT,
            LC_SUPERVISOR_AGENT,
            LC_HUMAN_IN_THE_LOOP,
            LC_A2A_CLIENT_AGENT,
            LC_MCP_CLIENT_AGENT
    );
    private static final String CHAT_MODEL_CONFIG_KEY = "chat-model";
    private static final String STREAMING_CHAT_MODEL_CONFIG_KEY = "streaming-chat-model";

    static final String AGENTS_CONFIG_KEY = "langchain4j.agents";

    @Override
    public void process(RoundContext roundCtx) {
        Collection<TypeInfo> types = roundCtx.annotatedTypes(AI_AGENT);
        Set<TypeName> chatModelRequiredTypes = chatModelRequiredTypes(types);

        validateAgentTypes(types);
        generateAgentSuppliers(roundCtx, types, chatModelRequiredTypes);
    }

    private void validateAgentTypes(Collection<TypeInfo> types) {
        for (TypeInfo type : types) {
            if (type.kind() != ElementKind.INTERFACE) {
                throw new CodegenException("Type annotated with " + AI_AGENT.fqName() + " must be an interface.",
                                           type.originatingElementValue());
            }
            if (type.hasAnnotation(AI_CHAT_MODEL) && type.hasAnnotation(AI_STREAMING_CHAT_MODEL)) {
                throw new CodegenException("Type annotated with " + AI_AGENT.fqName()
                                                   + " cannot use both @Ai.ChatModel and @Ai.StreamingChatModel.",
                                           type.originatingElementValue());
            }
            type.elementInfo()
                    .stream()
                    .filter(it -> it.kind() == ElementKind.METHOD)
                    .forEach(method -> validateAgentMethod(type, method));
        }
    }

    private void validateAgentMethod(TypeInfo type, TypedElementInfo method) {
        if (isAgentMethod(method) && isStringStream(method.typeName())) {
            throw new CodegenException("Agent method "
                                               + type.typeName().fqName()
                                               + "#"
                                               + method.elementName()
                                               + " must return dev.langchain4j.service.TokenStream instead of Stream<String>.",
                                       type.originatingElementValue());
        }
    }

    private void generateAgentSuppliers(RoundContext roundCtx,
                                        Collection<TypeInfo> types,
                                        Set<TypeName> chatModelRequiredTypes) {
        for (TypeInfo type : types) {
            generateAgentSupplier(roundCtx, type, chatModelRequiredTypes);
        }
    }

    private void generateAgentSupplier(RoundContext roundCtx,
                                       TypeInfo agentInterface,
                                       Set<TypeName> chatModelRequiredTypes) {
        AgentInfo agent = agentInfo(agentInterface);
        AgentMetadataSupplierBuilder.build(agentInterface, roundCtx);

        var classModel = ClassModel.builder()
                .type(agent.generatedType())
                .copyright(CodegenUtil.copyright(GENERATOR,
                                                 agent.agentInterfaceType(),
                                                 agent.generatedType()))
                .addAnnotation(CodegenUtil.generatedAnnotation(GENERATOR,
                                                               agent.agentInterfaceType(),
                                                               agent.generatedType(),
                                                               "1",
                                                               ""))
                .accessModifier(AccessModifier.PACKAGE_PRIVATE)
                .addInterface(supplierType(agent.agentInterfaceType()))
                .addAnnotation(Annotation.create(SERVICE_ANNOTATION_SINGLETON));

        addStateFields(classModel);
        addConstructor(classModel, agentInterface);
        classModel.addMethod(method -> addGetMethod(method, agent));
        classModel.addMethod(method -> addRequiresChatModelMethod(method, chatModelRequiredTypes));
        classModel.addMethod(this::addValidateChatModelSupportMethod);
        classModel.addMethod(method -> addConfigureSubAgentsMethod(method, agent));

        roundCtx.addGeneratedType(agent.generatedType(),
                                  classModel,
                                  agent.agentInterfaceType(),
                                  agentInterface.originatingElementValue());
    }

    private AgentInfo agentInfo(TypeInfo agentInterface) {
        TypeName agentInterfaceType = agentInterface.typeName();
        return new AgentInfo(agentInterface.typeName(),
                             generatedTypeName(agentInterfaceType, "AiAgent"),
                             agentInterface.annotation(AI_AGENT).stringValue().orElseThrow(),
                             agentInterface.hasAnnotation(AI_CHAT_MODEL),
                             agentInterface.hasAnnotation(AI_STREAMING_CHAT_MODEL));
    }

    private void addStateFields(ClassModel.Builder classModel) {
        classModel.addField(aiServices -> aiServices
                .name("agenticConfig")
                .type(CONFIG)
                .isFinal(true)
                .accessModifier(PRIVATE)
        );

        classModel.addField(aiServices -> aiServices
                .name("registry")
                .type(SERVICE_REGISTRY)
                .isFinal(true)
                .accessModifier(PRIVATE)
        );

        classModel.addField(aiServices -> aiServices
                .name("chatModel")
                .type(LC_CHAT_MODEL)
                .isFinal(true)
                .accessModifier(PRIVATE)
        );

        classModel.addField(aiServices -> aiServices
                .name("streamingChatModel")
                .type(LC_STREAMING_CHAT_MODEL)
                .isFinal(true)
                .accessModifier(PRIVATE)
        );
    }

    private void addConstructor(ClassModel.Builder classModel, TypeInfo agentInterface) {
        classModel.addConstructor(ctr -> ctr
                .accessModifier(AccessModifier.PACKAGE_PRIVATE)
                .addAnnotation(Annotation.create(ServiceCodegenTypes.SERVICE_ANNOTATION_INJECT))
                .addParameter(Parameter.builder()
                                      .type(CONFIG)
                                      .name("config")
                                      .build())
                .addParameter(Parameter.builder()
                                      .type(SERVICE_REGISTRY)
                                      .name("registry")
                                      .build())
                .addContent("this.agenticConfig = config.get(")
                .addContentLiteral(AGENTS_CONFIG_KEY)
                .addContentLine(");")
                .addContentLine("this.registry = registry;")
                .update(it -> {
                    aiAgentsParameter(it,
                                      true,
                                      agentInterface,
                                      AI_CHAT_MODEL,
                                      LC_CHAT_MODEL,
                                      "chatModel",
                                      "chatModel");
                    aiAgentsParameter(it,
                                      true,
                                      agentInterface,
                                      AI_STREAMING_CHAT_MODEL,
                                      LC_STREAMING_CHAT_MODEL,
                                      "streamingChatModel",
                                      "streamingChatModel");
                })
        );
    }

    private void addGetMethod(Method.Builder method, AgentInfo agent) {
        method.accessModifier(AccessModifier.PUBLIC)
                .addAnnotation(Annotations.OVERRIDE)
                .returnType(agent.agentInterfaceType())
                .name("get")
                .addContent("var topAgentConfig = this.agenticConfig.get(")
                .addContentLiteral(agent.agentName())
                .addContentLine(");")
                .addContent("boolean chatModelConfigured = topAgentConfig.get(")
                .addContentLiteral(CHAT_MODEL_CONFIG_KEY)
                .addContentLine(").exists();")
                .addContent("boolean streamingChatModelConfigured = topAgentConfig.get(")
                .addContentLiteral(STREAMING_CHAT_MODEL_CONFIG_KEY)
                .addContentLine(").exists();")
                .addContentLine("if (chatModelConfigured && streamingChatModelConfigured) {")
                .increaseContentPadding()
                .addContent("throw new ")
                .addContent(IllegalStateException.class)
                .addContent("(")
                .addContentLiteral("Both chat-model and streaming-chat-model are configured for agent "
                                           + agent.agentName()
                                           + ".")
                .addContentLine(");")
                .decreaseContentPadding()
                .addContentLine("}")
                .addContent("var configuredChatModel = topAgentConfig")
                .addContent(".get(")
                .addContentLiteral(CHAT_MODEL_CONFIG_KEY)
                .addContentLine(")")
                .increaseContentPadding()
                .addContentLine(".asString()")
                .addContent(".map(n -> registry.getNamed(")
                .addContent(LC_CHAT_MODEL)
                .addContentLine(".class, n))")
                .addContentLine(".orElse(chatModel);")
                .decreaseContentPadding()
                .addContent("var configuredStreamingChatModel = topAgentConfig")
                .addContent(".get(")
                .addContentLiteral(STREAMING_CHAT_MODEL_CONFIG_KEY)
                .addContentLine(")")
                .increaseContentPadding()
                .addContentLine(".asString()")
                .addContent(".map(n -> registry.getNamed(")
                .addContent(LC_STREAMING_CHAT_MODEL)
                .addContentLine(".class, n))")
                .addContentLine(".orElse(streamingChatModel);")
                .decreaseContentPadding()
                .addContentLine("if (chatModelConfigured) {")
                .increaseContentPadding()
                .addContentLine("configuredStreamingChatModel = null;")
                .decreaseContentPadding()
                .addContentLine("}")
                .addContentLine("if (streamingChatModelConfigured) {")
                .increaseContentPadding()
                .addContentLine("configuredChatModel = null;")
                .decreaseContentPadding()
                .addContentLine("}")
                .update(it -> addRootModelSelection(it, agent))
                .addContent("validateChatModelSupport(")
                .addContent(agent.agentInterfaceType())
                .addContent(".class, ")
                .addContentLiteral(agent.agentName())
                .addContentLine(", configuredChatModel, configuredStreamingChatModel);")
                .addContentLine("var rootChatModel = configuredChatModel;")
                .addContentLine("if (configuredStreamingChatModel != null && rootChatModel == null) {")
                .increaseContentPadding()
                .addContent("rootChatModel = new ")
                .addContent(LC_CHAT_MODEL)
                .addContentLine("() { };")
                .decreaseContentPadding()
                .addContentLine("}");
        method.addContent("return ")
                .addContent(LC_AGENTIC_SERVICES)
                .addContent(".createAgenticSystem(")
                .addContent(agent.agentInterfaceType())
                .addContent(".class, rootChatModel, this")
                .addContentLine("::configureSubAgents);");
        method.addContentLine();
    }

    private void addRootModelSelection(Method.Builder method, AgentInfo agent) {
        if (agent.hasChatModelAnnotation()) {
            method.addContentLine("if (!streamingChatModelConfigured) {")
                    .increaseContentPadding()
                    .addContentLine("configuredStreamingChatModel = null;")
                    .decreaseContentPadding()
                    .addContentLine("}");
        } else if (agent.hasStreamingChatModelAnnotation()) {
            method.addContentLine("if (!chatModelConfigured) {")
                    .increaseContentPadding()
                    .addContentLine("configuredChatModel = null;")
                    .decreaseContentPadding()
                    .addContentLine("}");
        } else {
            method.addContentLine("if (configuredChatModel != null && configuredStreamingChatModel != null) {")
                    .increaseContentPadding()
                    .addContentLine("configuredStreamingChatModel = null;")
                    .decreaseContentPadding()
                    .addContentLine("}");
        }
    }

    private void aiAgentsParameter(Constructor.Builder ctr,
                                   boolean autoDiscovery,
                                   TypeInfo aiInterface,
                                   TypeName aiModelAnnotation,
                                   TypeName lcModelType,
                                   String parameterName,
                                   String fieldName) {
        String modelName = aiInterface.findAnnotation(aiModelAnnotation)
                .flatMap(Annotation::stringValue)
                .orElse(null);

        if (modelName == null) {
            if (!autoDiscovery) {
                return;
            }
            ctr.addParameter(parameter -> parameter
                    .name(parameterName)
                    .type(optionalType(lcModelType)));
            ctr.addContent("this.")
                    .addContent(fieldName)
                    .addContent(" = ")
                    .addContent(parameterName)
                    .addContentLine(".orElse(null);");
        } else {
            if (!autoDiscovery) {
                return;
            }
            ctr.addParameter(parameter -> parameter
                    .name(parameterName)
                    .type(optionalType(lcModelType))
                    .addAnnotation(namedAnnotation(modelName)));
            ctr.addContent("this.")
                    .addContent(fieldName)
                    .addContent(" = ")
                    .addContent(parameterName)
                    .addContentLine(".orElse(null);");
        }
    }

    private void addConfigureSubAgentsMethod(Method.Builder mb, AgentInfo rootAgent) {
        mb.accessModifier(PACKAGE_PRIVATE)
                .addParameter(Parameter.builder()
                                      .name("ctx")
                                      .type(LC_DECLARATIVE_AGENT_CREATION_CONTEXT)
                                      .build())
                .name("configureSubAgents")
                .addContent(CLASS_WILDCARD)
                .addContentLine(" cls = ctx.agentServiceClass();")
                .addContentLine()
                .addContentLine("// Get Agent metadata created from it's annotations in build-time")
                .addContent("var metadata = registry.first(")
                .addContent(AGENT_METADATA)
                .addContent(".class, ")
                .addContent(SERVICE_QUALIFIER)
                .addContentLine(".createNamed(cls))")
                .increaseContentPadding()
                .addContent(".orElseThrow(() -> new ")
                .addContent(IllegalStateException.class)
                .addContent("(")
                .addContentLiteral("Agent ")
                .addContent("+ cls +")
                .addContentLiteral(" has no build time metadata available!")
                .addContentLine("));")
                .decreaseContentPadding()
                .addContent(STRING)
                .addContentLine(" agentName = metadata.agentName();")
                .addContentLine("var agentConfig = agenticConfig.get(agentName);")
                .addContent("var agentsConfigBuilder = ")
                .addContent(AGENTS_CONFIG)
                .addContent(".builder(metadata.buildTimeConfig());")
                .addContentLine()
                .addContentLine()
                .addContentLine("// Override annotation setup with config")
                .addContentLine("agentsConfigBuilder.config(agentConfig);")
                .addContentLine("var agentsConfig = agentsConfigBuilder.build();")
                .addContent("var runtimeChatModel = agentConfig.get(")
                .addContentLiteral(CHAT_MODEL_CONFIG_KEY)
                .addContentLine(").asString()")
                .increaseContentPadding()
                .addContent(".map(n -> registry.getNamed(")
                .addContent(LC_CHAT_MODEL)
                .addContentLine(".class, n));")
                .decreaseContentPadding()
                .addContent("var runtimeStreamingChatModel = agentConfig.get(")
                .addContentLiteral(STREAMING_CHAT_MODEL_CONFIG_KEY)
                .addContentLine(").asString()")
                .increaseContentPadding()
                .addContent(".map(n -> registry.getNamed(")
                .addContent(LC_STREAMING_CHAT_MODEL)
                .addContentLine(".class, n));")
                .decreaseContentPadding()
                .addContent("var configuredAgentChatModel = agentsConfig.chatModel()")
                .increaseContentPadding()
                .addContent(".map(n -> registry.getNamed(")
                .addContent(LC_CHAT_MODEL)
                .addContentLine(".class, n))")
                .addContentLine(".orElse(null);")
                .decreaseContentPadding()
                .addContent("var configuredAgentStreamingChatModel = agentsConfig.streamingChatModel()")
                .increaseContentPadding()
                .addContent(".map(n -> registry.getNamed(")
                .addContent(LC_STREAMING_CHAT_MODEL)
                .addContentLine(".class, n))")
                .addContentLine(".orElse(null);")
                .decreaseContentPadding()
                .addContentLine("var effectiveChatModel = configuredAgentChatModel;")
                .addContentLine("var effectiveStreamingChatModel = configuredAgentStreamingChatModel;")
                .addContentLine("if (runtimeChatModel.isPresent() && runtimeStreamingChatModel.isPresent()) {")
                .increaseContentPadding()
                .addContent("throw new ")
                .addContent(IllegalStateException.class)
                .addContent("(")
                .addContentLiteral("Both chat-model and streaming-chat-model are configured for agent ")
                .addContent(" + agentName + ")
                .addContentLiteral(".")
                .addContentLine(");")
                .decreaseContentPadding()
                .addContentLine("}")
                .addContentLine("agentsConfig.configure(ctx, registry);")
                .addContentLine("if (runtimeChatModel.isPresent()) {")
                .increaseContentPadding()
                .addContentLine("effectiveChatModel = runtimeChatModel.get();")
                .addContentLine("effectiveStreamingChatModel = null;")
                .addContentLine("ctx.agentBuilder().streamingChatModel(null);")
                .addContentLine("ctx.agentBuilder().chatModel(runtimeChatModel.get());")
                .decreaseContentPadding()
                .addContentLine("} else if (runtimeStreamingChatModel.isPresent()) {")
                .increaseContentPadding()
                .addContentLine("effectiveChatModel = null;")
                .addContentLine("effectiveStreamingChatModel = runtimeStreamingChatModel.get();")
                .addContentLine("ctx.agentBuilder().chatModel(null);")
                .addContentLine("ctx.agentBuilder().streamingChatModel(runtimeStreamingChatModel.get());")
                .decreaseContentPadding()
                .addContentLine("} else if (configuredAgentStreamingChatModel != null && configuredAgentChatModel == null) {")
                .increaseContentPadding()
                .addContentLine("effectiveChatModel = null;")
                .addContentLine("ctx.agentBuilder().chatModel(null);")
                .decreaseContentPadding()
                .addContentLine("} else if (configuredAgentChatModel == null && configuredAgentStreamingChatModel == null) {")
                .increaseContentPadding()
                .addContent("var topAgentConfig = this.agenticConfig.get(")
                .addContentLiteral(rootAgent.agentName())
                .addContentLine(");")
                .addContent("boolean rootChatModelConfigured = topAgentConfig.get(")
                .addContentLiteral(CHAT_MODEL_CONFIG_KEY)
                .addContentLine(").exists();")
                .addContent("boolean rootStreamingChatModelConfigured = topAgentConfig.get(")
                .addContentLiteral(STREAMING_CHAT_MODEL_CONFIG_KEY)
                .addContentLine(").exists();")
                .addContent("var rootStreamingChatModel = topAgentConfig.get(")
                .addContentLiteral(STREAMING_CHAT_MODEL_CONFIG_KEY)
                .addContentLine(").asString()")
                .increaseContentPadding()
                .addContent(".map(n -> registry.getNamed(")
                .addContent(LC_STREAMING_CHAT_MODEL)
                .addContentLine(".class, n))")
                .addContentLine(".orElse(null);")
                .update(it -> {
                    if (rootAgent.hasStreamingChatModelAnnotation()) {
                        it.addContentLine("if (!rootStreamingChatModelConfigured) {")
                                .increaseContentPadding()
                                .addContentLine("rootStreamingChatModel = streamingChatModel;")
                                .decreaseContentPadding()
                                .addContentLine("}");
                    } else if (!rootAgent.hasChatModelAnnotation()) {
                        it.addContentLine("if (!rootStreamingChatModelConfigured && chatModel == null) {")
                                .increaseContentPadding()
                                .addContentLine("rootStreamingChatModel = streamingChatModel;")
                                .decreaseContentPadding()
                                .addContentLine("}");
                    }
                })
                .decreaseContentPadding()
                .addContentLine("if (!rootChatModelConfigured && rootStreamingChatModel != null) {")
                .increaseContentPadding()
                .addContentLine("effectiveChatModel = null;")
                .addContentLine("effectiveStreamingChatModel = rootStreamingChatModel;")
                .addContentLine("ctx.agentBuilder().chatModel(null);")
                .addContentLine("ctx.agentBuilder().streamingChatModel(rootStreamingChatModel);")
                .decreaseContentPadding()
                .addContentLine("}")
                .decreaseContentPadding()
                .addContentLine("}")
                .addContentLine("validateChatModelSupport(cls, agentName, effectiveChatModel, effectiveStreamingChatModel);");
    }

    private void addRequiresChatModelMethod(Method.Builder method, Set<TypeName> chatModelRequiredTypes) {
        method.accessModifier(PRIVATE)
                .returnType(PRIMITIVE_BOOLEAN)
                .name("requiresChatModel")
                .addParameter(CLASS_WILDCARD, "cls");

        if (chatModelRequiredTypes.isEmpty()) {
            method.addContentLine("return false;");
            return;
        }

        chatModelRequiredTypes.forEach(typeName -> method
                .addContent("if (cls == ")
                .addContent(typeName)
                .addContentLine(".class) {")
                .increaseContentPadding()
                .addContentLine("return true;")
                .decreaseContentPadding()
                .addContentLine("}"));
        method.addContentLine("return false;");
    }

    private void addValidateChatModelSupportMethod(Method.Builder method) {
        method.accessModifier(PRIVATE)
                .name("validateChatModelSupport")
                .addParameter(CLASS_WILDCARD, "cls")
                .addParameter(STRING, "agentName")
                .addParameter(LC_CHAT_MODEL, "configuredChatModel")
                .addParameter(LC_STREAMING_CHAT_MODEL, "configuredStreamingChatModel")
                .addContentLine("if (configuredChatModel == null")
                .addContentLine("        && configuredStreamingChatModel != null")
                .addContentLine("        && requiresChatModel(cls)) {")
                .increaseContentPadding()
                .addContent("throw new ")
                .addContent(IllegalStateException.class)
                .addContent("(")
                .addContentLiteral("Agent ")
                .addContent(" + agentName + ")
                .addContentLiteral(" requires chat-model; streaming-chat-model only is not supported for "
                                           + "supervisor agents or agents using summarizedContext.")
                .addContentLine(");")
                .decreaseContentPadding()
                .addContentLine("}");
    }

    private Set<TypeName> chatModelRequiredTypes(Collection<TypeInfo> types) {
        Set<TypeName> result = new LinkedHashSet<>();
        for (TypeInfo type : types) {
            if (requiresChatModel(type)) {
                result.add(type.typeName());
            }
        }
        return result;
    }

    private boolean requiresChatModel(TypeInfo agentInterface) {
        if (agentInterface.elementInfo().stream()
                .filter(it -> it.kind() == ElementKind.METHOD)
                .anyMatch(this::requiresChatModel)) {
            return true;
        }

        return agentInterface.interfaceTypeInfo().stream()
                .anyMatch(this::requiresChatModel);
    }

    private boolean requiresChatModel(TypedElementInfo methodInfo) {
        return methodInfo.annotations().stream()
                .anyMatch(this::requiresChatModel);
    }

    private boolean requiresChatModel(Annotation annotation) {
        if (LC_SUPERVISOR_AGENT.equals(annotation.typeName())) {
            return true;
        }

        return LC_AGENTIC_AGENT.equals(annotation.typeName())
                && annotation.stringValues("summarizedContext")
                        .stream()
                        .flatMap(Collection::stream)
                        .findAny()
                        .isPresent();
    }

    private boolean isAgentMethod(TypedElementInfo methodInfo) {
        return methodInfo.annotations().stream()
                .map(Annotation::typeName)
                .anyMatch(AGENT_METHOD_ANNOTATIONS::contains);
    }

    private boolean isStringStream(TypeName typeName) {
        return TypeName.create(Stream.class).equals(typeName.genericTypeName())
                && typeName.typeArguments().size() == 1
                && STRING.equals(typeName.typeArguments().getFirst());
    }

    private TypeName generatedTypeName(TypeName aiInterfaceType, String suffix) {
        return TypeName.builder()
                .packageName(aiInterfaceType.packageName())
                .className(aiInterfaceType.classNameWithEnclosingNames().replace('.', '_') + "__" + suffix)
                .build();
    }

    private TypeName supplierType(TypeName suppliedType) {
        return TypeName.builder(TypeNames.SUPPLIER)
                .addTypeArgument(suppliedType)
                .build();
    }

    private TypeName optionalType(TypeName optionalType) {
        return TypeName.builder(TypeNames.OPTIONAL)
                .addTypeArgument(optionalType)
                .build();
    }

    private Annotation namedAnnotation(String modelName) {
        return Annotation.create(SERVICE_ANNOTATION_NAMED, modelName);
    }

    private record AgentInfo(TypeName agentInterfaceType,
                             TypeName generatedType,
                             String agentName,
                             boolean hasChatModelAnnotation,
                             boolean hasStreamingChatModelAnnotation) {
    }
}
