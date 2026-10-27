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

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.function.Consumer;

import io.helidon.builder.api.RuntimeType;

/**
 * Input stream that decompresses Brotli data without a native library.
 * Closing this stream closes the underlying input stream.
 */
public final class BrotliInputStream extends InputStream implements RuntimeType.Api<BrotliInputStreamConfig> {
    private final BrotliInputStreamConfig config;
    private final byte[] buffer;
    private final byte[][] dictionaryChunks;
    private final int[] chunkOffsets;
    private final int dictionarySize;

    private Decoder decoder;
    private int remainingBufferBytes;
    private int bufferOffset;
    private boolean closed;

    private BrotliInputStream(BrotliInputStreamConfig config) {
        this.config = Objects.requireNonNull(config);
        if (config.cacheBufferSize() <= 0) {
            throw new IllegalArgumentException("Cache buffer size must be strictly positive");
        }
        var chunks = config.dictionaryChunks();
        if (chunks.size() > 15) {
            throw new IllegalArgumentException("At most 15 dictionary chunks are supported");
        }
        dictionaryChunks = new byte[chunks.size()][];
        chunkOffsets = new int[chunks.size() + 1];
        int totalSize = 0;
        for (int i = 0; i < chunks.size(); i++) {
            dictionaryChunks[i] = Objects.requireNonNull(chunks.get(i)).clone();
            totalSize = Math.addExact(totalSize, dictionaryChunks[i].length);
            chunkOffsets[i + 1] = totalSize;
        }
        dictionarySize = totalSize;
        buffer = new byte[config.cacheBufferSize()];
        Objects.requireNonNull(config.inputStream());
    }

    /**
     * Create a builder for a Brotli input stream.
     *
     * @return a new builder
     */
    public static BrotliInputStreamConfig.Builder builder() {
        return BrotliInputStreamConfig.builder();
    }

    /**
     * Create a Brotli input stream from its configuration.
     *
     * @param config stream configuration
     * @return a new stream
     */
    public static BrotliInputStream create(BrotliInputStreamConfig config) {
        return new BrotliInputStream(config);
    }

    /**
     * Create a Brotli input stream customizing its configuration.
     *
     * @param consumer configuration consumer
     * @return a new stream
     */
    public static BrotliInputStream create(Consumer<BrotliInputStreamConfig.Builder> consumer) {
        return builder().update(Objects.requireNonNull(consumer)).build();
    }

    @Override
    public BrotliInputStreamConfig prototype() {
        return config;
    }

    @Override
    public int read() throws IOException {
        ensureOpen();
        if (bufferOffset >= remainingBufferBytes) {
            remainingBufferBytes = read(buffer, 0, buffer.length);
            bufferOffset = 0;
            if (remainingBufferBytes == -1) {
                return -1;
            }
        }
        return buffer[bufferOffset++] & 0xFF;
    }

    @Override
    public int read(byte[] destination, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, destination.length);
        ensureOpen();
        if (length == 0) {
            return 0;
        }
        int copied = Math.min(Math.max(remainingBufferBytes - bufferOffset, 0), length);
        if (copied > 0) {
            System.arraycopy(buffer, bufferOffset, destination, offset, copied);
            bufferOffset += copied;
            return copied;
        }
        try {
            if (decoder == null) {
                decoder = new Decoder(config.inputStream(), chunkOffsets, dictionaryChunks, dictionarySize);
            }
            decoder.decompress(destination, offset, length);
            int decoded = decoder.outputUsed();
            return decoded == 0 ? -1 : decoded;
        } catch (RuntimeException e) {
            throw new IOException("Brotli stream decoding failed", e);
        }
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            if (decoder == null) {
                config.inputStream().close();
            } else {
                decoder.close();
            }
        }
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("Brotli input stream is closed");
        }
    }
}
