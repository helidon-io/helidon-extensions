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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;

import io.helidon.common.Size;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Regression tests for the <a href="https://www.rfc-editor.org/rfc/rfc7932.html">Brotli format (RFC 7932)</a>.
 */
class BrotliConformanceTest {
    // Frozen flush/close fixture with zero-length metadata alignment (RFC 7932 section 11.2).
    // Metadata byte-aligns the compressed block; a separate final empty block closes the stream.
    private static final byte[] FLUSHED_DATA = HexFormat.of().parseHex(
            "8325000080aaaaaaea9f0fa72b11f3e1c4ff77d5bfe7deea9e515ddec67057bc585858d8d858d5cb8ceae0c3871f83c1601"
                    + "c67b2aa0683c1603018cca1ce88549446e1b0d04eb2f70477f194e1c9f9071a03");

    @Test
    @DisplayName("RFC 7932 sections 9.2 and 10: metadata has no ISUNCOMPRESSED field")
    void decodesMetadataAfterCompressedBlock() throws IOException {
        // Each stream contains the compressed literal C, one metadata byte (00 or 80), then a final empty block.
        // The metadata header ends on a byte boundary. Both vectors were independently decoded by Google Brotli 1.2.1.
        for (String vector : new String[] {"000000007490d010d140248059000003", "000000007490d010d140248059008003"}) {
            assertThat("metadata vector " + vector, decode(HexFormat.of().parseHex(vector)), is(new byte[] {'C'}));
        }
    }

    @Test
    @DisplayName("RFC 7932 section 9.2: metadata alignment bits must be zero")
    void rejectsNonzeroMetadataPadding() throws IOException {
        // Window 16, one-byte metadata, and a final empty block; bit 7 of the second byte is illegal fill.
        byte[] encoded = HexFormat.of().parseHex("2c800003");
        try (var input = new BrotliInputStream(new ByteArrayInputStream(encoded))) {
            assertThrows(IOException.class, input::readAllBytes);
        }
    }

    @Test
    @DisplayName("RFC 7932 section 9.2: metadata does not contribute output bytes")
    void decodesEmptyMetadata() throws IOException {
        // Zero-length metadata and one-byte metadata followed by the final empty block, respectively.
        for (String vector : new String[] {"0c03", "2c000003"}) {
            assertThat("metadata vector " + vector, decode(HexFormat.of().parseHex(vector)), is(new byte[0]));
        }
    }

    @Test
    @DisplayName("RFC 7932 sections 9.2 and 9.3: final block flags follow pending compressed bits")
    void finishesCompressedBlockAfterFlush() throws IOException {
        byte[] data = repetitiveData(76);
        var destination = new ByteArrayOutputStream();
        try (var output = new BrotliOutputStream(destination)) {
            output.write(data);
            output.flush();
        }
        // Metadata alignment must preserve the pending data bits before closing the stream.
        assertThat(destination.toByteArray(), is(FLUSHED_DATA));
        assertThat(decode(destination.toByteArray()), is(data));
    }

    @Test
    @DisplayName("RFC 7932 sections 9.2 and 9.3: finishing preserves compressed bits across windows")
    void roundTripsFlushedCompressedBlocksAcrossWindows() throws IOException {
        for (int window : new int[] {10, 16, 18, 24}) {
            for (int length : new int[] {76, 128, 64_000}) {
                byte[] data = repetitiveData(length);
                var destination = new ByteArrayOutputStream();
                var outputConfig = BrotliOutputStreamConfig.builder().window(window).build();
                try (var output = new BrotliOutputStream(destination, outputConfig)) {
                    output.write(data);
                    output.flush();
                    output.flush();
                }
                assertThat("window " + window + ", payload length " + length,
                           decode(destination.toByteArray()), is(data));
            }
        }
    }

    @Test
    @DisplayName("RFC 7932 sections 9.2 and 9.3: small caches preserve block endings across output flushes")
    void finishesWithSmallInputCaches() throws IOException {
        byte[] data = repetitiveData(256);
        for (int cacheSize : new int[] {4, 5, 7, 76, 77}) {
            var destination = new ByteArrayOutputStream();
            var outputConfig = BrotliOutputStreamConfig.builder().cacheInputSize(Size.create(cacheSize)).build();
            try (var output = new BrotliOutputStream(destination, outputConfig)) {
                output.write(data);
                output.flush();
            }
            assertThat("input cache size " + cacheSize, decode(destination.toByteArray()), is(data));
        }
    }

