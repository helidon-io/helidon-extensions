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

package io.helidon.extensions.oci.v3.examples.imperative;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

import com.oracle.bmc.objectstorage.ObjectStorage;
import com.oracle.bmc.objectstorage.responses.GetNamespaceResponse;

final class TestObjectStorage {
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ObjectStorage client;

    TestObjectStorage(String namespace) {
        this.client = (ObjectStorage) Proxy.newProxyInstance(ObjectStorage.class.getClassLoader(),
                                                             new Class<?>[] {ObjectStorage.class},
                                                             (proxy, method, args) -> invoke(proxy, method, args, namespace));
    }

    ObjectStorage client() {
        return client;
    }

    boolean closed() {
        return closed.get();
    }

    private Object invoke(Object proxy, Method method, Object[] args, String namespace) {
        return switch (method.getName()) {
        case "getNamespace" -> GetNamespaceResponse.builder()
                .value(namespace)
                .build();
        case "close" -> {
            closed.set(true);
            yield null;
        }
        case "equals" -> proxy == args[0];
        case "hashCode" -> System.identityHashCode(proxy);
        case "toString" -> "TestObjectStorage";
        default -> throw new UnsupportedOperationException(method.toString());
        };
    }
}
