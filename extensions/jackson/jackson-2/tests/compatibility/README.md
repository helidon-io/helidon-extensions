# Jackson 2 generated application compatibility

`src/test/resources/legacy` contains the original core Jackson 2 builder fixture
from commit `ffa7ebba4a39edbb757a10b8e41962f8a606cce5`. The generated source was produced
by the original `helidon-builder-codegen-jackson` processor during a clean core
build, and the JAR was compiled in that same build using the original core APIs.
`provenance.properties` records the source, binary, compile dependencies, and
processor hashes as provenance of the original build. These hashes are historical
metadata, not assertions about checked-out text files: ordinary Git line-ending
conversion is supported without special Git attributes.
The core fixture module completed compilation and installation;
an unrelated later module failed in that full reactor build.

The old generated application imports only neutral builder APIs and Jackson 2
annotations. Its original module descriptor has no dependency on the deprecated
Jackson integration runtime and provides no services. The JAR and descriptor are
retained intact; no replacement facade or invented service metadata is needed.

The regression recompiles the legacy sources with
processing disabled against the replacement dependency graph, and runs both the
unchanged original binary and the recompiled application through the extension's
`JacksonSupport`. Each runs alone and alongside the independently generated new
fixture on the class path and the module path. The new fixture is generated in
the sibling `builder-codegen` module using only the extension's Jackson processor.
Forked JVMs receive the curated replacement runtime graph and explicitly reject
the deprecated core runtime. Jackson 3 is outside this fixture's contract.

The original module descriptor exports the model package without opening it.
Jackson's access to the generated private builder constructor therefore needs
`--add-opens io.helidon.builder.tests.jackson/io.helidon.builder.tests.jackson=com.fasterxml.jackson.databind`.
The harness supplies that ordinary Jackson reflection access at launch while
preserving the original descriptor and binary. New applications should declare
`opens <model-package> to com.fasterxml.jackson.databind` in their own descriptor.

To refresh this fixture, build the named original core commit with its original
processor configuration, copy the original generated source and packaged fixture
JAR, and record the original build's hashes and provenance together. Never
run the old and new Jackson processors against the same blueprint.
