# Jakarta integration tests

The release unit's `tests` profile includes the independent consumer suite.
It clones the extension-owned examples to `consumers/target/consumers` and runs their
standalone Maven builds using installed artifacts and the documented BOM.

Each example tests observable behavior on the classpath, records its runtime dependency tree,
and launches a separate Java process on the module path. The post-build check rejects the
deprecated core integration runtimes and unrelated Jakarta families or sibling modules.
This checks that selecting one module does not depend on the extension reactor or another
integration implementation.

Use JDK 27 with Maven: set `JAVA_HOME` to that JDK and put its `bin` directory on `PATH`.
Follow the [development build prerequisites](../docs/README.md#development-build), then run
from the Jakarta release root:

```shell
mvn clean install -Ptests
```
