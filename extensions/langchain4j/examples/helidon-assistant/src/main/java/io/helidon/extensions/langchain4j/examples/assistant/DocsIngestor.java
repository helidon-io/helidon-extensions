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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

import io.helidon.config.Config;
import io.helidon.config.ConfigException;
import io.helidon.service.registry.Service;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;

@Service.Singleton
@Service.RunLevel(Service.RunLevel.STARTUP)
class DocsIngestor {
    private static final int EMBEDDING_BATCH_SIZE = 32;

    private final Path docsPath;
    private final int maxFileBytes;
    private final DocumentSplitter splitter;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> seStore;
    private final EmbeddingStore<TextSegment> mpStore;

    @Service.Inject
    DocsIngestor(Config config,
                 @Service.Named("docs-embedding-model") EmbeddingModel embeddingModel,
                 @Service.Named("se-docs") EmbeddingStore<TextSegment> seStore,
                 @Service.Named("mp-docs") EmbeddingStore<TextSegment> mpStore) {
        Config assistant = config.get("assistant");
        String path = assistant.get("docs-path").asString().asOptional()
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new ConfigException("assistant.docs-path must point to the documentation directory"));
        docsPath = Path.of(path).toAbsolutePath().normalize();
        int chunkSize = assistant.get("chunk-size").asInt().orElse(1000);
        int chunkOverlap = assistant.get("chunk-overlap").asInt().orElse(100);
        maxFileBytes = assistant.get("max-file-bytes").asInt().orElse(1024 * 1024);
        if (chunkSize <= 0 || chunkOverlap < 0 || chunkOverlap >= chunkSize) {
            throw new ConfigException("assistant.chunk-size must be positive and assistant.chunk-overlap must be between 0"
                                              + " and chunk-size - 1");
        }
        if (maxFileBytes <= 0 || maxFileBytes == Integer.MAX_VALUE) {
            throw new ConfigException("assistant.max-file-bytes must be between 1 and " + (Integer.MAX_VALUE - 1));
        }
        splitter = DocumentSplitters.recursive(chunkSize, chunkOverlap);
        this.embeddingModel = embeddingModel;
        this.seStore = seStore;
        this.mpStore = mpStore;
    }

    @Service.PostConstruct
    void ingest() {
        requireDirectory(docsPath);
        requireDirectory(docsPath.resolve("se"));
        requireDirectory(docsPath.resolve("mp"));
        ingest("se", seStore);
        ingest("mp", mpStore);
    }

    private static void requireDirectory(Path path) {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new ConfigException("Documentation directory does not exist or is a symbolic link: " + path);
        }
    }

    private void ingest(String flavor, EmbeddingStore<TextSegment> store) {
        Path directory = docsPath.resolve(flavor);
        int segmentCount = 0;
        // Files.walk does not follow symbolic links. Check files with the same policy before opening them.
        try (var paths = Files.walk(directory)) {
            List<Path> documents = paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.getFileName().toString().endsWith(".adoc")
                            || path.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .toList();
            for (Path path : documents) {
                String text = readDocument(path);
                if (text.isBlank()) {
                    continue;
                }
                Metadata metadata = Metadata.from("source", docsPath.relativize(path).toString().replace('\\', '/'));
                metadata.put("flavor", flavor);
                List<TextSegment> segments = splitter.split(Document.from(text, metadata));
                for (int offset = 0; offset < segments.size(); offset += EMBEDDING_BATCH_SIZE) {
                    List<TextSegment> batch = segments.subList(offset, Math.min(offset + EMBEDDING_BATCH_SIZE, segments.size()));
                    store.addAll(embeddingModel.embedAll(batch).content(), batch);
                }
                segmentCount += segments.size();
            }
        } catch (IOException | UncheckedIOException e) {
            throw new IllegalStateException("Could not ingest documentation from " + directory, e);
        }
        if (segmentCount == 0) {
            throw new ConfigException("No non-empty .adoc or .md documentation found in " + directory);
        }
        System.getLogger(DocsIngestor.class.getName())
                .log(System.Logger.Level.INFO, "Ingested {0} documentation segments for {1}", segmentCount, flavor);
    }

    private String readDocument(Path path) {
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(maxFileBytes + 1);
            if (bytes.length > maxFileBytes) {
                throw new ConfigException("Documentation exceeds assistant.max-file-bytes: " + path);
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read documentation " + path, e);
        }
    }
}
