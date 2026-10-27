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
package io.helidon.extensions.jackson.jackson2.tests.compatibility.runner;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import io.helidon.common.GenericType;
import io.helidon.common.media.type.MediaTypes;
import io.helidon.extensions.jackson.jackson2.http.media.JacksonSupport;
import io.helidon.http.WritableHeaders;
import io.helidon.http.media.MediaContext;
import io.helidon.http.media.MediaSupport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;

public class CompatibilityMain {
    public static void main(String[] args) throws Exception {
        // This check also catches class-path leakage, which JPMS resolution alone cannot reject.
        try {
            Class.forName("io.helidon.http.media.jackson.JacksonSupport");
            throw new AssertionError("Deprecated core Jackson runtime is present");
        } catch (ClassNotFoundException _) {
            // The replacement must supply the entire integration runtime.
        }
        var support = JacksonSupport.create(new ObjectMapper().registerModule(new Jdk8Module()));
        support.init(MediaContext.create());
        for (String name : args) {
            Class<?> type = Class.forName(name);
            roundtrip(support, type);
            System.out.println("Roundtrip OK: " + name);
        }
    }

    private static <T> void roundtrip(MediaSupport support, Class<T> type) throws Exception {
        var genericType = GenericType.create(type);
        var headers = WritableHeaders.create().contentType(MediaTypes.APPLICATION_JSON);
        byte[] json = "{\"encrypted\":true,\"workflowId\":47,\"size\":42,\"label\":\"legacy\"}"
                .getBytes(StandardCharsets.UTF_8);
        var value = support.reader(genericType, headers).supplier().get()
                .read(genericType, new ByteArrayInputStream(json), headers);
        if (!type.getMethod("getSize").invoke(value).equals(42L)
                || !type.getMethod("getWorkflowId").invoke(value).equals(47)
                || !type.getMethod("isEncrypted").invoke(value).equals(true)
                || !type.getMethod("getLabel").invoke(value).equals(Optional.of("legacy"))) {
            throw new AssertionError("Deserialization changed for " + type.getName());
        }
        var output = new ByteArrayOutputStream();
        support.writer(genericType, headers).supplier().get().write(genericType, value, output, headers);
        var expected = new ObjectMapper().readTree(json);
        var actual = new ObjectMapper().readTree(output.toByteArray());
        if (!expected.equals(actual)) {
            throw new AssertionError(type.getName() + ": expected " + expected + ", actual " + actual);
        }
    }
}
