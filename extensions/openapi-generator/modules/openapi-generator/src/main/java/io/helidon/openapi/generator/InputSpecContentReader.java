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

package io.helidon.openapi.generator;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class InputSpecContentReader {

    private static final int INPUT_SPEC_CONNECT_TIMEOUT_MILLIS = 10_000;
    private static final int INPUT_SPEC_READ_TIMEOUT_MILLIS = 30_000;
    private static final int RAW_INPUT_SPEC_MAX_BYTES = 64 * 1024 * 1024;

    private InputSpecContentReader() {
    }

    static String read(String inputSpec) throws IOException {
        URI uri = toUri(inputSpec);
        if (uri != null && uri.getScheme() != null && !isWindowsDrivePath(inputSpec)) {
            String scheme = uri.getScheme().toLowerCase();
            if ("file".equals(scheme)) {
                return Files.readString(Path.of(uri), StandardCharsets.UTF_8);
            }

            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                throw new IOException("Unsupported input spec URI scheme for raw discriminator recovery: " + scheme);
            }

            URLConnection connection = uri.toURL().openConnection();
            connection.setConnectTimeout(INPUT_SPEC_CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(INPUT_SPEC_READ_TIMEOUT_MILLIS);
            connection.setUseCaches(false);
            try (InputStream inputStream = connection.getInputStream()) {
                return readBoundedInputSpecContent(inputStream);
            }
        }

        return Files.readString(Path.of(inputSpec), StandardCharsets.UTF_8);
    }

    private static String readBoundedInputSpecContent(InputStream inputStream) throws IOException {
        byte[] content = inputStream.readNBytes(RAW_INPUT_SPEC_MAX_BYTES + 1);
        if (content.length > RAW_INPUT_SPEC_MAX_BYTES) {
            throw new IOException("Input spec exceeds raw discriminator recovery limit of "
                    + RAW_INPUT_SPEC_MAX_BYTES + " bytes");
        }
        return new String(content, StandardCharsets.UTF_8);
    }

    private static URI toUri(String value) {
        try {
            return URI.create(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static boolean isWindowsDrivePath(String value) {
        return value != null
                && value.length() >= 2
                && Character.isLetter(value.charAt(0))
                && value.charAt(1) == ':';
    }
}
