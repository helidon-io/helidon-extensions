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

package io.helidon.extensions.jackson.jackson3.builder.codegen;

import java.util.List;
import java.util.Optional;

import io.helidon.builder.codegen.GeneratedMethod;
import io.helidon.builder.codegen.OptionInfo;
import io.helidon.builder.codegen.OptionMethodType;
import io.helidon.builder.codegen.PrototypeInfo;
import io.helidon.builder.codegen.spi.BuilderCodegenExtension;
import io.helidon.codegen.classmodel.ClassBase;
import io.helidon.codegen.classmodel.ClassModel;
import io.helidon.codegen.classmodel.TypeArgument;
import io.helidon.common.types.AccessModifier;
import io.helidon.common.types.Annotation;
import io.helidon.common.types.EnumValue;
import io.helidon.common.types.TypeName;
import io.helidon.common.types.TypedElementInfo;

import static io.helidon.extensions.jackson.jackson3.builder.codegen.JacksonTypes.JSON_AUTO_DETECT;
import static io.helidon.extensions.jackson.jackson3.builder.codegen.JacksonTypes.JSON_CREATOR;
import static io.helidon.extensions.jackson.jackson3.builder.codegen.JacksonTypes.JSON_DESERIALIZE;
import static io.helidon.extensions.jackson.jackson3.builder.codegen.JacksonTypes.JSON_POJO_BUILDER;
import static io.helidon.extensions.jackson.jackson3.builder.codegen.JacksonTypes.JSON_VISIBILITY;

class JacksonBuilderExtension implements BuilderCodegenExtension {
    static final String JACKSON_PACKAGE_PREFIX = "tools.jackson";
    static final String JACKSON_ANNOTATION_PACKAGE = "com.fasterxml.jackson.annotation";
    private final boolean serialize;
    private final boolean deserialize;

    JacksonBuilderExtension(boolean serialize, boolean deserialize) {
        this.serialize = serialize;
        this.deserialize = deserialize;
    }

    @Override
    public Optional<GeneratedMethod> method(OptionInfo optionInfo, GeneratedMethod method, OptionMethodType methodType) {
        // serialization (of object to String) uses the implementation type getters
        if (methodType == OptionMethodType.PROTOTYPE_GETTER) {
            return Optional.of(updateImplGetter(optionInfo, method));
        }
        if (methodType == OptionMethodType.BUILDER_SETTER) {
            if (deserialize) {
                // Jackson uses separate mutators; the normal builder contract is unchanged.
                return Optional.of(method);
            }
            if (serialize) {
                return Optional.of(updateBuilderSetter(optionInfo, method));
            }
        }

        return Optional.of(method);
    }

    @Override
    public void updateBuilder(PrototypeInfo prototypeInfo, List<OptionInfo> options, ClassBase.Builder<?, ?> classModel) {
        if (!deserialize) {
            return;
        }
        var prototype = prototypeInfo.prototypeType();
        var builderType = TypeName.builder()
                .from(TypeName.create(prototype.fqName() + ".Builder"))
                .typeArguments(prototype.typeArguments())
                .build();
        String mutatorPrefix = "jackson";
        for (OptionInfo option : options) {
            while (option.setterName().startsWith(mutatorPrefix)) {
                mutatorPrefix += "_";
            }
        }

        classModel.addAnnotation(Annotation.builder()
                                         .typeName(JSON_POJO_BUILDER)
                                         .property("withPrefix", mutatorPrefix)
                                         .build());
        var hidden = EnumValue.create(JSON_VISIBILITY, "NONE");
        classModel.addAnnotation(Annotation.builder()
                                         .typeName(JSON_AUTO_DETECT)
                                         .property("fieldVisibility", hidden)
                                         .property("getterVisibility", hidden)
                                         .property("isGetterVisibility", hidden)
                                         .property("setterVisibility", EnumValue.create(JSON_VISIBILITY, "PUBLIC_ONLY"))
                                         .build());
        classModel.addMethod(method -> {
            method.name("builder")
                    .accessModifier(AccessModifier.PUBLIC)
                    .isStatic(true)
                    .description("Create a new builder for Jackson deserialization.")
                    .returnType(builderType, "a new builder")
                    .addAnnotation(Annotation.create(JSON_CREATOR));
            for (TypeName argument : prototypeInfo.blueprint().typeName().typeArguments()) {
                var parameter = TypeArgument.builder()
                        .token(argument.className())
                        .description(prototypeInfo.javadoc().genericsTokens().getOrDefault(argument.className(), List.of()));
                argument.upperBounds().forEach(parameter::addBound);
                method.addGenericArgument(parameter.build());
            }
            method.addContent("return ")
                    .addContent(TypeName.create(prototype.fqName()))
                    .addContentLine(".builder();");
        });
        for (OptionInfo option : options) {
            if (option.interfaceMethod().isPresent()) {
                addJacksonMutator(option, builderType, mutatorPrefix, classModel);
            }
        }
    }

