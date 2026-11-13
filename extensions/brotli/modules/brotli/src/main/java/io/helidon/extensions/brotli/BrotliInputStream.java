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

/**
 * Input stream that decompresses Brotli data without a native library.
 * Closing this stream closes the underlying input stream.
 */
public final class BrotliInputStream extends InputStream {
    /**
     * Default configuration used by the one-argument constructor.
     */
    public static final BrotliInputStreamConfig DEFAULT_CONFIG = BrotliInputStreamConfig.builder().build();

    private final InputStream inputStream;
    private final BrotliInputStreamConfig config;
    private final byte[] buffer;
    private final byte[][] dictionaryChunks;
    private final int[] chunkOffsets;
    private final int dictionarySize;

    private Decoder decoder;
    private int remainingBufferBytes;
    private int bufferOffset;
    private boolean closed;

    /**
     * Create a decoder with the default configuration.
     *
     * @param inputStream stream containing Brotli-compressed data
     */
    public BrotliInputStream(InputStream inputStream) {
        this(inputStream, DEFAULT_CONFIG);
    }

    /**
     * Create a decoder with the provided configuration.
     *
     * @param inputStream stream containing Brotli-compressed data
     * @param config decoder configuration
     */
    public BrotliInputStream(InputStream inputStream, BrotliInputStreamConfig config) {
        this.inputStream = Objects.requireNonNull(inputStream, "inputStream");
        this.config = Objects.requireNonNull(config, "config");
        int cacheBufferSize;
        try {
            cacheBufferSize = Math.toIntExact(config.cacheBufferSize().toBytes());
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Cache buffer size must be between 1 and 2147483647 bytes", e);
        }
        if (cacheBufferSize <= 0) {
            throw new IllegalArgumentException("Cache buffer size must be strictly positive");
        }
        if (config.maxWindowBits() < 10 || config.maxWindowBits() > 24) {
            throw new IllegalArgumentException("Maximum Brotli window exponent must be between 10 and 24");
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
        buffer = new byte[cacheBufferSize];
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
                decoder = new Decoder(inputStream, chunkOffsets, dictionaryChunks, dictionarySize,
                                      config.maxWindowBits(), config.verifyEndOfInput());
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
                inputStream.close();
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
