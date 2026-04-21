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
