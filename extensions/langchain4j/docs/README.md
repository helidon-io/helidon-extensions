<!--@frontmatter
site:
  title: "Helidon LangChain4j Extension"
  version: "27.0.0"
pages:
  - config/manifest.md
-->
# LangChain4j

- [LangChain4j](langchain4j.md) <!--@icon i-simple-icons-langchain -->
- [RAG](rag.md) <!--@icon i-lucide-search-check -->
- [Provider Generator](provider-generator.md) <!--@icon i-lucide-factory -->

## Providers

- [OpenAI](open-ai.md) <!--@icon i-simple-icons-openai -->
- [Google Gemini](gemini.md) <!--@icon i-simple-icons-googlegemini -->
- [Oracle OCI GenAI](oci-genai.md) <!--@icon i-simple-icons-oracle -->
- [Ollama](ollama.md) <!--@icon i-simple-icons-ollama -->
- [Jlama](jlama.md) <!--@icon i-lucide-cpu -->
- [Cohere](cohere.md) <!--@icon i-lucide-messages-square -->
- [Oracle Embedding Store](oracle.md) <!--@icon i-simple-icons-oracle -->
- [Coherence Embedding Store](coherence.md) <!--@icon i-lucide-database-zap -->
- [Built-in Providers](lc4j-providers.md) <!--@icon i-lucide-package-check -->
- [In-Process Embedding Models](lc4j-in-process.md) <!--@icon i-lucide-memory-stick -->
- [Mock ChatModel](mock.md) <!--@icon i-lucide-flask-conical -->

## Reference

- [Config Reference](config/config_reference.md) <!--@icon i-lucide-cogs -->
<!-- TODO Add a Javadocs link when the API documentation is available. -->

## Migrating to version 27

> [!WARNING]
> Helidon Extensions 27 removes the legacy Cohere scoring-model `proxy` configuration option and the generated
> `proxy(Proxy)` builder methods. LangChain4j no longer supports configuring Cohere with `java.net.Proxy`.
>
> Proxying remains available through a LangChain4j `HttpClientBuilder`. For example, wrap a proxy-configured
> `java.net.http.HttpClient.Builder` in LangChain4j's `JdkHttpClientBuilder`, then supply it programmatically with
> `CohereScoringModelConfig.Builder.httpClientBuilder(...)`. Alternatively, register a proxy-aware implementation as
> a named service and select it with `http-client-builder.service-registry.named`.

> [!WARNING]
> OpenAI image-model configuration also changes in Helidon Extensions 27. Remove `style`, which has no direct
> replacement, and remove image-model `response-format`. The chat-model `response-format` option remains available.
> Image models now support `background`, `output-format`, `output-compression`, and `moderation`; configure these
> independently as needed. Removed image-model options may otherwise fail configuration validation or silently stop
> applying.
