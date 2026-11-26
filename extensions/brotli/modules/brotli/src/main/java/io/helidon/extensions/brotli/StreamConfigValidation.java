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

package io.helidon.extensions.brotli;

import io.helidon.common.Size;

/**
 * Scalar configuration checks shared by the Brotli streams and HTTP encoding.
 */
final class StreamConfigValidation {
    private static final int MAX_CACHE_BUFFER_SIZE = Integer.MAX_VALUE - 8;
    // The output buffer's unsigned 32-bit bit cursor must not wrap before a full cache is drained.
    private static final int MAX_CACHE_INPUT_SIZE = (1 << 29) - 1;

    private StreamConfigValidation() {
    }

    /**
     * Check the decoder cache and window settings.
     *
     * @param config decoder configuration
     * @return the cache buffer size in bytes
     */
    static int validateInput(BrotliInputStreamConfig config) {
        int size = cacheSize(config.cacheBufferSize(), 1, MAX_CACHE_BUFFER_SIZE,
                             "Cache buffer size must be between 1 and " + MAX_CACHE_BUFFER_SIZE + " bytes");
        if (config.maxWindowBits() < 10 || config.maxWindowBits() > 24) {
            throw new IllegalArgumentException("Maximum Brotli window exponent must be between 10 and 24");
        }
        return size;
    }

    /**
     * Check the encoder quality, window and cache settings.
     *
     * @param config encoder configuration
     * @return the input cache size in bytes
     */
    static int validateOutput(BrotliOutputStreamConfig config) {
        if (config.quality() != 0) {
            throw new IllegalArgumentException("Only Brotli compression quality 0 is supported");
        }
        if (config.window() < 10 || config.window() > 24) {
            throw new IllegalArgumentException("Brotli window must be between 10 and 24");
        }
        return cacheSize(config.cacheInputSize(), 4, MAX_CACHE_INPUT_SIZE,
                         "Input cache size must be between 4 and " + MAX_CACHE_INPUT_SIZE + " bytes");
    }

    private static int cacheSize(Size size, int min, int max, String errorMessage) {
        int bytes;
        try {
            bytes = Math.toIntExact(size.toBytes());
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(errorMessage, e);
        }
        if (bytes < min || bytes > max) {
            throw new IllegalArgumentException(errorMessage);
        }
        return bytes;
    }
}
