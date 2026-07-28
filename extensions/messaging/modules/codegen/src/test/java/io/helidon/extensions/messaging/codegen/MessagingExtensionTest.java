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

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import io.helidon.codegen.testing.TestCompiler;
import io.helidon.common.Generated;
import io.helidon.common.types.TypeName;
import io.helidon.common.types.TypeNames;
import io.helidon.service.registry.Service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessagingExtensionTest {
    private static final List<Class<?>> COMPILER_CLASSPATH = List.of(
            Generated.class,
            Service.class,
            TypeName.class,
            MessagingExtensionProvider.class
    );

    @Test
    void identifiesConcretePayloadAndEnvelopeTypes() {
        TypeName listOfInteger = TypeName.builder(TypeNames.LIST)
                .addTypeArgument(TypeNames.BOXED_INT)
                .build();
        TypeName typeVariable = TypeName.createFromGenericDeclaration("T");

        assertTrue(MessagingExtension.isConcretePayloadType(listOfInteger));
        assertFalse(MessagingExtension.isConcretePayloadType(TypeNames.WILDCARD));
        assertFalse(MessagingExtension.isConcretePayloadType(typeVariable));
        assertFalse(MessagingExtension.isConcretePayloadType(TypeName.builder(TypeNames.LIST)
                                                                      .addTypeArgument(TypeNames.WILDCARD)
                                                                      .build()));
        assertFalse(MessagingExtension.isConcretePayloadType(TypeName.builder(TypeNames.LIST)
                                                                      .addTypeArgument(typeVariable)
                                                                      .build()));

        assertFalse(MessagingExtension.hasUnresolvedTypeVariable(TypeNames.WILDCARD));
        assertTrue(MessagingExtension.hasUnresolvedTypeVariable(typeVariable));
    }

    @Test
    void rejectsDifferentParameterizedUsagesOfSameEnvelope() {
        TestCompiler.Result result = compile("""
                package com.example;

                import io.helidon.extensions.messaging.Message;
                import io.helidon.extensions.messaging.Messaging;
                import io.helidon.service.registry.Service;

                interface KeyedMessage<K, V> extends Message<V> {
                }

                @Service.Singleton
                class ConflictingEnvelopeConsumer {
                    @Messaging.OnMessage("orders")
                    void consume(KeyedMessage<String, Integer> first,
                                 KeyedMessage<Long, Integer> second) {
                    }
                }
                """);

        assertDiagnostic(result, "Conflicting messaging envelope types");
    }

    @Test
    void rejectsTypeVariableInNonPayloadEnvelopeArgument() {
        TestCompiler.Result result = compile("""
                package com.example;

                import io.helidon.extensions.messaging.Message;
                import io.helidon.extensions.messaging.Messaging;
                import io.helidon.service.registry.Service;

                interface KeyedMessage<K, V> extends Message<V> {
                }

                @Service.Singleton
                class GenericEnvelopeConsumer {
                    @Messaging.OnMessage("orders")
                    <K> void consume(KeyedMessage<K, Integer> message) {
                    }
                }
                """);

        assertDiagnostic(result, "uses an unresolved type variable in its messaging envelope type");
    }

    @Test
    void supportsWildcardInNonPayloadEnvelopeArgument() throws IOException {
        TestCompiler.Result result = compile("""
                package com.example;

                import io.helidon.extensions.messaging.Message;
                import io.helidon.extensions.messaging.Messaging;
                import io.helidon.service.registry.Service;

                interface KeyedMessage<K, V> extends Message<V> {
                }

                @Service.Singleton
                class WildcardEnvelopeConsumer {
                    @Messaging.OnMessage("orders")
                    void consume(KeyedMessage<?, Integer> message) {
                    }
                }
                """);

        assertCompilationSucceeded(result);
        try (var generatedSources = Files.walk(result.sourceOutput())) {
            assertTrue(generatedSources.anyMatch(path -> path.getFileName()
                    .toString()
                    .startsWith("WildcardEnvelopeConsumer__MessagingConsumer_")),
                       "Messaging consumer registration was not generated");
        }
    }

    @Test
    void rejectsWildcardBoundContainingTypeVariable() {
        TestCompiler.Result result = compile("""
                package com.example;

                import io.helidon.extensions.messaging.Message;
                import io.helidon.extensions.messaging.Messaging;
                import io.helidon.service.registry.Service;

                interface KeyedMessage<K, V> extends Message<V> {
                }

                @Service.Singleton
                class GenericWildcardEnvelopeConsumer {
                    @Messaging.OnMessage("orders")
                    <K> void consume(KeyedMessage<? extends K, Integer> message) {
                    }
                }
                """);

        assertDiagnostic(result, "uses an unresolved type variable in its messaging envelope type");
    }

    @Test
    void generatedRegistrationRetainsCompleteGenericTypes() throws IOException {
        TestCompiler.Result result = compile("""
                package com.example;

                import java.util.List;

                import io.helidon.extensions.messaging.Message;
                import io.helidon.extensions.messaging.Messaging;
                import io.helidon.service.registry.Service;

                interface KeyedMessage<K, V> extends Message<V> {
                }

                @Service.Singleton
                class GenericMetadataConsumer {
                    @Messaging.OnMessage("orders")
                    void consume(KeyedMessage<String, List<Integer>> message) {
                    }
                }
                """);

        assertCompilationSucceeded(result);
        String generatedSource = generatedSource(result, "GenericMetadataConsumer__MessagingConsumer_");
        assertTrue(generatedSource.contains("payloadGenericType()"), generatedSource);
        assertTrue(generatedSource.contains("new GenericType<List<Integer>>()"), generatedSource);
        assertTrue(generatedSource.contains("envelopeGenericType()"), generatedSource);
        assertTrue(generatedSource.contains("new GenericType<KeyedMessage<String, List<Integer>>>()"),
                   generatedSource);
    }

    @Test
    void generatedRegistrationBoxesPrimitivePayloadMetadata() throws IOException {
        TestCompiler.Result result = compile("""
                package com.example;

                import io.helidon.extensions.messaging.Messaging;
                import io.helidon.service.registry.Service;

                @Service.Singleton
                class PrimitiveConsumer {
                    @Messaging.OnMessage("numbers")
                    void consume(int value) {
                    }
                }
                """);

        assertCompilationSucceeded(result);
        String generatedSource = generatedSource(result, "PrimitiveConsumer__MessagingConsumer_");
        assertTrue(generatedSource.contains("return Integer.class;"), generatedSource);
        assertTrue(generatedSource.contains("new GenericType<Integer>()"), generatedSource);
    }

    @Test
    void rejectsConflictingEmitterPayloadsForSameServiceAndChannel() {
        TestCompiler.Result result = compile("""
                package com.example;

                import io.helidon.extensions.messaging.Emitter;
                import io.helidon.service.registry.Service;

                @Service.Singleton
                class ConflictingEmitterProducer {
                    @Service.Inject
                    @Service.Named("orders")
                    Emitter<String> first;

                    @Service.Inject
                    @Service.Named("orders")
                    Emitter<Integer> second;
                }
                """);

        assertDiagnostic(result, "Conflicting messaging emitter payload types for channel orders");
    }

    @Test
    void rejectsWildcardEmitterPayload() {
        TestCompiler.Result result = compile("""
                package com.example;

                import io.helidon.extensions.messaging.Emitter;
                import io.helidon.service.registry.Service;

                @Service.Singleton
                class WildcardEmitterProducer {
                    @Service.Inject
                    @Service.Named("orders")
                    Emitter<?> emitter;
                }
                """);

        assertDiagnostic(result, "Messaging emitter payload type must be concrete");
    }

    @Test
    void rejectsNestedWildcardEmitterPayload() {
        TestCompiler.Result result = compile("""
                package com.example;

                import java.util.List;

                import io.helidon.extensions.messaging.Emitter;
                import io.helidon.service.registry.Service;

                @Service.Singleton
                class NestedWildcardEmitterProducer {
                    @Service.Inject
                    @Service.Named("orders")
                    Emitter<List<?>> emitter;
                }
                """);

        assertDiagnostic(result, "Messaging emitter payload type must be concrete");
    }

    @Test
    void rejectsTypeVariableEmitterPayload() {
        TestCompiler.Result result = compile("""
                package com.example;

                import io.helidon.extensions.messaging.Emitter;
                import io.helidon.service.registry.Service;

                @Service.Singleton
                class GenericEmitterProducer<T> {
                    @Service.Inject
                    @Service.Named("orders")
                    Emitter<T> emitter;
                }
                """);

        assertDiagnostic(result, "Messaging emitter payload type must be concrete");
    }

    private TestCompiler.Result compile(String source) {
        return TestCompiler.builder()
                .currentRelease()
                .addClasspath(COMPILER_CLASSPATH)
                .addClasspath(loadClass("io.helidon.builder.api.Prototype"))
                .addClasspath(loadClass("io.helidon.extensions.messaging.Messaging"))
                .update(this::addProcessor)
                .printDiagnostics(false)
                .addSource("Consumer.java", source)
                .build()
                .compile();
    }

    private void addProcessor(TestCompiler.Builder builder) {
        try {
            Class<?> processorType = Class.forName("javax.annotation.processing.Processor");
            Object processor = Class.forName("io.helidon.codegen.apt.AptProcessor")
                    .getConstructor()
                    .newInstance();
            builder.getClass()
                    .getMethod("addProcessor", processorType)
                    .invoke(builder, processor);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Could not configure the test annotation processor", e);
        }
    }

    private String generatedSource(TestCompiler.Result result, String filePrefix) throws IOException {
        try (var generatedSources = Files.walk(result.sourceOutput())) {
            var generatedSource = generatedSources
                    .filter(path -> path.getFileName().toString().startsWith(filePrefix))
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .filter(path -> !path.getFileName().toString().contains("__ServiceDescriptor"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Generated source not found for " + filePrefix));
            return Files.readString(generatedSource);
        }
    }

    private Class<?> loadClass(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new AssertionError("Missing test compiler classpath type " + className, e);
        }
    }

    private void assertDiagnostic(TestCompiler.Result result, String expected) {
        String diagnostics = String.join("\n", result.diagnostics());
        assertFalse(result.success(), "Compilation unexpectedly succeeded");
        assertTrue(diagnostics.contains(expected), "Missing diagnostic '" + expected + "':\n" + diagnostics);
    }

    private void assertCompilationSucceeded(TestCompiler.Result result) {
        assertTrue(result.success(), "Compilation failed:\n" + String.join("\n", result.diagnostics()));
    }
}
