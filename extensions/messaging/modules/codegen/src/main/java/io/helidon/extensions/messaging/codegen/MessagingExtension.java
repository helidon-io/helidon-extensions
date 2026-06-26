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

package io.helidon.extensions.messaging.codegen;

import java.util.Collection;

import io.helidon.codegen.CodegenException;
import io.helidon.codegen.CodegenUtil;
import io.helidon.codegen.classmodel.ClassModel;
import io.helidon.codegen.classmodel.Method;
import io.helidon.common.types.AccessModifier;
import io.helidon.common.types.Annotation;
import io.helidon.common.types.Annotations;
import io.helidon.common.types.ElementKind;
import io.helidon.common.types.TypeInfo;
import io.helidon.common.types.TypeName;
import io.helidon.common.types.TypeNames;
import io.helidon.common.types.TypedElementInfo;
import io.helidon.service.codegen.RegistryCodegenContext;
import io.helidon.service.codegen.RegistryRoundContext;
import io.helidon.service.codegen.ServiceCodegenTypes;
import io.helidon.service.codegen.spi.RegistryCodegenExtension;

class MessagingExtension implements RegistryCodegenExtension {
    private static final TypeName GENERATOR = TypeName.create(MessagingExtension.class);

    private final RegistryCodegenContext ctx;

