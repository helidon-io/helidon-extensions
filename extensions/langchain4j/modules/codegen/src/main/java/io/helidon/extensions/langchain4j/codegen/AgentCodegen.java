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
import java.util.Set;

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
import io.helidon.service.codegen.ServiceCodegenTypes;

import static io.helidon.common.types.AccessModifier.PACKAGE_PRIVATE;
import static io.helidon.common.types.AccessModifier.PRIVATE;
import static io.helidon.common.types.TypeNames.CLASS_WILDCARD;
import static io.helidon.common.types.TypeNames.STRING;
import static io.helidon.extensions.langchain4j.codegen.LangchainTypes.A2A_AGENT_CONFIG_SUPPORT;
import static io.helidon.extensions.langchain4j.codegen.LangchainTypes.AGENTS_CONFIG;
import static io.helidon.extensions.langchain4j.codegen.LangchainTypes.AGENT_METADATA;
import static io.helidon.extensions.langchain4j.codegen.LangchainTypes.AI_AGENT;
import static io.helidon.extensions.langchain4j.codegen.LangchainTypes.AI_CHAT_MODEL;
import static io.helidon.extensions.langchain4j.codegen.LangchainTypes.CONFIG;
import static io.helidon.extensions.langchain4j.codegen.LangchainTypes.LC_A2A_CLIENT_AGENT;
import static io.helidon.extensions.langchain4j.codegen.LangchainTypes.LC_AGENTIC_SERVICES;
import static io.helidon.extensions.langchain4j.codegen.LangchainTypes.LC_CHAT_MODEL;
import static io.helidon.extensions.langchain4j.codegen.LangchainTypes.LC_DECLARATIVE_AGENT_CREATION_CONTEXT;
import static io.helidon.service.codegen.ServiceCodegenTypes.SERVICE_ANNOTATION_NAMED;
import static io.helidon.service.codegen.ServiceCodegenTypes.SERVICE_ANNOTATION_SINGLETON;
import static io.helidon.service.codegen.ServiceCodegenTypes.SERVICE_QUALIFIER;
import static io.helidon.service.codegen.ServiceCodegenTypes.SERVICE_REGISTRY;

class AgentCodegen implements CodegenExtension {
    private static final TypeName GENERATOR = TypeName.create(AgentCodegen.class);
    private static final Set<TypeName> COMPOSED_AGENT_ANNOTATIONS = Set.of(
            TypeName.create("dev.langchain4j.agentic.declarative.SequenceAgent"),
            TypeName.create("dev.langchain4j.agentic.declarative.LoopAgent"),
            TypeName.create("dev.langchain4j.agentic.declarative.ConditionalAgent"),
            TypeName.create("dev.langchain4j.agentic.declarative.ParallelAgent"),
            TypeName.create("dev.langchain4j.agentic.declarative.ParallelMapperAgent"),
            TypeName.create("dev.langchain4j.agentic.declarative.SupervisorAgent"),
            TypeName.create("dev.langchain4j.agentic.declarative.PlannerAgent"));
    static final String AGENTS_CONFIG_KEY = "langchain4j.agents";

    @Override
    public void process(RoundContext roundCtx) {
        Collection<TypeInfo> types = roundCtx.annotatedTypes(AI_AGENT);

        // First round to get annotation metadata of all known agents
        for (TypeInfo type : types) {
            // for each annotated interface, generate Iface__AiServices and Iface__Service
            // the type MUST be an interface
            if (type.kind() != ElementKind.INTERFACE) {
                throw new CodegenException("Type annotated with " + AI_AGENT.fqName() + " must be an interface.",
                                           type.originatingElementValue());
            }

        }
        // Second round to generate agent producers
        for (TypeInfo type : types) {
            process(roundCtx, type);
        }
    }

