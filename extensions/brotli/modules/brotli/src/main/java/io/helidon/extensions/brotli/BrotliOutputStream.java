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

/**
 * Output stream that compresses data using Brotli without a native library.
 * Closing this stream finishes the Brotli stream and closes the underlying output stream.
 * Only compression quality {@code 0} is currently supported.
 */
public final class BrotliOutputStream extends OutputStream {
    /**
     * Default configuration used by the one-argument constructor.
     */
    public static final BrotliOutputStreamConfig DEFAULT_CONFIG = BrotliOutputStreamConfig.builder().build();

    private final OutputStream outputStream;
    private final Encoder encoder;
    private final ByteInput input;

    private boolean closed;

    /**
     * Create an encoder with the default configuration.
     *
     * @param outputStream destination for Brotli-compressed data
     */
    public BrotliOutputStream(OutputStream outputStream) {
        this(outputStream, DEFAULT_CONFIG);
    }

    /**
     * Create an encoder with the provided configuration.
     *
     * @param outputStream destination for Brotli-compressed data
     * @param config encoder configuration
     */
    public BrotliOutputStream(OutputStream outputStream, BrotliOutputStreamConfig config) {
        this.outputStream = Objects.requireNonNull(outputStream, "outputStream");
        Objects.requireNonNull(config, "config");
        if (config.quality() != 0) {
            throw new IllegalArgumentException("Only Brotli compression quality 0 is supported");
        }
        if (config.window() < 10 || config.window() > 24) {
            throw new IllegalArgumentException("Brotli window must be between 10 and 24");
        }
        int cacheInputSize;
        try {
            cacheInputSize = Math.toIntExact(config.cacheInputSize().toBytes());
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Input cache size must be between 4 and 2147483647 bytes", e);
        }
        if (cacheInputSize < 4) {
            throw new IllegalArgumentException("Input cache size must be at least 4");
        }
        encoder = new Encoder(outputStream, config.quality(), config.window(), cacheInputSize);
        input = new ByteInput(cacheInputSize);
    }

    @Override
    public void write(int value) throws IOException {
        ensureOpen();
        if (input.size() == input.limit()) {
            flush();
        }
        input.writeByte(value & 0xFF);
    }

    @Override
    public void write(byte[] buffer, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, buffer.length);
        ensureOpen();
        while (length > 0) {
            if (input.size() == input.limit()) {
                flush();
            }
            int count = Math.min(length, input.limit() - input.size());
            input.write(buffer, offset, count);
            offset += count;
            length -= count;
        }
    }

    @Override
    public void flush() throws IOException {
        ensureOpen();
        try {
            if (input.size() > 0) {
                encoder.compress(input);
                input.clear();
            }
            outputStream.flush();
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
                outputStream.close();
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