    @Override
    public void updatePrototype(PrototypeInfo prototypeInfo, List<OptionInfo> options, ClassModel.Builder classModel) {
        if (!deserialize) {
            return;
        }
        var builderType = TypeName.builder(prototypeInfo.prototypeType())
                .className("Builder")
                .addEnclosingName(prototypeInfo.prototypeType().className())
                .build();

        classModel.addAnnotation(Annotation.builder()
                                         .typeName(JSON_DESERIALIZE)
                                         .property("builder", builderType)
                                         .build());
    }

    private void addJacksonMutator(OptionInfo option,
                                  TypeName builderType,
                                  String mutatorPrefix,
                                  ClassBase.Builder<?, ?> classModel) {
        String suffix = Character.toUpperCase(option.name().charAt(0)) + option.name().substring(1);
        classModel.addMethod(method -> {
            method.name(mutatorPrefix + suffix)
                    .accessModifier(AccessModifier.PUBLIC)
                    .description("Set " + option.name() + " during Jackson deserialization.")
                    .returnType(builderType, "this builder")
                    .addParameter(parameter -> parameter.name("value")
                            .type(option.declaredType())
                            .description("the deserialized value"));
            if (serialize) {
                for (Annotation annotation : option.interfaceMethod().orElseThrow().annotations()) {
                    if (annotation.typeName().packageName().startsWith(JACKSON_PACKAGE_PREFIX)
                            || annotation.typeName().packageName().equals(JACKSON_ANNOTATION_PACKAGE)) {
                        method.addAnnotation(annotation);
                    }
                }
            }
            if (option.declaredType().isOptional()) {
                method.addContentLine("if (value == null || value.isEmpty()) {")
                        .addContentLine("    clear" + suffix + "();")
                        .addContentLine("} else {")
                        .addContentLine("    " + option.setterName() + "(value.get());")
                        .addContentLine("}");
            } else {
                method.addContentLine(option.setterName() + "(value);");
            }
            method.addContentLine("return this;");
        });
    }

    private GeneratedMethod updateBuilderSetter(OptionInfo optionInfo, GeneratedMethod method) {
        if (optionInfo.interfaceMethod().isEmpty()) {
            // no declaration
            return method;
        }
        TypedElementInfo ifaceMethod = optionInfo.interfaceMethod().get();
        // add any annotation related to Jackson
        var gmBuilder = GeneratedMethod.builder(method);
        var methodBuilder = TypedElementInfo.builder(method.method());

        for (Annotation annotation : ifaceMethod.annotations()) {
            if (annotation.typeName().packageName().startsWith(JACKSON_PACKAGE_PREFIX)
                    || annotation.typeName().packageName().equals(JACKSON_ANNOTATION_PACKAGE)) {
                methodBuilder.addAnnotation(annotation);
            }
        }

        return gmBuilder.method(methodBuilder)
                .build();
    }

    private GeneratedMethod updateImplGetter(OptionInfo optionInfo, GeneratedMethod method) {
        if (optionInfo.interfaceMethod().isEmpty()) {
            // no declaration
            return method;
        }
        TypedElementInfo ifaceMethod = optionInfo.interfaceMethod().get();
        // add any annotation related to Jackson
        var gmBuilder = GeneratedMethod.builder(method);
        var methodBuilder = TypedElementInfo.builder(method.method());

        for (Annotation annotation : ifaceMethod.annotations()) {
            if (annotation.typeName().packageName().startsWith(JACKSON_PACKAGE_PREFIX)
                    || annotation.typeName().packageName().equals(JACKSON_ANNOTATION_PACKAGE)) {
                methodBuilder.addAnnotation(annotation);
            }
        }

        return gmBuilder.method(methodBuilder)
                .build();
    }
}
