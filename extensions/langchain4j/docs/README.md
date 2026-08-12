# Overview

Langchain4j integration extension.

## Migrating to version 27

> [!WARNING]
> Helidon Extensions 27 removes the legacy Cohere scoring-model `proxy` configuration option and the generated
> `proxy(Proxy)` builder methods. LangChain4j no longer supports configuring Cohere with `java.net.Proxy`.
>
> Proxying remains available through a LangChain4j `HttpClientBuilder`. For example, wrap a proxy-configured
> `java.net.http.HttpClient.Builder` in LangChain4j's `JdkHttpClientBuilder`, then supply it programmatically with
> `CohereScoringModelConfig.Builder.httpClientBuilder(...)`. Alternatively, register a proxy-aware implementation as
> a named service and select it with `http-client-builder.service-registry.named`.
