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

import io.helidon.common.Api;

/**
 * Output stream that compresses data using Brotli without a native library.
 * Closing this stream finishes the Brotli stream and closes the underlying output stream.
 * If writing compressed bytes to the destination fails, subsequent writes and flushes fail,
 * and closing the stream only closes the destination.
 * Only compression quality {@code 0} is currently supported.
 */
@Api.Preview
public final class BrotliOutputStream extends OutputStream {
    /**
     * Default configuration used by the one-argument constructor.
     */
    public static final BrotliOutputStreamConfig DEFAULT_CONFIG = BrotliOutputStreamConfig.builder().build();

    // The output buffer's unsigned 32-bit bit cursor must not wrap before a full cache is flushed.
    private static final int MAX_CACHE_INPUT_SIZE = (1 << 29) - 1;

    private final OutputStream outputStream;
    private final Encoder encoder;
    private final ByteInput input;

    private boolean closed;
    private boolean writeFailed;
    private Throwable reportedFlushFailure;

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
            throw new IllegalArgumentException("Input cache size must be between 4 and " + MAX_CACHE_INPUT_SIZE + " bytes", e);
        }
        if (cacheInputSize < 4 || cacheInputSize > MAX_CACHE_INPUT_SIZE) {
            throw new IllegalArgumentException("Input cache size must be between 4 and " + MAX_CACHE_INPUT_SIZE + " bytes");
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
        reportedFlushFailure = null;
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
        reportedFlushFailure = null;
    }

    @Override
    public void flush() throws IOException {
        ensureOpen();
        if (input.size() > 0) {
            try {
                encoder.compress(input);
            } catch (UncheckedIOException e) {
                // The destination may already have accepted part of this compressed block.
                writeFailed = true;
                reportedFlushFailure = e.getCause();
                throw e.getCause();
            } catch (RuntimeException | Error e) {
                writeFailed = true;
                reportedFlushFailure = e;
                throw e;
            }
            input.clear();
        }
        try {
            outputStream.flush();
            reportedFlushFailure = null;
        } catch (UncheckedIOException e) {
            reportedFlushFailure = e.getCause();
            throw e.getCause();
        } catch (IOException e) {
            reportedFlushFailure = e;
            throw e;
        }
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        // Try-with-resources cannot suppress a cleanup failure on the identical exception from flush.
        Throwable reported = reportedFlushFailure;
        reportedFlushFailure = null;
        Throwable finishFailure = null;
        try {
            if (!writeFailed) {
                encoder.finish(input);
            }
        } catch (UncheckedIOException e) {
            IOException cause = e.getCause();
            if (cause != reported) {
                finishFailure = cause;
                throw cause;
            }
        } catch (RuntimeException | Error e) {
            finishFailure = e;
            throw e;
        } finally {
            try {
                outputStream.close();
            } catch (IOException | RuntimeException | Error e) {
                Throwable closeFailure = e instanceof UncheckedIOException unchecked ? unchecked.getCause() : e;
                if (closeFailure != reported && closeFailure != finishFailure) {
                    if (finishFailure == null) {
                        throw e;
                    }
                    finishFailure.addSuppressed(e);
                }
            }
        }
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("Brotli output stream is closed");
        }
        if (writeFailed) {
            throw new IOException("Brotli output stream cannot continue after a failed destination write");
        }
    }
}
