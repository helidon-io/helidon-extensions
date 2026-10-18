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

package io.helidon.extensions.langchain4j.examples.assistant;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.helidon.config.Config;
import io.helidon.config.ConfigException;
import io.helidon.config.ConfigSources;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DocsIngestorTest {
    private final TestEmbeddingModel model = new TestEmbeddingModel();
    private final InMemoryEmbeddingStore<TextSegment> seStore = new InMemoryEmbeddingStore<>();
    private final InMemoryEmbeddingStore<TextSegment> mpStore = new InMemoryEmbeddingStore<>();

    @TempDir
    Path docsPath;

    @Test
    void ingestsMarkdownAndAsciiDocIntoSeparateFlavorStoresWithRelativeSourceMetadata() {
        write("se/guides/http.adoc", "Helidon SE uses virtual threads. Café.");
        write("mp/config.md", "Helidon MP supports MicroProfile Config.");
        write("se/ignored.txt", "This file must not be embedded.");
        write("se/empty.adoc", " \n\t");

        ingestor(Map.of()).ingest();

        List<TextSegment> se = segments(seStore);
        List<TextSegment> mp = segments(mpStore);
        assertThat(se, hasSize(1));
        assertThat(mp, hasSize(1));
        assertThat(se.getFirst().text(), is("Helidon SE uses virtual threads. Café."));
        assertThat(se.getFirst().metadata().getString("source"), is("se/guides/http.adoc"));
        assertThat(se.getFirst().metadata().getString("flavor"), is("se"));
        assertThat(mp.getFirst().text(), is("Helidon MP supports MicroProfile Config."));
        assertThat(mp.getFirst().metadata().getString("source"), is("mp/config.md"));
        assertThat(mp.getFirst().metadata().getString("flavor"), is("mp"));
    }

    @Test
    void splitsLongDocumentsAndBoundsEmbeddingBatches() {
        write("se/long.adoc", "Helidon uses virtual threads for concurrent requests.\n\n".repeat(100));
        write("mp/config.adoc", "MicroProfile Config.");

        ingestor(Map.of("assistant.chunk-size", "80", "assistant.chunk-overlap", "10")).ingest();

        List<TextSegment> segments = segments(seStore);
        assertThat(segments.size(), greaterThan(32));
        assertThat(segments.stream().map(segment -> segment.text().length()).toList(), everyItem(lessThanOrEqualTo(80)));
        assertThat(segments.stream().map(segment -> segment.metadata().getString("source")).distinct().toList(),
                   contains("se/long.adoc"));
        assertThat(model.batchSizes, everyItem(lessThanOrEqualTo(32)));
        assertThat(model.batchSizes.stream().mapToInt(Integer::intValue).sum(), is(segments.size() + 1));
    }

    @Test
    void requiresDocumentationPath() {
        Config config = Config.empty();
        var error = assertThrows(ConfigException.class, () -> new DocsIngestor(config, model, seStore, mpStore));
        assertThat(error.getMessage(), containsString("assistant.docs-path"));
    }

    @Test
    void requiresBothFlavorDirectoriesBeforeEmbedding() {
        write("se/guide.adoc", "Helidon SE.");

        var error = assertThrows(ConfigException.class, () -> ingestor(Map.of()).ingest());

        assertThat(error.getMessage(), containsString("mp"));
        assertThat(model.batchSizes, hasSize(0));
    }

    @Test
    void rejectsEmptyDocumentation() {
        write("se/empty.adoc", " \n");
        write("mp/guide.adoc", "Helidon MP.");

        var error = assertThrows(ConfigException.class, () -> ingestor(Map.of()).ingest());

        assertThat(error.getMessage(), containsString("No non-empty .adoc or .md"));
        assertThat(model.batchSizes, hasSize(0));
    }

    @Test
    void rejectsOversizedDocumentsBeforeEmbedding() {
        write("se/large.adoc", "a".repeat(65));
        write("mp/guide.adoc", "Helidon MP.");

        var error = assertThrows(ConfigException.class,
                                 () -> ingestor(Map.of("assistant.max-file-bytes", "64")).ingest());

        assertThat(error.getMessage(), containsString("assistant.max-file-bytes"));
        assertThat(model.batchSizes, hasSize(0));
    }

    @Test
    void rejectsInvalidChunkAndFileLimits() {
        assertThrows(ConfigException.class, () -> ingestor(Map.of("assistant.chunk-size", "0")));
        assertThrows(ConfigException.class, () -> ingestor(Map.of("assistant.chunk-overlap", "-1")));
        assertThrows(ConfigException.class, () -> ingestor(Map.of("assistant.chunk-overlap", "1000")));
        assertThrows(ConfigException.class, () -> ingestor(Map.of("assistant.max-file-bytes", "0")));
        assertThrows(ConfigException.class, () -> ingestor(Map.of("assistant.max-file-bytes", "2147483647")));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void ignoresSymbolicFilesAndDirectories() {
        write("se/guide.adoc", "Helidon SE.");
        write("mp/guide.adoc", "Helidon MP.");
        write("outside/private.adoc", "Do not ingest this documentation.");
        try {
            Files.createSymbolicLink(docsPath.resolve("se/linked.adoc"), docsPath.resolve("outside/private.adoc"));
            Files.createSymbolicLink(docsPath.resolve("se/linked-directory"), docsPath.resolve("outside"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        ingestor(Map.of()).ingest();

        assertThat(segments(seStore).stream().map(TextSegment::text).toList(), contains("Helidon SE."));
    }

    private static List<TextSegment> segments(InMemoryEmbeddingStore<TextSegment> store) {
        return store.search(EmbeddingSearchRequest.builder()
                                    .queryEmbedding(Embedding.from(new float[] {1, 0}))
                                    .maxResults(1000)
                                    .build())
                .matches().stream().map(match -> match.embedded()).toList();
    }

    private DocsIngestor ingestor(Map<String, String> overrides) {
        Map<String, String> properties = new HashMap<>(overrides);
        properties.put("assistant.docs-path", docsPath.toString());
        return new DocsIngestor(Config.just(ConfigSources.create(properties)), model, seStore, mpStore);
    }

    private void write(String relativePath, String text) {
        Path path = docsPath.resolve(relativePath);
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final class TestEmbeddingModel implements EmbeddingModel {
        private final List<Integer> batchSizes = new ArrayList<>();

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            batchSizes.add(segments.size());
            return Response.from(segments.stream().map(_ -> Embedding.from(new float[] {1, 0})).toList());
        }
    }
}