    private void process(RoundContext roundCtx, TypeInfo agentInterface) {
        TypeName agentInterfaceType = agentInterface.typeName();
        TypeName generatedType = generatedTypeName(agentInterfaceType, "AiAgent");
        boolean a2aAgent = isA2AAgent(agentInterface);
        AgentMetadataSupplierBuilder.build(agentInterface, roundCtx);

        var classModel = ClassModel.builder()
                .type(generatedType)
                .copyright(CodegenUtil.copyright(GENERATOR,
                                                 agentInterfaceType,
                                                 generatedType))
                .addAnnotation(CodegenUtil.generatedAnnotation(GENERATOR,
                                                               agentInterfaceType,
                                                               generatedType,
                                                               "1",
                                                               ""))
                .accessModifier(AccessModifier.PACKAGE_PRIVATE)
                .addInterface(supplierType(agentInterfaceType))
                .addAnnotation(Annotation.create(SERVICE_ANNOTATION_SINGLETON));

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

        if (!a2aAgent) {
            classModel.addField(aiServices -> aiServices
                    .name("chatModel")
                    .type(LC_CHAT_MODEL)
                    .isFinal(true)
                    .accessModifier(PRIVATE)
            );
        }

        // constructor (parameters depend on annotations on interface)
        classModel.addConstructor(ctr -> ctr
                .accessModifier(AccessModifier.PACKAGE_PRIVATE)
                .addAnnotation(Annotation.create(ServiceCodegenTypes.SERVICE_ANNOTATION_INJECT))
                .update(it -> {
                    if (a2aAgent) {
                        agentBaseParameters(it);
                    } else {
                        aiAgentsParameter(it,
                                          true,
                                          agentInterface,
                                          AI_CHAT_MODEL,
                                          LC_CHAT_MODEL,
                                          "chatModel");
                    }
                })
        );

        // and the get method (implementation of supplier)
        classModel.addMethod(get -> {
            get.accessModifier(AccessModifier.PUBLIC)
                    .addAnnotation(Annotations.OVERRIDE)
                    .returnType(agentInterfaceType)
                    .name("get")
                    .addContent("var agentsConfig = agentsConfig(")
                    .addContent(agentInterfaceType)
                    .addContentLine(".class);");
            if (a2aAgent) {
                get.addContent("return ")
                        .addContent(A2A_AGENT_CONFIG_SUPPORT)
                        .addContent(".create(")
                        .addContent(agentInterfaceType)
                        .addContentLine(".class, agentsConfig);");
            } else {
                get.addContent("var configuredModel = agentsConfig.chatModel()")
                        .increaseContentPadding()
                        .addContentLine()
                        .addContentLine(".map(n -> registry.getNamed(ChatModel.class, n))")
                        .addContentLine(".orElse(chatModel);")
                        .decreaseContentPadding()
                        .addContent("return ")
                        .addContent(LC_AGENTIC_SERVICES)
                        .addContent(".createAgenticSystem(")
                        .addContent(agentInterfaceType)
                        .addContent(".class, ")
                        .addContent("configuredModel, new ")
                        .addContent(LC_AGENTIC_SERVICES)
                        .addContent(".AgentConfigurator(this")
                        .addContentLine("::configureSubAgents, this::resolveSubAgent, null));")
                        .addContentLine("");
            }
        });

        classModel.addMethod(this::addAgentsConfigMethod);
        classModel.addMethod(this::addConfigureSubAgentsMethod);
        classModel.addMethod(this::addResolveSubAgentMethod);

        roundCtx.addGeneratedType(generatedType, classModel, agentInterfaceType, agentInterface.originatingElementValue());
    }

    private void aiAgentsParameter(Constructor.Builder ctr,
                                   boolean autoDiscovery,
                                   TypeInfo aiInterface,
                                   TypeName aiModelAnnotation,
                                   TypeName lcModelType,
                                   String aiServicesMethodName) {
        agentBaseParameters(ctr);

        // if annotated, we have a named value (and that is mandatory)
        String modelName = aiInterface.findAnnotation(aiModelAnnotation)
                .flatMap(Annotation::stringValue)
                .orElse(null);

        if (modelName == null) {
            // there is no annotation, use only auto-discovered model (if present)
            if (!autoDiscovery) {
                // no autodiscovery, this model will not be configured
                return;
            }
            ctr.addParameter(parameter -> parameter
                    .name(aiServicesMethodName)
                    .type(optionalType(lcModelType)));
            ctr
                    .addContent("this.chatModel = chatModel.orElse(null);");
        } else {
            // there is no annotation, use only auto-discovered model (if present)
            if (!autoDiscovery) {
                // no autodiscovery, this model will not be configured
                return;
            }
            ctr.addParameter(parameter -> parameter
                    .name(aiServicesMethodName)
                    .type(optionalType(lcModelType))
                    .addAnnotation(namedAnnotation(modelName)));
            ctr
                    .addContent("this.chatModel = chatModel.orElse(null);");
        }
    }

