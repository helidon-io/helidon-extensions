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

package io.helidon.extensions.messaging.connectors.pulsar;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import io.helidon.messaging.ConnectorDelivery;

import org.apache.pulsar.client.api.MessageId;

final class PulsarTestSupport {
    private PulsarTestSupport() {
    }

    static org.apache.pulsar.client.api.Message<Object> nativeMessage(Object value, int size) {
        return nativeMessage(value, size, Map.of("trace-id", "pulsar-trace"));
    }

    @SuppressWarnings("unchecked")
    static org.apache.pulsar.client.api.Message<Object> nativeMessage(Object value,
                                                                     int size,
                                                                     Map<String, String> properties) {
        byte[] messageId = {1, 2, 3};
        MessageId id = proxy(MessageId.class, (ignored, method, args) -> switch (method.getName()) {
        case "toByteArray" -> messageId.clone();
        case "compareTo" -> 0;
        case "toString" -> "1:2:3";
        default -> defaultValue(method);
        });
        return proxy(org.apache.pulsar.client.api.Message.class, (ignored, method, args) -> switch (method.getName()) {
        case "getValue" -> value;
        case "getData" -> value instanceof byte[] bytes ? bytes.clone() : String.valueOf(value).getBytes();
        case "size" -> size;
        case "getProperties" -> properties;
        case "hasKey", "hasOrderingKey", "hasBrokerPublishTime", "hasIndex", "isReplicated" -> false;
        case "getMessageId" -> id;
        case "getTopicName" -> "persistent://public/default/input";
        case "getPublishTime" -> 1234L;
        case "getEventTime" -> 0L;
        case "getSequenceId" -> -1L;
        case "getProducerName" -> "source-producer";
        case "getRedeliveryCount" -> 2;
        case "getSchemaVersion" -> new byte[] {4, 5};
        case "getBrokerPublishTime", "getIndex", "getEncryptionCtx", "getReaderSchema" -> Optional.empty();
        default -> defaultValue(method);
        });
    }

    static ConnectorDelivery completedDelivery() {
        return new ConnectorDelivery() {
            @Override
            public boolean isDone() {
                return true;
            }

            @Override
            public boolean isCurrentThread() {
                return false;
            }

            @Override
            public void await() {
            }

            @Override
            public boolean await(Duration timeout) {
                return true;
            }

            @Override
            public void cancel() {
            }

            @Override
            public void close() {
            }
        };
    }

    @SuppressWarnings("unchecked")
    static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }

    static Object defaultValue(Method method) {
        Class<?> type = method.getReturnType();
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0D;
        }
        if (type == float.class) {
            return 0F;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == char.class) {
            return (char) 0;
        }
        return null;
    }
}
