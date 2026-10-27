# Jakarta integration tests

The release unit's `tests` profile includes the independent consumer suite.
It clones the extension-owned examples to `consumers/target/consumers` and runs their
standalone Maven builds using installed artifacts and the documented BOM.

Each example tests observable behavior on the classpath, records its runtime dependency tree,
and launches a separate Java process on the module path. The post-build check rejects the
deprecated core integration runtimes and unrelated Jakarta families or sibling modules.
This checks that selecting one module does not depend on the extension reactor or another
integration implementation.

Run from the Jakarta release root after installing its production modules:

```shell
mvn_27 clean install -Ptests
```
