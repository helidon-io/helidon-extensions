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

import io.helidon.codegen.CodegenException;
import io.helidon.codegen.CodegenUtil;
import io.helidon.codegen.classmodel.ClassModel;
import io.helidon.common.Api;
import io.helidon.common.types.AccessModifier;
import io.helidon.common.types.Annotation;
import io.helidon.common.types.Annotations;
import io.helidon.common.types.TypeInfo;
import io.helidon.common.types.TypeName;
import io.helidon.common.types.TypedElementInfo;
import io.helidon.service.codegen.RegistryCodegenContext;
import io.helidon.service.codegen.RegistryRoundContext;
import io.helidon.service.codegen.ServiceCodegenTypes;
import io.helidon.service.codegen.spi.InjectCodegenObserver;
import io.helidon.service.codegen.spi.InjectCodegenObserverProvider;

/**
 * POC provider that turns {@code @Service.Named Emitter<T>} injection points into generated emitter services.
 */
public class MessagingEmitterObserverProvider implements InjectCodegenObserverProvider {
    /**
     * Required public constructor for {@link java.util.ServiceLoader}.
     */
    @Api.Internal
    public MessagingEmitterObserverProvider() {
    }

    @Override
    public InjectCodegenObserver create(RegistryCodegenContext context) {
        return new MessagingEmitterObserver();
    }

    private static final class MessagingEmitterObserver implements InjectCodegenObserver {
        private static final TypeName GENERATOR = TypeName.create(MessagingEmitterObserver.class);

        @Override
        public void onInjectionPoint(RegistryRoundContext roundContext,
                                     TypeInfo service,
                                     TypedElementInfo element,
                                     TypedElementInfo argument) {
            TypeName typeName = argument.typeName();
            if (!typeName.genericTypeName().equals(MessagingTypes.EMITTER)) {
                return;
            }
            if (typeName.typeArguments().isEmpty()) {
                throw new CodegenException("Cannot generate a messaging emitter when type argument is missing",
                                           argument.originatingElementValue());
            }

            String channel = argument.findAnnotation(ServiceCodegenTypes.SERVICE_ANNOTATION_NAMED)
                    .flatMap(Annotation::stringValue)
                    .orElseThrow(() -> new CodegenException("Messaging emitters must be qualified with @Service.Named",
                                                            argument.originatingElementValue()));

            TypeName payloadType = typeName.typeArguments().getFirst();
            TypeName generatedType = emitterTypeName(service.typeName(), channel);
            if (roundContext.generatedType(generatedType).isEmpty()) {
                generateEmitter(roundContext, service, generatedType, payloadType, channel);
            }
        }

        private TypeName emitterTypeName(TypeName serviceType, String channel) {
            return TypeName.builder()
                    .packageName(serviceType.packageName())
                    .className(serviceType.classNameWithEnclosingNames().replace('.', '_')
                                       + "__MessagingEmitter_" + safeIdentifier(channel)
                                       + "_" + Integer.toUnsignedString(channel.hashCode(), Character.MAX_RADIX))
                    .build();
        }

        private void generateEmitter(RegistryRoundContext roundContext,
                                     TypeInfo serviceInfo,
                                     TypeName generatedType,
                                     TypeName payloadType,
                                     String channel) {
            TypeName messageType = messageType(payloadType);

            ClassModel.Builder classModel = ClassModel.builder()
                    .copyright(CodegenUtil.copyright(GENERATOR, serviceInfo.typeName(), generatedType))
                    .addAnnotation(CodegenUtil.generatedAnnotation(GENERATOR,
                                                                   serviceInfo.typeName(),
                                                                   generatedType,
                                                                   "1",
                                                                   ""))
                    .type(generatedType)
                    .accessModifier(AccessModifier.PACKAGE_PRIVATE)
                    .description("Messaging emitter service for channel {@code " + channel + "}.")
                    .addInterface(emitterInterface(payloadType))
                    .addAnnotation(Annotation.create(ServiceCodegenTypes.SERVICE_ANNOTATION_SINGLETON))
                    .addAnnotation(Annotation.create(ServiceCodegenTypes.SERVICE_ANNOTATION_NAMED, channel));

            classModel.addField(registry -> registry
                    .accessModifier(AccessModifier.PRIVATE)
                    .isFinal(true)
                    .type(MessagingTypes.MESSAGING_RUNTIME)
                    .name("registry"));

            classModel.addConstructor(ctr -> ctr
                    .addAnnotation(Annotation.create(ServiceCodegenTypes.SERVICE_ANNOTATION_INJECT))
                    .accessModifier(AccessModifier.PACKAGE_PRIVATE)
                    .addParameter(registry -> registry
                            .type(MessagingTypes.MESSAGING_RUNTIME)
                            .name("registry"))
                    .addContentLine("this.registry = registry;"));

            classModel.addMethod(method -> method
                    .addAnnotation(Annotations.OVERRIDE)
                    .accessModifier(AccessModifier.PUBLIC)
                    .returnType(TypeName.create(void.class))
                    .name("emit")
                    .addParameter(entity -> entity
                            .type(payloadType)
                            .name("entity"))
                    .addContent("emit(")
                    .addContent(MessagingTypes.MESSAGE)
                    .addContentLine(".builder(entity).build());"));

            classModel.addMethod(method -> method
                    .addAnnotation(Annotations.OVERRIDE)
                    .accessModifier(AccessModifier.PUBLIC)
                    .returnType(TypeName.create(void.class))
                    .name("emit")
                    .addParameter(message -> message
                            .type(messageType)
                            .name("message"))
                    .addContent("registry.emit(")
                    .addContentLiteral(channel)
                    .addContentLine(", message);"));

            classModel.addMethod(method -> method
                    .addAnnotation(Annotations.OVERRIDE)
                    .accessModifier(AccessModifier.PUBLIC)
                    .returnType(TypeName.create(void.class))
                    .name("emitBatch")
                    .addParameter(messages -> messages
                            .type(messageListType(payloadType))
                            .name("messages"))
                    .addContent("registry.emitBatch(")
                    .addContentLiteral(channel)
                    .addContentLine(", messages);"));

            roundContext.addGeneratedType(generatedType, classModel, serviceInfo.typeName(), serviceInfo);
        }

        private TypeName emitterInterface(TypeName payloadType) {
            return TypeName.builder()
                    .from(MessagingTypes.EMITTER)
                    .addTypeArgument(payloadType)
                    .build();
        }

        private TypeName messageType(TypeName payloadType) {
            return TypeName.builder()
                    .from(MessagingTypes.MESSAGE)
                    .addTypeArgument(payloadType)
                    .build();
        }

        private TypeName messageListType(TypeName payloadType) {
            return TypeName.builder(MessagingTypes.LIST)
                    .addTypeArgument(messageType(payloadType))
                    .build();
        }

        private String safeIdentifier(String channel) {
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < channel.length(); i++) {
                char c = channel.charAt(i);
                result.append(Character.isJavaIdentifierPart(c) ? c : '_');
            }
            return result.toString();
        }
    }
}
