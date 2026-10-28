/*
 * Copyright (c) 2023, 2026 Oracle and/or its affiliates.
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

package io.helidon.extensions.jackson.jackson3.http.media;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

import io.helidon.common.GenericType;
import io.helidon.common.media.type.MediaTypes;
import io.helidon.common.testing.http.junit5.HttpHeaderMatcher;
import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.http.ClientRequestHeaders;
import io.helidon.http.HeaderValues;
import io.helidon.http.HttpException;
import io.helidon.http.HttpMediaType;
import io.helidon.http.HttpMediaTypes;
import io.helidon.http.Status;
import io.helidon.http.WritableHeaders;
import io.helidon.http.media.EntityWriter;
import io.helidon.http.media.InstanceWriter;
import io.helidon.http.media.MediaContext;
import io.helidon.http.media.MediaSupport;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.hasItems;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/*
When adding/updating tests in this class, consider if it should be done
 in the following tests a well:
    - JsonbMediaTest
    - JsonpMediaTest
    - GsonMediaTest
 */
class JacksonMediaTest {
    private static final Charset ISO_8859_2 = Charset.forName("ISO-8859-2");
    private static final GenericType<Book> BOOK_TYPE = GenericType.create(Book.class);
    private static final GenericType<List<Book>> BOOK_LIST_TYPE = new GenericType<List<Book>>() { };
    private static final Instant INSTANT = Instant.now();
    private final MediaSupport support;

    JacksonMediaTest() {
        this.support = JacksonSupport.create(Config.empty());
        support.init(MediaContext.create());
    }

    @Test
    void testBuiltInJavaTypes() {
        var mapper = JacksonSupport.builder().build().prototype().objectMapper();
        var value = new JavaTypes(Optional.of("value"), Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(mapper.readValue(mapper.writeValueAsString(value), JavaTypes.class), is(value));
        assertThat(mapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES), is(true));
        assertThat(mapper.isEnabled(MapperFeature.DEFAULT_VIEW_INCLUSION), is(true));
        assertThat(mapper.isEnabled(MapperFeature.DETECT_PARAMETER_NAMES), is(true));
    }