    @Test
    @DisplayName("RFC 7932 section 3.2: skewed symbol frequencies still permit a depth-limited prefix code")
    void encodesDepthLimitedHuffmanTree() throws IOException {
        // These valid symbol frequencies require the quality-0 encoder to retry its initial, overly deep tree.
        int[] counts = {1, 1, 2, 3, 5, 8, 17, 41, 80, 143, 245, 410, 677, 1109, 1808, 2939, 4769, 7730, 12521};
        byte[] data = new byte[Arrays.stream(counts).sum()];
        int offset = 0;
        for (int symbol = 0; symbol < counts.length; symbol++) {
            Arrays.fill(data, offset, offset + counts[symbol], (byte) symbol);
            offset += counts[symbol];
        }
        var destination = new ByteArrayOutputStream();
        try (var output = new BrotliOutputStream(destination)) {
            output.write(data);
        }
        assertThat(decode(destination.toByteArray()), is(data));
    }

    @Test
    @DisplayName("RFC 7932 section 4: a resolved backward distance of zero is invalid")
    void rejectsZeroBackwardDistance() throws IOException {
        // Establish distance 1, then apply short code 4 (last distance minus 1), resolving to zero.
        // Google Brotli 1.2.1 rejects this handcrafted stream.
        byte[] encoded = HexFormat.of().parseHex("82000000445001824811c800");
        try (var input = new BrotliInputStream(new ByteArrayInputStream(encoded))) {
            assertThrows(IOException.class, input::readAllBytes);
        }
    }

    @Test
    @DisplayName("CVE-2016-1624/CVE-2016-1968: malformed backward copy is rejected")
    void rejectsHistoricNativeDecoderCrashVector() throws IOException {
        // Mozilla bug 1246742, attachment 8717115: a 20-byte stream that underflowed the native decoder.
        byte[] encoded = HexFormat.of().parseHex("728a8a92aa8a8a3800002492e2c08a8a1f012101");
        try (var input = new BrotliInputStream(new ByteArrayInputStream(encoded))) {
            IOException failure = assertThrows(IOException.class, () -> input.transferTo(OutputStream.nullOutputStream()));
            assertThat(failure.getCause(), instanceOf(BrotliException.class));
        }
    }

    @Test
    @DisplayName("CVE-2024-32976: trailing data after a complete Brotli stream is rejected")
    void rejectsBufferedTrailingData() throws IOException {
        // A complete "my data" stream followed by the five extra bytes in Envoy's regression case.
        byte[] encoded = HexFormat.of().parseHex("0303806d792064617461034142434445");
        try (var input = new BrotliInputStream(new ByteArrayInputStream(encoded))) {
            IOException failure = assertThrows(IOException.class, input::readAllBytes);
            assertThat(failure.getCause(), instanceOf(BrotliException.class));
        }
    }

    @Test
    void verifiedInputEndRejectsIncrementallyDeliveredTrailingData() throws IOException {
        byte[] encoded = HexFormat.of().parseHex("0303806d792064617461034142434445");
        var source = new ByteArrayInputStream(encoded) {
            @Override
            public int read(byte[] bytes, int offset, int length) {
                return super.read(bytes, offset, Math.min(length, 1));
            }
        };
        try (var input = new BrotliInputStream(source, BrotliInputStreamConfig.builder().verifyEndOfInput(true).build())) {
            IOException failure = assertThrows(IOException.class, input::readAllBytes);
            assertThat(failure.getCause(), instanceOf(BrotliException.class));
            assertThat(failure.getCause().getMessage(), is("Unused bytes after end"));
            assertThat(source.available(), is(4));
            assertThrows(IOException.class, input::read);
        }
    }

    @Test
    @DisplayName("RFC 7932 section 9.2: uncompressed blocks decode across input-buffer and EOF boundaries")
    void decodesUncompressedBlocksAtInputBoundary() throws IOException {
        int[][] schedules = {{Integer.MAX_VALUE, 8192, 256}, {1, 1, 1}, {7, 31, 17}, {4095, 4096, 256}};
        for (int length : new int[] {1, 2, 3, 4, 5, 6, 7, 8, 4091, 4092, 4093, 16384}) {
            byte[] expected = new byte[length];
            Arrays.fill(expected, (byte) 'A');
            byte[] encoded = uncompressedBlock(length);
            for (int[] schedule : schedules) {
                String description = "payload length " + length + ", source/read/cache " + Arrays.toString(schedule);
                assertThat(description, decode(encoded, schedule[0], schedule[1], schedule[2]), is(expected));
            }
        }
    }

