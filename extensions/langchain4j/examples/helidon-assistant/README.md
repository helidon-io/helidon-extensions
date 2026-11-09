# Helidon Assistant

A browser-based documentation assistant built with Helidon 27's declarative
HTTP endpoints, Service Registry, and LangChain4j agents. It is a new version
of the [Helidon-docs assistant](https://github.com/danielkec/helidon-assistant/tree/bf729c5).

Each request runs a declarative sequence:

1. Classify the question as Helidon SE or MicroProfile.
2. Route it to the corresponding expert, using a separate documentation retriever.
3. Summarize the exchange and return the answer and updated conversation summary.

Models, embedding stores, and retrievers are named components in
[application.yaml](src/main/resources/application.yaml). The chat model uses the
OpenAI provider, which also supports compatible endpoints. Embeddings are
computed locally with the quantized AllMiniLmL6V2 model; no embedding API key is needed.

## Build

Requires JDK 26 and Maven 3.8 or newer. The example uses Helidon and Extensions
`27.0.0-SNAPSHOT` artifacts. From the Extensions repository root:

```shell
mvn -Pexamples -pl extensions/langchain4j/examples/helidon-assistant -am clean install
cd extensions/langchain4j/examples/helidon-assistant
```

The example is not deployed as a library. After installing its reactor dependencies,
it can also be built directly with `mvn clean package`.

## Documentation dataset

Provide a directory containing `se/` and `mp/` documentation trees.
For example, from the example directory:

```shell
git clone --depth 1 --filter=blob:none --sparse --branch helidon-4.x \
    https://github.com/helidon-io/helidon.git helidon-docs
git -C helidon-docs sparse-checkout set docs
```

This provides the default path, `./helidon-docs/docs`. To use another checkout:

```shell
export HELIDON_DOCS_PATH=/absolute/path/to/docs
```

The application runs on Helidon 27, while the SE/MP dataset in the command above
describes Helidon 4. Answers follow the documentation version you supply.
Older AsciiDoc datasets work too: point `HELIDON_DOCS_PATH` at their
`docs/src/main/asciidoc` directory.

At startup, `DocsIngestor` reads UTF-8 Markdown and AsciiDoc files, splits them
into overlapping chunks, and populates separate in-memory SE/MP stores. Source
paths are retained as chunk metadata. Ingestion must finish before the chat
endpoint is available; missing or empty documentation trees fail startup.

The example treats documentation as text: it does not render markup, expand
AsciiDoc includes, or execute document directives. It skips symbolic links and
non-document files. The index is rebuilt on each start, so indexing a full
checkout can take time and memory. Tune the following configuration if needed:

```yaml
assistant:
  docs-path: /absolute/path/to/docs
  chunk-size: 1000
  chunk-overlap: 100
  max-file-bytes: 1048576
```

## Run

Set the chat-model credentials, then start the packaged application:

```shell
export OPENAI_API_KEY=your-api-key
java -jar target/helidon-extensions-langchain4j-examples-helidon-assistant.jar
```

Open [http://localhost:8080](http://localhost:8080). Optional environment overrides:

- `OPENAI_MODEL`: chat model name; defaults to `gpt-4o-mini`.
- `OPENAI_BASE_URL`: OpenAI-compatible endpoint; defaults to `https://api.openai.com/v1`.
- `HELIDON_DOCS_PATH`: documentation directory.

For an OCI GenAI OpenAI-compatible endpoint, set `OPENAI_BASE_URL` to your
endpoint, `OPENAI_MODEL` to an enabled model, and `OPENAI_API_KEY` to its API
token. Other configured providers can be selected by changing the named
`assistant-model` component in `application.yaml`.

The example binds to loopback by default and has no authentication. Questions,
conversation summaries, and retrieved documentation excerpts are sent to the
configured chat model, which can incur provider charges. Index only documentation
you intend that provider to receive.

## HTTP API

`POST /chat` accepts and returns JSON:

```shell
curl --fail-with-body http://localhost:8080/chat \
    -H 'Content-Type: application/json' \
    -d '{"message":"How do I create a Helidon SE HTTP endpoint?","summary":""}'
```

```json
{"message":"The answer...","summary":"An updated summary..."}
```

Send the returned `summary` with the next message. The first request may omit
it. The service does not keep shared chat memory; the browser keeps only the
current summary, and **New conversation** clears it. Messages must be non-blank
strings of at most 8,000 characters; summaries must be strings of at most 16,000
characters. Invalid requests return HTTP 400 before model invocation.
The HTTP listener limits request bodies to 256 KiB; larger bodies return HTTP 413.

The browser renders answers as text, including code blocks, without executing
model-produced HTML. Verify generated answers and code against the supplied docs.

## Tests

From the repository root:

```shell
mvn -Pexamples -pl extensions/langchain4j/examples/helidon-assistant -am \
    -Dtest=AssistantTest,DocsIngestorTest,ConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Tests require no API keys or external model servers. HTTP tests run the actual
declarative agent sequence, named content retrievers, stores, and ingestion,
with deterministic chat and embedding models. They check both routing branches,
retrieved source text, summary propagation, conversation isolation, input
validation (including malformed and oversized JSON), and static content. Ingestion tests cover both file formats,
chunking, embedding batches, missing/empty datasets, file-size limits, and
symbolic links. Configuration tests load the production YAML to check defaults
and overrides.
