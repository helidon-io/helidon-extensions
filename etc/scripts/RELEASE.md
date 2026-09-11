# Releasing Helidon Extensions

These are the steps for doing a release of a Helidon Extension. These steps
will use release 27.0.1 in examples. Of course, you are not releasing
27.0.1, so make sure to change that release number to your release
number when copy/pasting.

## Overview

The release workflow is triggered when a change is pushed to
a branch with the following pattern `<extension>/release-*`. The release workflow performs
a Maven release to the [Central Publishing Portal](https://central.sonatype.org/publish/publish-portal-guide/).

1. Create a release branch
2. Push release branch to upstream, release workflow runs
3. Verify bits in Central Deployment repository and then publish them
4. Increment version in the codeline branch

## Directory convention

An extension is a directory containing `pom.xml` and `bom/pom.xml`, either
directly under `extensions/` or one grouping directory below it. Each extension
has its own version and declares `helidon.version` in its root POM. A grouping
POM only aggregates extensions; it is not their Maven parent or a release unit.

For example, Messaging connectors live in `extensions/messaging/kafka`,
`extensions/messaging/jms`, and `extensions/messaging/pulsar`. Each uses the
normal `bom`, `modules`, `tests`, and optional `examples` layout. The extension
ID is its path relative to `extensions/`; no registration file is needed.

Release Kafka independently with:

```shell
git checkout -b messaging/kafka/release-27.0.1
./etc/scripts/release.sh update_version --extension=messaging/kafka --version=27.0.1-SNAPSHOT
git commit -a -m "Update Messaging Kafka version to 27.0.1-SNAPSHOT"
git push origin messaging/kafka/release-27.0.1
```

This produces tag `messaging/kafka/27.0.1` and releases Kafka's project, BOM,
and connector, plus shared repository parents when required. JMS and Pulsar
keep their own versions. The BOM version property follows the directory ID
with dots: `messaging.kafka.extension.version`. Maven project coordinates
follow the same convention: group `io.helidon.extensions.messaging.kafka`,
artifact `helidon-extensions-messaging-kafka-project`.

Validation selects one connector for a connector branch or a change inside
that connector directory, and all connectors for changes to their grouping
POM. Flat extensions such as `eureka` use the same commands as before.

## Steps in detail

1. Create local release branch
    ```shell
    git checkout -b eureka/release-27.0.1
    ./etc/scripts/release.sh update_version --extension=eureka --version=27.0.1-SNAPSHOT
    git commit -a -m "Update Helidon Extensions Eureka version to 27.0.0.1-SNAPSHOT"
    git push origin eureka/release-27.0.1
    ```

2. Wait for release build to complete:

   https://github.com/helidon-io/helidon/actions/workflows/release.yaml

3. Check Central Portal for deployment
    1. In browser go to: https://central.sonatype.com/publishing and login.
    2. Click on Deployments tab, you should see the Deployment listed (io-helidon-extensions)
    3. Status should be "Validated". You can explore the Deployment Info to see staged artifacts

4. Make a PR to update the version in the codeline branch to the next version
    ```shell
    git checkout -b eureka/update_version_27.0.2-SNAPSHOT
    ./etc/scripts/release.sh update_version --extension=eureka --version=27.0.2-SNAPSHOT
    git commit -a -m "Update Helidon Extensions Eureka version to 27.0.0.2-SNAPSHOT"
    git push myfork eureka/update_version_27.0.2-SNAPSHOT
    ```

# Staging Repository Profile

To pull artifacts from the Central Portal staging repository add this to your `settings.xml`:

The BEARER_TOKEN must be that for the user that uploaded the release.
For general information concerning BEARER_TOKEN see
* https://central.sonatype.org/publish/generate-portal-token/
* https://central.sonatype.org/publish/publish-portal-api/#authentication-authorization
* https://central.sonatype.org/publish/publish-portal-api/#manually-testing-a-deployment-bundle

```xml
  <servers>
   <server>
      <id>central.manual.testing</id>
      <configuration>
         <httpHeaders>
            <property>
               <name>Authorization</name>
               <value>Bearer ${BEARER_TOKEN}</value>
            </property>
         </httpHeaders>
      </configuration>
   </server>
</servers>

<profiles>
    <profile>
       <id>central.manual.testing</id>
       <repositories>
          <repository>
             <id>central.manual.testing</id>
             <name>Central Testing repository</name>
             <url>https://central.sonatype.com/api/v1/publisher/deployments/download</url>
          </repository>
       </repositories>
       <pluginRepositories>
          <pluginRepository>
             <id>central.manual.testing</id>
             <name>Central Testing repository</name>
             <url>https://central.sonatype.com/api/v1/publisher/deployments/download</url>
          </pluginRepository>
       </pluginRepositories>
    </profile>
</profiles>
```