    private void agentBaseParameters(Constructor.Builder ctr) {
        ctr.addParameter(Parameter.builder()
                                 .type(CONFIG)
                                 .name("config")
                                 .build());

        ctr.addParameter(Parameter.builder()
                                 .type(SERVICE_REGISTRY)
                                 .name("registry")
                                 .build());

        ctr.addContent("this.agenticConfig = config.get(")
                .addContentLiteral(AGENTS_CONFIG_KEY)
                .addContentLine(");");
        ctr.addContentLine("this.registry = registry;");
    }

    private void addAgentsConfigMethod(Method.Builder mb) {
        mb.accessModifier(PRIVATE)
                .returnType(AGENTS_CONFIG)
                .addParameter(Parameter.builder()
                                      .name("cls")
                                      .type(CLASS_WILDCARD)
                                      .build())
                .name("agentsConfig")
                .addContentLine("// Get Agent metadata created from its annotations at build time")
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
                .addContent("var agentsConfigBuilder = ")
                .addContent(AGENTS_CONFIG)
                .addContentLine(".builder(metadata.buildTimeConfig());")
                .addContentLine("agentsConfigBuilder.config(agenticConfig.get(agentName));")
                .addContentLine("return agentsConfigBuilder.build();");
    }

    private void addConfigureSubAgentsMethod(Method.Builder mb) {
        mb
                .accessModifier(PACKAGE_PRIVATE)
                .addParameter(Parameter.builder()
                                      .name("ctx")
                                      .type(LC_DECLARATIVE_AGENT_CREATION_CONTEXT)
                                      .build())
                .name("configureSubAgents")
                .addContent(CLASS_WILDCARD)
                .addContentLine(" cls = ctx.agentServiceClass();")
                .addContentLine("agentsConfig(cls).configure(ctx, registry);");
    }

    private void addResolveSubAgentMethod(Method.Builder mb) {
        mb.accessModifier(PRIVATE)
                .returnType(TypeNames.OBJECT)
                .addParameter(Parameter.builder()
                                      .name("cls")
                                      .type(CLASS_WILDCARD)
                                      .build())
                .name("resolveSubAgent")
                .addContent("if (!")
                .addContent(A2A_AGENT_CONFIG_SUPPORT)
                .addContentLine(".isA2A(cls)) {")
                .increaseContentPadding()
                .addContentLine("return null;")
                .decreaseContentPadding()
                .addContentLine("}")
                .addContent("if (registry.first(")
                .addContent(AGENT_METADATA)
                .addContent(".class, ")
                .addContent(SERVICE_QUALIFIER)
                .addContentLine(".createNamed(cls)).isEmpty()) {")
                .increaseContentPadding()
                .addContentLine("return null;")
                .decreaseContentPadding()
                .addContentLine("}")
                .addContentLine("// Each workflow needs a fresh A2A proxy because it carries mutable parent state")
                .addContent("return ")
                .addContent(A2A_AGENT_CONFIG_SUPPORT)
                .addContentLine(".create(cls, agentsConfig(cls));");
    }

    private boolean isA2AAgent(TypeInfo typeInfo) {
        return hasAnnotation(typeInfo, LC_A2A_CLIENT_AGENT)
                && COMPOSED_AGENT_ANNOTATIONS.stream().noneMatch(annotation -> hasAnnotation(typeInfo, annotation));
    }

    private boolean hasAnnotation(TypeInfo typeInfo, TypeName annotation) {
        if (typeInfo.elementInfo()
                .stream()
                .anyMatch(element -> element.hasAnnotation(annotation))) {
            return true;
        }
        return typeInfo.interfaceTypeInfo()
                .stream()
                .anyMatch(interfaceType -> hasAnnotation(interfaceType, annotation));
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
}
