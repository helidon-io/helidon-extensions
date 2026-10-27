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
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.function.Consumer;

import io.helidon.builder.api.RuntimeType;

/**
 * Output stream that compresses data using Brotli without a native library.
 * Closing this stream finishes the Brotli stream and closes the underlying output stream.
 * Only compression quality {@code 0} is currently supported.
 */
public final class BrotliOutputStream extends OutputStream implements RuntimeType.Api<BrotliOutputStreamConfig> {
    private final BrotliOutputStreamConfig config;
    private final Encoder encoder;
    private final Buffer input;

    private boolean closed;

    private BrotliOutputStream(BrotliOutputStreamConfig config) {
        this.config = Objects.requireNonNull(config);
        Objects.requireNonNull(config.outputStream());
        if (config.quality() != 0) {
            throw new IllegalArgumentException("Only Brotli compression quality 0 is supported");
        }
        if (config.window() < 10 || config.window() > 24) {
            throw new IllegalArgumentException("Brotli window must be between 10 and 24");
        }
        if (config.cacheInputSize() < 4) {
            throw new IllegalArgumentException("Input cache size must be at least 4");
        }
        encoder = new Encoder(config);
        input = new Buffer(config.cacheInputSize());
    }

    /**
     * Create a builder for a Brotli output stream.
     *
     * @return a new builder
     */
    public static BrotliOutputStreamConfig.Builder builder() {
        return BrotliOutputStreamConfig.builder();
    }

    /**
     * Create a Brotli output stream from its configuration.
     *
     * @param config stream configuration
     * @return a new stream
     */
    public static BrotliOutputStream create(BrotliOutputStreamConfig config) {
        return new BrotliOutputStream(config);
    }

    /**
     * Create a Brotli output stream customizing its configuration.
     *
     * @param consumer configuration consumer
     * @return a new stream
     */
    public static BrotliOutputStream create(Consumer<BrotliOutputStreamConfig.Builder> consumer) {
        return builder().update(Objects.requireNonNull(consumer)).build();
    }

    @Override
    public BrotliOutputStreamConfig prototype() {
        return config;
    }

    @Override
    public void write(int value) throws IOException {
        ensureOpen();
        if (input.bitIx() == input.maxBitIx()) {
            flush();
        }
        input.writeByte(value & 0xFF);
    }

    @Override
    public void write(byte[] buffer, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, buffer.length);
        ensureOpen();
        for (int i = 0; i < length; i++) {
            write(buffer[offset + i]);
        }
    }

    @Override
    public void flush() throws IOException {
        ensureOpen();
        try {
            if (input.byteIx() > 0) {
                encoder.compress(input);
                input.clear();
            }
            config.outputStream().flush();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            encoder.close(input);
        } catch (UncheckedIOException e) {
            try {
                config.outputStream().close();
            } catch (IOException closeFailure) {
                e.getCause().addSuppressed(closeFailure);
            }
            throw e.getCause();
        }
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("Brotli output stream is closed");
        }
    }
}
