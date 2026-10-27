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

package io.helidon.extensions.brotli.http.encoding;

import java.io.OutputStream;
import java.util.Objects;
import java.util.Set;

import io.helidon.extensions.brotli.BrotliInputStream;
import io.helidon.extensions.brotli.BrotliOutputStream;
import io.helidon.http.Header;
import io.helidon.http.HeaderNames;
import io.helidon.http.HeaderValues;
import io.helidon.http.WritableHeaders;
import io.helidon.http.encoding.ContentDecoder;
import io.helidon.http.encoding.ContentEncoder;
import io.helidon.http.encoding.ContentEncoding;

/**
 * Pure Java Brotli content encoding for Helidon HTTP clients and servers.
 * <p>
 * The content coding identifier is {@code br}. Compression uses the codec's
 * supported quality level, {@code 0}.
 */
public final class BrotliEncoding implements ContentEncoding {
    private static final Header CONTENT_ENCODING_BROTLI = HeaderValues.createCached(HeaderNames.CONTENT_ENCODING,
                                                                                    false,
                                                                                    false,
                                                                                    "br");

    private final String name;

    BrotliEncoding(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    /**
     * Create Brotli content encoding with the default name, {@code br}.
     *
     * @return Brotli content encoding
     */
    public static BrotliEncoding create() {
        return new BrotliEncoding("br");
    }

    @Override
    public Set<String> ids() {
        return Set.of("br");
    }

    @Override
    public boolean supportsEncoding() {
        return true;
    }

    @Override
    public boolean supportsDecoding() {
        return true;
    }

    @Override
    public ContentDecoder decoder() {
        return network -> BrotliInputStream.builder().inputStream(network).build();
    }

    @Override
    public ContentEncoder encoder() {
        return new ContentEncoder() {
            @Override
            public OutputStream apply(OutputStream network) {
                return BrotliOutputStream.builder().outputStream(network).build();
            }

            @Override
            public void headers(WritableHeaders<?> headers) {
                headers.add(CONTENT_ENCODING_BROTLI);
                headers.remove(HeaderNames.CONTENT_LENGTH);
            }
        };
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String type() {
        return "br";
    }
}