    @Test
    @DisplayName("A declared metablock length cannot reserve an output window before decoding")
    void truncatedMetablockDoesNotReserveOutputWindow() throws Exception {
        // RFC 7932 section 9.2 defines the length field; the allocation bound is a decoder policy.
        // Query public JDK management APIs without adding a dependency to the production JPMS module.
        var managementFactory = Class.forName("java.lang.management.ManagementFactory");
        Object allocationBean = managementFactory.getMethod("getThreadMXBean").invoke(null);
        var threadMxBean = Class.forName("com.sun.management.ThreadMXBean");
        assumeTrue(threadMxBean.isInstance(allocationBean), "Thread allocation measurements are unavailable");
        assumeTrue((boolean) threadMxBean.getMethod("isThreadAllocatedMemoryEnabled").invoke(allocationBean),
                   "Thread allocation measurements are disabled");
        var getAllocatedBytes = threadMxBean.getMethod("getThreadAllocatedBytes", long.class);

        byte[] encoded = {(byte) 0xCF, (byte) 0xFF, (byte) 0xFF, (byte) 0x7F};
        try (var input = new BrotliInputStream(new ByteArrayInputStream(encoded))) {
            long threadId = Thread.currentThread().threadId();
            long before = (long) getAllocatedBytes.invoke(allocationBean, threadId);
            assertThrows(IOException.class, input::readAllBytes);
            long allocated = (long) getAllocatedBytes.invoke(allocationBean, threadId) - before;
            assertThat("bytes allocated before any decoded output", allocated, lessThan(1L << 20));
        }
    }

    @Test
    @DisplayName("RFC 7932 sections 9.2 and 9.3: truncated data and a missing final block are invalid")
    void rejectsTruncatedUncompressedBlock() throws IOException {
        byte[] encoded = uncompressedBlock(8);
        for (int length = 0; length < encoded.length; length++) {
            byte[] truncated = Arrays.copyOf(encoded, length);
            try (var input = new BrotliInputStream(new ByteArrayInputStream(truncated))) {
                assertThrows(IOException.class, input::readAllBytes, "encoded prefix length " + length);
            }
        }
    }

    @Test
    @DisplayName("RFC 7932 section 9.2: fill bits after the final empty block must be zero")
    void rejectsNonzeroTerminalPadding() throws IOException {
        byte[] encoded = uncompressedBlock(8);
        encoded[encoded.length - 1] |= (byte) 0x80;
        try (var input = new BrotliInputStream(new ByteArrayInputStream(encoded))) {
            assertThrows(IOException.class, input::readAllBytes);
        }
    }

    private static byte[] repetitiveData(int length) {
        byte[] pattern = "abracadabra\0".getBytes(StandardCharsets.US_ASCII);
        byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            data[i] = pattern[i % pattern.length];
        }
        return data;
    }

    private static byte[] uncompressedBlock(int length) {
        // RFC 7932 section 9.2: WBITS=16, ISLAST=0, MNIBBLES=4, MLEN=length-1, ISUNCOMPRESSED=1.
        // The three-byte header is padded to a byte boundary. The last byte is ISLAST=1, ISLASTEMPTY=1.
        // At length 4092 this yields b0ff10 + 4092 copies of 41 + 03, independently accepted by Google Brotli 1.2.1.
        int header = ((length - 1) << 4) | (1 << 20);
        byte[] encoded = new byte[length + 4];
        encoded[0] = (byte) header;
        encoded[1] = (byte) (header >>> 8);
        encoded[2] = (byte) (header >>> 16);
        Arrays.fill(encoded, 3, 3 + length, (byte) 'A');
        encoded[encoded.length - 1] = 3;
        return encoded;
    }

    private static byte[] decode(byte[] encoded) throws IOException {
        return decode(encoded, Integer.MAX_VALUE, 8192, 256);
    }

    private static byte[] decode(byte[] encoded, int sourceChunk, int readChunk, int cacheSize) throws IOException {
        var destination = new ByteArrayOutputStream();
        var config = BrotliInputStreamConfig.builder().cacheBufferSize(Size.create(cacheSize)).build();
        try (var input = new BrotliInputStream(new ChunkedInputStream(encoded, sourceChunk), config)) {
            if (readChunk == 1) {
                for (int value; (value = input.read()) != -1;) {
                    destination.write(value);
                }
            } else {
                byte[] buffer = new byte[readChunk];
                for (int count; (count = input.read(buffer)) != -1;) {
                    destination.write(buffer, 0, count);
                }
            }
        }
        return destination.toByteArray();
    }

    private static final class ChunkedInputStream extends ByteArrayInputStream {
        private final int chunkSize;

        private ChunkedInputStream(byte[] data, int chunkSize) {
            super(data);
            this.chunkSize = chunkSize;
        }

        @Override
        public int read(byte[] destination, int offset, int length) {
            return super.read(destination, offset, Math.min(length, chunkSize));
        }
    }
}