    @Test
    void testPropertyOverridesDefault() {
        var mapper = JacksonSupport.builder()
                .putProperty("FAIL_ON_UNKNOWN_PROPERTIES", false)
                .putProperty("DEFAULT_VIEW_INCLUSION", false)
                .build().prototype().objectMapper();
        assertThat(mapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES), is(false));
        assertThat(mapper.isEnabled(MapperFeature.DEFAULT_VIEW_INCLUSION), is(false));
    }

    @Test
    void testFinalFieldMutationRejectedByDefault() {
        assertThrows(JacksonRuntimeException.class, () -> readRole(support));
    }

    @Test
    void testFinalFieldMutationDisabledWithOtherProperties() {
        var config = Config.create(ConfigSources.create(Map.of("properties.FAIL_ON_UNKNOWN_PROPERTIES", "false")));
        assertThat(readRole(JacksonSupport.create(config)), is("user"));
    }

    @Test
    void testFinalFieldMutationConfigured() {
        for (boolean enabled : List.of(false, true)) {
            var config = Config.create(ConfigSources.create(Map.of("properties.FAIL_ON_UNKNOWN_PROPERTIES", "false",
                                                                  "properties.ALLOW_FINAL_FIELDS_AS_MUTATORS",
                                                                  Boolean.toString(enabled))));
            assertThat("ALLOW_FINAL_FIELDS_AS_MUTATORS=" + enabled,
                       readRole(JacksonSupport.create(config)),
                       is(enabled ? "admin" : "user"));
        }
    }

    @Test
    void testFinalFieldMutationWithSuppliedMapper() {
        var mapper = JsonMapper.builder()
                .enable(MapperFeature.ALLOW_FINAL_FIELDS_AS_MUTATORS)
                .build();
        var mediaSupport = JacksonSupport.builder()
                .objectMapper(mapper)
                .putProperty("ALLOW_FINAL_FIELDS_AS_MUTATORS", false)
                .build();
        assertThat(readRole(mediaSupport), is("admin"));
    }

    @Test
    void testSuppliedMapperWinsOverProperties() {
        var mapper = JsonMapper.builder().build();
        var supplied = JacksonSupport.builder()
                .objectMapper(mapper)
                .putProperty("FAIL_ON_UNKNOWN_PROPERTIES", true)
                .build().prototype().objectMapper();
        assertThat(supplied, sameInstance(mapper));
        assertThat(supplied.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES), is(false));
    }

    @Test
    void testDateTimeProperty() {
        var mapper = JacksonSupport.builder()
                .putProperty("WRITE_DATES_AS_TIMESTAMPS", false)
                .build().prototype().objectMapper();
        assertThat(mapper.writeValueAsString(Instant.parse("2026-01-01T00:00:00Z")),
                   is("\"2026-01-01T00:00:00Z\""));
    }

    @Test
    void testMalformedJson() {
        var headers = WritableHeaders.create().contentType(MediaTypes.APPLICATION_JSON);
        assertThrows(JacksonRuntimeException.class,
                     () -> support.reader(BOOK_TYPE, headers).supplier().get()
                             .read(BOOK_TYPE, new ByteArrayInputStream("{".getBytes(StandardCharsets.UTF_8)), headers));
    }

    @Test
    void testWriteSingle() {
        WritableHeaders<?> headers = WritableHeaders.create();

        MediaSupport.WriterResponse<Book> res = support.writer(BOOK_TYPE, headers);
        assertThat(res.support(), is(MediaSupport.SupportLevel.COMPATIBLE));
        EntityWriter<Book> writer = res.supplier().get();
        assertThat(writer.supportsInstanceWriter(), is(true));
        assertInstanceWriter(writer.instanceWriter(BOOK_TYPE, new Book("test-title"), headers), "test-title");

        ByteArrayOutputStream os = new ByteArrayOutputStream();

        writer.write(BOOK_TYPE, new Book("test-title"), os, headers);

        assertThat(headers, HttpHeaderMatcher.hasHeader(HeaderValues.CONTENT_TYPE_JSON));
        String result = os.toString(StandardCharsets.UTF_8);
        assertThat(result, containsString("\"title\""));
        assertThat(result, containsString("\"test-title\""));

        // sanity check, parse back to book
        Book sanity = support.reader(BOOK_TYPE, headers)
                .supplier()
                .get()
                .read(BOOK_TYPE, new ByteArrayInputStream(os.toByteArray()), headers);

        assertThat(sanity.getTitle(), is("test-title"));
    }

    @Test
    void testWriteServerInstance() {
        WritableHeaders<?> requestHeaders = WritableHeaders.create();
        WritableHeaders<?> responseHeaders = WritableHeaders.create();

        MediaSupport.WriterResponse<Book> res = support.writer(BOOK_TYPE, requestHeaders, responseHeaders);
        assertThat(res.support(), is(MediaSupport.SupportLevel.COMPATIBLE));
        EntityWriter<Book> writer = res.supplier().get();

        assertThat(writer.supportsInstanceWriter(), is(true));
        assertInstanceWriter(writer.instanceWriter(BOOK_TYPE,
                                                   new Book("server-title"),
                                                   requestHeaders,
                                                   responseHeaders),
                             "server-title");
        assertThat(responseHeaders, HttpHeaderMatcher.hasHeader(HeaderValues.CONTENT_TYPE_JSON));
    }

    @Test
    void testWriteList() {
        WritableHeaders<?> headers = WritableHeaders.create();

        MediaSupport.WriterResponse<List<Book>> res = support.writer(BOOK_LIST_TYPE, headers);
        assertThat(res.support(), is(MediaSupport.SupportLevel.COMPATIBLE));

        ByteArrayOutputStream os = new ByteArrayOutputStream();

        List<Book> books = List.of(new Book("first"), new Book("second"), new Book("third"));
        res.supplier().get()
                .write(BOOK_LIST_TYPE, books, os, headers);

        assertThat(headers, HttpHeaderMatcher.hasHeader(HeaderValues.CONTENT_TYPE_JSON));
        String result = os.toString(StandardCharsets.UTF_8);
        assertThat(result, containsString("\"title\""));
        assertThat(result, containsString("\"first\""));
        assertThat(result, containsString("\"second\""));
        assertThat(result, containsString("\"third\""));

        // sanity check, parse back to books
        List<Book> sanity = support.reader(BOOK_LIST_TYPE, headers)
                .supplier()
                .get()
                .read(BOOK_LIST_TYPE, new ByteArrayInputStream(os.toByteArray()), headers);

        assertThat(sanity, hasItems(new Book("first"), new Book("second"), new Book("third")));
    }

    @Test
    void testReadServerSingle() {
        WritableHeaders<?> requestHeaders = WritableHeaders.create();
        requestHeaders.contentType(MediaTypes.APPLICATION_JSON);

        MediaSupport.ReaderResponse<Book> res = support.reader(BOOK_TYPE, requestHeaders);
        assertThat(res.support(), is(MediaSupport.SupportLevel.COMPATIBLE));

        InputStream is = new ByteArrayInputStream("{\"title\": \"utf-8: řžýčň\"}".getBytes(StandardCharsets.UTF_8));
        Book book = res.supplier().get()
                .read(BOOK_TYPE, is, requestHeaders);

        assertThat(book.getTitle(), is("utf-8: řžýčň"));
    }

    @Test
    void testReadClientSingle() {
        WritableHeaders<?> requestHeaders = WritableHeaders.create();
        WritableHeaders<?> responseHeaders = WritableHeaders.create();
        requestHeaders.contentType(MediaTypes.APPLICATION_XML);
        responseHeaders.contentType(MediaTypes.APPLICATION_JSON);

        MediaSupport.ReaderResponse<Book> res = support.reader(BOOK_TYPE, requestHeaders, responseHeaders);
        assertThat(res.support(), is(MediaSupport.SupportLevel.COMPATIBLE));

        InputStream is = new ByteArrayInputStream("{\"title\": \"utf-8: řžýčň\"}".getBytes(StandardCharsets.UTF_8));
        Book book = res.supplier().get()
                .read(BOOK_TYPE, is, requestHeaders, responseHeaders);

        assertThat(book.getTitle(), is("utf-8: řžýčň"));
    }

    @Test
    void testReadServerList() {
        WritableHeaders<?> requestHeaders = WritableHeaders.create();
        requestHeaders.contentType(MediaTypes.APPLICATION_JSON);

        MediaSupport.ReaderResponse<List<Book>> res = support.reader(BOOK_LIST_TYPE, requestHeaders);
        assertThat(res.support(), is(MediaSupport.SupportLevel.COMPATIBLE));

        InputStream is =
                new ByteArrayInputStream("[{\"title\": \"first\"}, {\"title\": \"second\"}]".getBytes(StandardCharsets.UTF_8));
        List<Book> books = res.supplier().get()
                .read(BOOK_LIST_TYPE, is, requestHeaders);

        assertThat(books, hasItems(new Book("first"), new Book("second")));
    }

    @Test
    void testReadServerSingleNonUtf8() {
        WritableHeaders<?> requestHeaders = WritableHeaders.create();
        requestHeaders.contentType(HttpMediaType.create(MediaTypes.APPLICATION_JSON).withCharset(ISO_8859_2));

        MediaSupport.ReaderResponse<Book> res = support.reader(BOOK_TYPE, requestHeaders);
        assertThat(res.support(), is(MediaSupport.SupportLevel.COMPATIBLE));

        InputStream is = new ByteArrayInputStream("{\"title\": \"is-8859-2: řžýčň\"}".getBytes(ISO_8859_2));
        Book book = res.supplier().get()
                .read(BOOK_TYPE, is, requestHeaders);

        assertThat(book.getTitle(), is("is-8859-2: řžýčň"));
    }

    @Test
    void testReadClientSingleNonUtf8() {
        WritableHeaders<?> requestHeaders = WritableHeaders.create();
        WritableHeaders<?> responseHeaders = WritableHeaders.create();
        requestHeaders.contentType(MediaTypes.APPLICATION_XML);
        responseHeaders.contentType(HttpMediaType.create(MediaTypes.APPLICATION_JSON).withCharset(ISO_8859_2));

        MediaSupport.ReaderResponse<Book> res = support.reader(BOOK_TYPE, requestHeaders, responseHeaders);
        assertThat(res.support(), is(MediaSupport.SupportLevel.COMPATIBLE));

        InputStream is = new ByteArrayInputStream("{\"title\": \"utf-8: řžýčň\"}".getBytes(ISO_8859_2));
        Book book = res.supplier().get()
                .read(BOOK_TYPE, is, requestHeaders, responseHeaders);

        assertThat(book.getTitle(), is("utf-8: řžýčň"));
    }

    @Test
    void testReadServerListNonUtf8() {
        WritableHeaders<?> requestHeaders = WritableHeaders.create();
        requestHeaders.contentType(HttpMediaType.create(MediaTypes.APPLICATION_JSON).withCharset(ISO_8859_2));

        MediaSupport.ReaderResponse<List<Book>> res = support.reader(BOOK_LIST_TYPE, requestHeaders);
        assertThat(res.support(), is(MediaSupport.SupportLevel.COMPATIBLE));

        InputStream is =
                new ByteArrayInputStream("[{\"title\": \"čř\"}, {\"title\": \"šň\"}]".getBytes(ISO_8859_2));
        List<Book> books = res.supplier().get()
                .read(BOOK_LIST_TYPE, is, requestHeaders);

        assertThat(books, hasItems(new Book("čř"), new Book("šň")));
    }

    @Test
    void testReadServerUtf8() {
        WritableHeaders<?> responseHeaders = WritableHeaders.create();
        ClientRequestHeaders headers = ClientRequestHeaders.create(WritableHeaders.create());
        headers.accept(HttpMediaTypes.JSON_UTF_8);

        MediaSupport.WriterResponse<Book> res = support.writer(BOOK_TYPE, headers, responseHeaders);

        assertThat(res.support(), is(MediaSupport.SupportLevel.COMPATIBLE));

        ByteArrayOutputStream bos = new ByteArrayOutputStream();

        res.supplier()
                .get()
                .write(BOOK_TYPE, new Book("čr"), bos, headers, responseHeaders);

        assertThat(bos.size(), not(0));
    }

    @Test
    void testCustomInstance() {
        ObjectMapper objectMapper = JsonMapper.builderWithJackson2Defaults()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        MediaSupport jacksonSupport = JacksonSupport.create(objectMapper);

        WritableHeaders<?> requestHeaders = WritableHeaders.create();
        requestHeaders.contentType(MediaTypes.APPLICATION_JSON);

        MediaSupport.ReaderResponse<Book> res = jacksonSupport.reader(BOOK_TYPE, requestHeaders);
        assertThat(res.support(), is(MediaSupport.SupportLevel.COMPATIBLE));

        InputStream is =
                new ByteArrayInputStream("{\"title\": \"Some Test\", \"unknown\": \"value\"}".getBytes(StandardCharsets.UTF_8));
        Book book = res.supplier().get()
                .read(BOOK_TYPE, is, requestHeaders);

        assertThat(book.getTitle(), is("Some Test"));
    }

    @Test
    void testCustomInstanceFromConfiguration() {
        Config config = Config.create();
        MediaSupport jacksonSupport = JacksonSupport.create(config);

        WritableHeaders<?> requestHeaders = WritableHeaders.create();
        requestHeaders.contentType(MediaTypes.APPLICATION_JSON);

        MediaSupport.ReaderResponse<Book> res = jacksonSupport.reader(BOOK_TYPE, requestHeaders);
        assertThat(res.support(), is(MediaSupport.SupportLevel.COMPATIBLE));

        InputStream is =
                new ByteArrayInputStream("{\"title\": \"Some Test\", \"unknown\": \"value\"}".getBytes(StandardCharsets.UTF_8));
        Book book = res.supplier().get()
                .read(BOOK_TYPE, is, requestHeaders);

        assertThat(book.getTitle(), is("Some Test"));
    }

    @Test
    void testReadServerWrongCharset() {
        WritableHeaders<?> requestHeaders = WritableHeaders.create();
        WritableHeaders<?> responseHeaders = WritableHeaders.create();
        requestHeaders.contentType(MediaTypes.APPLICATION_XML);
        responseHeaders.contentType(HttpMediaType.create(MediaTypes.APPLICATION_JSON).withCharset("xxxxxxx"));

        MediaSupport.ReaderResponse<Book> res = support.reader(BOOK_TYPE, requestHeaders, responseHeaders);
        assertThat(res.support(), is(MediaSupport.SupportLevel.COMPATIBLE));

        InputStream is = new ByteArrayInputStream("{\"title\": \"utf-8: řžýčň\"}".getBytes(ISO_8859_2));
        var httpException = assertThrows(HttpException.class, () -> res.supplier().get()
                .read(BOOK_TYPE, is, requestHeaders, responseHeaders));

        assertThat(httpException.status(), is(Status.UNSUPPORTED_MEDIA_TYPE_415));
    }

    private String readRole(MediaSupport mediaSupport) {
        var type = GenericType.create(RequestDto.class);
        var headers = WritableHeaders.create().contentType(MediaTypes.APPLICATION_JSON);
        var body = new ByteArrayInputStream("{\"role\":\"admin\"}".getBytes(StandardCharsets.UTF_8));
        return mediaSupport.reader(type, headers).supplier().get().read(type, body, headers).getRole();
    }

    private void assertInstanceWriter(InstanceWriter writer, String expectedTitle) {
        byte[] bytes = writer.instanceBytes();
        assertThat(writer.alwaysInMemory(), is(true));
        assertThat(writer.contentLength(), is(OptionalLong.of(bytes.length)));

        ByteArrayOutputStream os = new ByteArrayOutputStream();
        writer.write(os);
        assertThat(os.toByteArray(), is(bytes));

        String result = new String(bytes, StandardCharsets.UTF_8);
        assertThat(result, containsString("\"title\""));
        assertThat(result, containsString("\"" + expectedTitle + "\""));
    }

    public record JavaTypes(Optional<String> value, Instant timestamp) {
    }

    public static class RequestDto {
        private final String role;

        public RequestDto() {
            role = "user";
        }

        public String getRole() {
            return role;
        }
    }

    public static class Book {
        private String title;

        public Book() {
        }

        public Book(String title) {
            this.title = title;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            Book book = (Book) o;
            return Objects.equals(title, book.title);
        }

        @Override
        public int hashCode() {
            return Objects.hash(title);
        }

        @Override
        public String toString() {
            return title;
        }
    }
}