    MessagingExtension(RegistryCodegenContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void process(RegistryRoundContext roundContext) {
        Collection<TypedElementInfo> elements = roundContext.annotatedElements(MessagingTypes.ON_MESSAGE);
        for (TypedElementInfo element : elements) {
            validateConsumerMethod(element);
            TypeName serviceType = enclosingType(element);
            TypeInfo typeInfo = roundContext.typeInfo(serviceType)
                    .orElseThrow(() -> new CodegenException("Could not obtain messaging consumer type " + serviceType,
                                                            element.originatingElementValue()));
            checkTypeIsService(roundContext, typeInfo);
            generateConsumerRegistration(roundContext, typeInfo, element);
        }
    }

    private void generateConsumerRegistration(RegistryRoundContext roundContext,
                                              TypeInfo typeInfo,
                                              TypedElementInfo element) {
        String channel = element.annotation(MessagingTypes.ON_MESSAGE)
                .stringValue()
                .orElseThrow(() -> new CodegenException("@Messaging.OnMessage requires a channel name",
                                                        element.originatingElementValue()));
        ConsumerMethod consumerMethod = consumerMethod(element);
        TypeName payloadType = consumerMethod.payloadType();
        TypeName generatedType = TypeName.builder()
                .packageName(typeInfo.typeName().packageName())
                .className(consumerClassName(typeInfo, element))
                .build();

        ClassModel.Builder classModel = ClassModel.builder()
                .copyright(CodegenUtil.copyright(GENERATOR, typeInfo.typeName(), generatedType))
                .addAnnotation(CodegenUtil.generatedAnnotation(GENERATOR, typeInfo.typeName(), generatedType, "1", ""))
                .type(generatedType)
                .accessModifier(AccessModifier.PACKAGE_PRIVATE)
                .description("Messaging consumer registration for channel {@code " + channel + "}.")
                .addInterface(MessagingTypes.CONSUMER_REGISTRATION)
                .addAnnotation(Annotation.create(ServiceCodegenTypes.SERVICE_ANNOTATION_SINGLETON));

        classModel.addField(consumer -> consumer
                .accessModifier(AccessModifier.PRIVATE)
                .isFinal(true)
                .type(typeInfo.typeName())
                .name("consumer"));

        classModel.addConstructor(ctr -> ctr
                .addAnnotation(Annotation.create(ServiceCodegenTypes.SERVICE_ANNOTATION_INJECT))
                .accessModifier(AccessModifier.PACKAGE_PRIVATE)
                .addParameter(consumer -> consumer
                        .type(typeInfo.typeName())
                        .name("consumer"))
                .addContentLine("this.consumer = consumer;"));

        classModel.addMethod(method -> method
                .addAnnotation(Annotations.OVERRIDE)
                .accessModifier(AccessModifier.PUBLIC)
                .returnType(TypeNames.STRING)
                .name("channel")
                .addContent("return ")
                .addContentLiteral(channel)
                .addContentLine(";"));

        classModel.addMethod(method -> method
                .addAnnotation(Annotations.OVERRIDE)
                .accessModifier(AccessModifier.PUBLIC)
                .returnType(TypeNames.CLASS_WILDCARD)
                .name("payloadType")
                .addContent("return ")
                .addContent(payloadType.genericTypeName())
                .addContentLine(".class;"));

        if (consumerMethod.batch()) {
            addBatchMethod(classModel);
        }
        addDispatchMethod(classModel, element, payloadType, consumerMethod.batch());

        roundContext.addGeneratedType(generatedType,
                                      classModel,
                                      typeInfo.typeName(),
                                      element.originatingElementValue());
    }

    private void addBatchMethod(ClassModel.Builder classModel) {
        classModel.addMethod(method -> method
                .addAnnotation(Annotations.OVERRIDE)
                .accessModifier(AccessModifier.PUBLIC)
                .returnType(TypeNames.PRIMITIVE_BOOLEAN)
                .name("batch")
                .addContentLine("return true;"));
    }

    private void addDispatchMethod(ClassModel.Builder classModel,
                                   TypedElementInfo element,
                                   TypeName payloadType,
                                   boolean batch) {
        if (batch) {
            addBatchDispatchMethods(classModel, element, payloadType);
            return;
        }

        TypeName messageType = TypeName.builder()
                .from(MessagingTypes.MESSAGE)
                .addTypeArgument(payloadType)
                .build();

        Method.Builder dispatch = Method.builder()
                .addAnnotation(Annotations.OVERRIDE)
                .addAnnotation(Annotation.create(SuppressWarnings.class, "unchecked"))
                .accessModifier(AccessModifier.PUBLIC)
                .returnType(TypeName.create(void.class))
                .name("dispatch")
                .addParameter(message -> message
                        .type(messageWildcardType())
                        .name("message"))
                .addContent("var typedMessage = (")
                .addContent(messageType)
                .addContentLine(") message;")
                .addContent("consumer.")
                .addContent(element.elementName())
                .addContent("(");

        for (int i = 0; i < element.parameterArguments().size(); i++) {
            if (i > 0) {
                dispatch.addContent(", ");
            }
            addParameterDispatch(dispatch,
                                 element.parameterArguments().get(i),
                                 element.parameterArguments().size() == 1);
        }

        dispatch.addContentLine(");");
        classModel.addMethod(dispatch);
    }

    private void addBatchDispatchMethods(ClassModel.Builder classModel, TypedElementInfo element, TypeName payloadType) {
        TypeName batchType = messageListType(payloadType);

        classModel.addMethod(method -> method
                .addAnnotation(Annotations.OVERRIDE)
                .accessModifier(AccessModifier.PUBLIC)
                .returnType(TypeName.create(void.class))
                .name("dispatch")
                .addParameter(message -> message
                        .type(messageWildcardType())
                        .name("message"))
                .addContent("dispatchBatch(")
                .addContent(MessagingTypes.LIST)
                .addContentLine(".of(message));"));

        classModel.addMethod(method -> method
                .addAnnotation(Annotations.OVERRIDE)
                .addAnnotation(Annotation.create(SuppressWarnings.class, "unchecked"))
                .accessModifier(AccessModifier.PUBLIC)
                .returnType(TypeName.create(void.class))
                .name("dispatchBatch")
                .addParameter(messages -> messages
                        .type(messageWildcardListType())
                        .name("messages"))
                .addContent("var typedMessages = (")
                .addContent(batchType)
                .addContent(") (")
                .addContent(MessagingTypes.LIST)
                .addContentLine("<?>) messages;")
                .addContent("consumer.")
                .addContent(element.elementName())
                .addContentLine("(typedMessages);"));
    }

    private void addParameterDispatch(Method.Builder dispatch, TypedElementInfo argument, boolean singleParameter) {
        if (argument.hasAnnotation(MessagingTypes.HEADER_PARAM)) {
            String headerName = argument.annotation(MessagingTypes.HEADER_PARAM)
                    .stringValue()
                    .orElseThrow(() -> new CodegenException("@Messaging.HeaderParam requires a header name",
                                                            argument.originatingElementValue()));
            dispatch.addContent("typedMessage.header(")
                    .addContentLiteral(headerName)
                    .addContent(").orElse(null)");
            return;
        }

        if (argument.hasAnnotation(MessagingTypes.ENTITY)
                || (singleParameter && !argument.typeName().genericTypeName().equals(MessagingTypes.MESSAGE))) {
            dispatch.addContent("typedMessage.entity()");
            return;
        }

        if (argument.typeName().genericTypeName().equals(MessagingTypes.MESSAGE)) {
            dispatch.addContent("typedMessage");
            return;
        }

        throw new CodegenException("Unsupported @Messaging.OnMessage parameter " + argument.toDeclaration()
                                           + ". Use @Messaging.Entity, @Messaging.HeaderParam, or Message<T>.",
                                   argument.originatingElementValue());
    }

    private ConsumerMethod consumerMethod(TypedElementInfo element) {
        if (element.parameterArguments().size() == 1) {
            TypedElementInfo argument = element.parameterArguments().getFirst();
            TypeName batchPayloadType = batchPayloadType(argument);
            if (batchPayloadType != null) {
                if (argument.hasAnnotation(MessagingTypes.HEADER_PARAM)
                        || argument.hasAnnotation(MessagingTypes.ENTITY)) {
                    throw new CodegenException("List<Message<T>> batch consumer parameters cannot use messaging annotations",
                                               argument.originatingElementValue());
                }
                return new ConsumerMethod(batchPayloadType, true);
            }
        } else {
            for (TypedElementInfo argument : element.parameterArguments()) {
                if (batchPayloadType(argument) != null) {
                    throw new CodegenException("List<Message<T>> batch consumers must declare exactly one parameter",
                                               argument.originatingElementValue());
                }
            }
        }

        TypeName payloadType = null;
        for (TypedElementInfo argument : element.parameterArguments()) {
            TypeName candidate = null;
            if (argument.hasAnnotation(MessagingTypes.ENTITY)) {
                candidate = argument.typeName();
            } else if (argument.typeName().genericTypeName().equals(MessagingTypes.MESSAGE)) {
                if (argument.typeName().typeArguments().isEmpty()) {
                    throw new CodegenException("Message consumer parameters must declare a payload type",
                                               argument.originatingElementValue());
                }
                candidate = argument.typeName().typeArguments().getFirst();
            } else if (element.parameterArguments().size() == 1
                    && !argument.hasAnnotation(MessagingTypes.HEADER_PARAM)) {
                candidate = argument.typeName();
            }

            if (candidate != null) {
                if (payloadType == null) {
                    payloadType = candidate;
                } else if (!payloadType.equals(candidate)) {
                    throw new CodegenException("Conflicting messaging payload types on " + element.toDeclaration(),
                                               argument.originatingElementValue());
                }
            }
        }

        if (payloadType == null) {
            throw new CodegenException("@Messaging.OnMessage method must declare a payload or Message<T> parameter",
                                       element.originatingElementValue());
        }
        return new ConsumerMethod(payloadType, false);
    }

    private TypeName batchPayloadType(TypedElementInfo argument) {
        TypeName typeName = argument.typeName();
        if (!typeName.genericTypeName().equals(MessagingTypes.LIST)) {
            return null;
        }
        if (typeName.typeArguments().isEmpty()) {
            throw new CodegenException("List<Message<T>> batch consumer parameters must declare Message<T>",
                                       argument.originatingElementValue());
        }
        TypeName messageType = typeName.typeArguments().getFirst();
        if (!messageType.genericTypeName().equals(MessagingTypes.MESSAGE)) {
            return null;
        }
        if (messageType.typeArguments().isEmpty()) {
            throw new CodegenException("List<Message<T>> batch consumer parameters must declare a payload type",
                                       argument.originatingElementValue());
        }
        TypeName payloadType = messageType.typeArguments().getFirst();
        if (payloadType.equals(TypeNames.WILDCARD)) {
            throw new CodegenException("List<Message<T>> batch consumer parameters must declare a concrete payload type",
                                       argument.originatingElementValue());
        }
        return payloadType;
    }

    private TypeName messageWildcardType() {
        return TypeName.builder()
                .from(MessagingTypes.MESSAGE)
                .addTypeArgument(TypeNames.WILDCARD)
                .build();
    }

    private TypeName messageWildcardListType() {
        return TypeName.builder(MessagingTypes.LIST)
                .addTypeArgument(messageWildcardType())
                .build();
    }

    private TypeName messageListType(TypeName payloadType) {
        return TypeName.builder(MessagingTypes.LIST)
                .addTypeArgument(TypeName.builder()
                                         .from(MessagingTypes.MESSAGE)
                                         .addTypeArgument(payloadType)
                                         .build())
                .build();
    }

    private void validateConsumerMethod(TypedElementInfo element) {
        if (element.kind() != ElementKind.METHOD) {
            throw new CodegenException("@Messaging.OnMessage is only allowed on methods",
                                       element.originatingElementValue());
        }
        if (element.accessModifier() == AccessModifier.PRIVATE) {
            throw new CodegenException("@Messaging.OnMessage is only allowed on non-private methods",
                                       element.originatingElementValue());
        }
        if (!element.typeName().equals(TypeNames.PRIMITIVE_VOID)) {
            throw new CodegenException("@Messaging.OnMessage methods must return void",
                                       element.originatingElementValue());
        }
    }

    private void checkTypeIsService(RegistryRoundContext roundContext, TypeInfo typeInfo) {
        if (roundContext.generatedType(ctx.descriptorType(typeInfo.typeName())).isEmpty()
                && typeInfo.annotations().isEmpty()) {
            throw new CodegenException("@Messaging.OnMessage type must be a service",
                                       typeInfo.originatingElementValue());
        }
    }

    private TypeName enclosingType(TypedElementInfo element) {
        return element.enclosingType()
                .orElseThrow(() -> new CodegenException("Element " + element + " does not have an enclosing type",
                                                        element.originatingElementValue()));
    }

    private String consumerClassName(TypeInfo typeInfo, TypedElementInfo element) {
        return typeInfo.typeName().classNameWithEnclosingNames().replace('.', '_')
                + "__MessagingConsumer_" + element.elementName()
                + "_" + Integer.toUnsignedString(element.toDeclaration().hashCode(), Character.MAX_RADIX);
    }

    private record ConsumerMethod(TypeName payloadType, boolean batch) {
    }
}
