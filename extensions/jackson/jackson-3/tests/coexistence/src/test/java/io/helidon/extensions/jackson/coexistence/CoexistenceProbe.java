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

package io.helidon.extensions.jackson.coexistence;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;

import io.helidon.common.GenericType;
import io.helidon.common.media.type.MediaTypes;
import io.helidon.config.Config;
import io.helidon.config.ConfigException;
import io.helidon.config.ConfigSources;
import io.helidon.http.WritableHeaders;
import io.helidon.http.media.MediaContext;
import io.helidon.http.media.MediaSupport;
import io.helidon.http.media.spi.MediaSupportProvider;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Isolated process entry point. Only public APIs are used; no mapper internals are inspected.
 */
public final class CoexistenceProbe {
    private static final GenericType<Bean> BEAN = GenericType.create(Bean.class);

    private CoexistenceProbe() {
    }

    public static void main(String[] args) throws Exception {
        List<String> keys = Arrays.asList(args[0].split(","));
        List<MediaSupportProvider> providers = ServiceLoader.load(MediaSupportProvider.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .filter(provider -> provider.configKey().startsWith("jackson"))
                .toList();
        assertThat("exact provider configuration keys", providers.stream().map(MediaSupportProvider::configKey).toList(),
                   containsInAnyOrder(keys.toArray(String[]::new)));

        String winner = keys.contains("jackson-3") ? "jackson-3" : "jackson-2";
        verify(MediaContext.create(), winner, false);
        for (String key : keys) {
            // Disable automatic providers, but retain every integration on the runtime path.
            Config config = Config.create(ConfigSources.create(Map.of("media-supports-discover-services", "false",
                                                                      "media-supports.0.type", key,
                                                                      "media-supports.0.name", "selected")));
            verify(MediaContext.create(config), key, true);

            MediaSupport support = programmatic(key);
            // Explicit supports take precedence even when automatic discovery remains enabled.
            verify(MediaContext.builder().addMediaSupport(support).build(), key, false);
        }

        // There is no implicit migration alias: an absent integration's key must not select another one.
        for (String absent : List.of("jackson", "jackson-2", "jackson-3")) {
            if (!keys.contains(absent)) {
                assertThrows(ClassNotFoundException.class, () -> Class.forName(supportClass(absent)),
                             "integration must be absent: " + absent);
                Config config = Config.create(ConfigSources.create(Map.of("media-supports-discover-services", "false",
                                                                          "media-supports.0.type", absent)));
                assertThrows(ConfigException.class, () -> MediaContext.create(config), absent);
            }
        }
        System.out.println("PASS " + args[0]);
    }

    private static MediaSupport programmatic(String key) throws Exception {
        String className = supportClass(key);
        // The public factory is resolved lazily so the same compiled probe can run with either integration absent.
        return (MediaSupport) Class.forName(className).getMethod("create").invoke(null);
    }

    private static void verify(MediaContext context, String key, boolean onlySelected) {
        List<MediaSupport> supports = context.prototype().mediaSupports().stream()
                .filter(support -> support.type().startsWith("jackson"))
                .toList();
        assertThat("first Jackson support", supports.getFirst().getClass().getName(), is(supportClass(key)));
        if (onlySelected) {
            assertThat("explicit configuration must select only its own key", supports, hasSize(1));
            assertThat(supports.getFirst().name(), is("selected"));
        }

        Bean value = new Bean();
        value.zebra = "last";
        value.alpha = "first";
        value.count = 7;
        String json = write(context, BEAN, value);
        assertThat("default property ordering for " + key, json,
                   is("{\"zebra\":\"last\",\"alpha\":\"first\",\"count\":7}"));
        Bean roundTrip = read(context, BEAN, json);
        assertThat(roundTrip.alpha, is("first"));
        assertThat(roundTrip.zebra, is("last"));
        assertThat(roundTrip.count, is(7));

        RuntimeException failure = assertThrows(RuntimeException.class,
                                               () -> read(context, BEAN, "{\"unknown\":true}"));
        assertThat("selected reader", failure.getClass().getName(),
                   is(supportClass(key).replace("JacksonSupport", "JacksonRuntimeException")));
        assertThat(read(context, BEAN, "{\"count\":null}").count, is(0));

        GenericType<Optional<String>> optionalType = new GenericType<>() { };
        assertThat(write(context, optionalType, Optional.of("present")), is("\"present\""));
        assertThat(read(context, optionalType, "\"present\""), is(Optional.of("present")));
        assertThat(read(context, optionalType, "null"), is(Optional.empty()));
        GenericType<LocalDate> dateType = GenericType.create(LocalDate.class);
        LocalDate date = LocalDate.of(2026, 10, 27);
        assertThat(read(context, dateType, write(context, dateType, date)), is(date));
        GenericType<Choice> enumType = GenericType.create(Choice.class);
        assertThat(write(context, enumType, Choice.FIRST), is("\"FIRST\""));
        assertThat(read(context, enumType, "\"FIRST\""), is(Choice.FIRST));
    }

    private static String supportClass(String key) {
        return switch (key) {
            case "jackson" -> "io.helidon.http.media.jackson.JacksonSupport";
            case "jackson-2" -> "io.helidon.extensions.jackson.jackson2.http.media.JacksonSupport";
            case "jackson-3" -> "io.helidon.extensions.jackson.jackson3.http.media.JacksonSupport";
            default -> throw new IllegalArgumentException(key);
        };
    }

    private static <T> String write(MediaContext context, GenericType<T> type, T value) {
        WritableHeaders<?> headers = WritableHeaders.create();
        headers.contentType(MediaTypes.APPLICATION_JSON);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        context.writer(type, headers).write(type, value, output, headers);
        return output.toString(StandardCharsets.UTF_8);
    }

    private static <T> T read(MediaContext context, GenericType<T> type, String json) {
        WritableHeaders<?> headers = WritableHeaders.create();
        headers.contentType(MediaTypes.APPLICATION_JSON);
        return context.reader(type, headers).read(type,
                                                 new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)),
                                                 headers);
    }

    public enum Choice {
        FIRST
    }

    public static final class Bean {
        public String zebra;
        public String alpha;
        public int count;
    }
}
