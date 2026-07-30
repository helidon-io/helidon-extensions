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

package io.helidon.extensions.messaging;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.OptionalLong;

/**
 * Deterministic logical content sizing for payload types whose byte weight is known without serialization.
 */
final class MessageSizes {
    private MessageSizes() {
    }

    static OptionalLong logicalBytes(Object entity, Map<String, String> headers) {
        OptionalLong payloadBytes = payloadBytes(entity);
        if (payloadBytes.isEmpty()) {
            return OptionalLong.empty();
        }

        try {
            long result = Math.addExact(payloadBytes.getAsLong(), headersBytes(headers));
            return OptionalLong.of(result);
        } catch (ArithmeticException e) {
            return OptionalLong.empty();
        }
    }

    static long headersBytes(Map<String, String> headers) {
        long result = 0;
        for (Map.Entry<String, String> header : headers.entrySet()) {
            result = Math.addExact(result, utf8Bytes(header.getKey()));
            result = Math.addExact(result, utf8Bytes(header.getValue()));
        }
        return result;
    }

    private static OptionalLong payloadBytes(Object entity) {
        if (entity == null) {
            return OptionalLong.of(0);
        }
        if (entity instanceof byte[] bytes) {
            return OptionalLong.of(bytes.length);
        }
        if (entity instanceof ByteBuffer buffer) {
            return OptionalLong.of(buffer.capacity());
        }
        if (entity instanceof String text) {
            return OptionalLong.of(utf8Bytes(text));
        }
        if (entity instanceof Byte || entity instanceof Boolean) {
            return OptionalLong.of(Byte.BYTES);
        }
        if (entity instanceof Short || entity instanceof Character) {
            return OptionalLong.of(Short.BYTES);
        }
        if (entity instanceof Integer || entity instanceof Float) {
            return OptionalLong.of(Integer.BYTES);
        }
        if (entity instanceof Long || entity instanceof Double) {
            return OptionalLong.of(Long.BYTES);
        }
        return OptionalLong.empty();
    }

    private static long utf8Bytes(String value) {
        long result = 0;
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character <= 0x7F) {
                result++;
            } else if (character <= 0x7FF) {
                result += 2;
            } else if (Character.isHighSurrogate(character)
                    && i + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(i + 1))) {
                result += 4;
                i++;
            } else if (Character.isSurrogate(character)) {
                result++;
            } else {
                result += 3;
            }
        }
        return result;
    }
}
