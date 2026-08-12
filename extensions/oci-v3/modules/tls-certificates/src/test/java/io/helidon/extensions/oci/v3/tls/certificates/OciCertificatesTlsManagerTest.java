/*
 * Copyright (c) 2023, 2026 Oracle and/or its affiliates.
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

package io.helidon.extensions.oci.v3.tls.certificates;

import java.lang.reflect.Method;
import java.net.URI;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import io.helidon.common.testing.junit5.InMemoryLoggingHandler;
import io.helidon.common.tls.Tls;
import io.helidon.config.Config;
import io.helidon.config.ConfigSources;

import javax.net.ssl.X509KeyManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// see pom.xml for system properties that can be used in these tests
class OciCertificatesTlsManagerTest {
    @AfterEach
    void reset() {
        TestOciCertificatesDownloader.version = "1";
        TestOciCertificatesDownloader.caCertificateResource = "test-keys/ca.pem";
        TestOciCertificatesDownloader.callCount_loadCertificates = 0;
        TestOciCertificatesDownloader.callCount_loadCertificatesWithPrivateKey = 0;
        TestOciCertificatesDownloader.callCount_loadCACertificate = 0;
        TestOciCertificatesDownloader.managedDelayMillis = 0;
        TestOciCertificatesDownloader.managedFailure = null;
        TestOciCertificatesDownloader.caFailure = null;
        TestOciPrivateKeyDownloader.callCount = 0;
    }

    @Test
    void managerCreation() {
        Config tlsManagerConfig = Config.create()
                .get("server.sockets.0.tls.manager.oci-certificates-tls-manager");
        OciCertificatesTlsManagerConfig cfg = OciCertificatesTlsManagerConfig
                .create(tlsManagerConfig);
        OciCertificatesTlsManager tlsManager = OciCertificatesTlsManager.create(cfg);
        assertThat(tlsManager,
                   notNullValue());
    }

    @Test
    void configIsMonitoredForChange() throws Exception {
        TestingConfigSource testingConfigSource =
                new TestingConfigSource(
                        "server.sockets.0.tls.manager.oci-certificates-tls-manager.key-password");
        Config config = Config.just(testingConfigSource,
                                    ConfigSources.systemProperties(),
                                    ConfigSources.classpath("application.yaml"));
        assertThat(config.exists(),
                   is(true));
        Config tlsConfig = config.get("server.sockets.0.tls");
        assertThat(tlsConfig.exists(),
                   is(true));

        int certDownloadCountBaseline0 = TestOciCertificatesDownloader.callCount_loadCertificates;
        int caCertDownloadCountBaseline0 = TestOciCertificatesDownloader.callCount_loadCACertificate;
        int pkDownloadCountBaseLine0 = TestOciPrivateKeyDownloader.callCount;
        assertThat("sanity",
                   certDownloadCountBaseline0,
                   equalTo(0));
        assertThat("sanity",
                   caCertDownloadCountBaseline0,
                   equalTo(0));
        assertThat("sanity",
                   pkDownloadCountBaseLine0,
                   equalTo(0));

        Tls tls = Tls.create(tlsConfig);
        assertThat(tls.prototype().manager(),
                   instanceOf(DefaultOciCertificatesTlsManager.class));

        int certDownloadCountBaseline = TestOciCertificatesDownloader.callCount_loadCertificates;
        int caCertDownloadCountBaseline = TestOciCertificatesDownloader.callCount_loadCACertificate;
        int pkDownloadCountBaseLine = TestOciPrivateKeyDownloader.callCount;
        assertThat(certDownloadCountBaseline,
                   equalTo(1));
        assertThat(caCertDownloadCountBaseline,
                   equalTo(1));
        assertThat(pkDownloadCountBaseLine,
                   equalTo(1));

        Config pwdConfig = tlsConfig.get("manager.oci-certificates-tls-manager.key-password");
        assertThat(pwdConfig.exists(),
                   is(true));

        // mutate it
        testingConfigSource.update("changed");
        assertThat(config.context().last()
                           .get("server.sockets.0.tls.manager.oci-certificates-tls-manager.key-password").asString().asOptional(),
                   is(Optional.of("changed")));

        // Config change delivery is asynchronous. Wait for the legacy Vault refresh to finish so it cannot
        // leak downloader calls into a subsequent test after the static counters have been reset.
        long deadline = System.nanoTime() + 5_000_000_000L;
        while ((TestOciCertificatesDownloader.callCount_loadCACertificate < caCertDownloadCountBaseline + 1
                || TestOciPrivateKeyDownloader.callCount < pkDownloadCountBaseLine + 1)
                && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(TestOciCertificatesDownloader.callCount_loadCertificates, equalTo(certDownloadCountBaseline + 1));
        assertThat(TestOciCertificatesDownloader.callCount_loadCACertificate, equalTo(caCertDownloadCountBaseline + 1));
        assertThat(TestOciPrivateKeyDownloader.callCount, equalTo(pkDownloadCountBaseLine + 1));
    }

    @Test
    void legacyVaultReloadsWhenVersionIsUnchanged() {
        DefaultOciCertificatesTlsManager manager = newManager(OciPrivateKeySource.VAULT);

        assertThat(TestOciCertificatesDownloader.callCount_loadCertificates, equalTo(1));
        assertThat(TestOciCertificatesDownloader.callCount_loadCACertificate, equalTo(1));
        assertThat(TestOciPrivateKeyDownloader.callCount, equalTo(1));

        assertTrue(manager.loadContext(false));
        assertThat(TestOciCertificatesDownloader.callCount_loadCertificates, equalTo(2));
        assertThat(TestOciCertificatesDownloader.callCount_loadCACertificate, equalTo(2));
        assertThat(TestOciPrivateKeyDownloader.callCount, equalTo(2));
    }

    @Test
    void vaultCanDisableReloadWhenVersionIsUnchanged() {
        DefaultOciCertificatesTlsManager manager = newManager(OciPrivateKeySource.VAULT, false);

        assertFalse(manager.loadContext(false));
        assertThat(TestOciCertificatesDownloader.callCount_loadCertificates, equalTo(2));
        assertThat(TestOciCertificatesDownloader.callCount_loadCACertificate, equalTo(2));
        assertThat(TestOciPrivateKeyDownloader.callCount, equalTo(1));
    }

    @Test
    void managedBundleLoadsAndRotatesWithoutVaultKeyDownloader() {
        DefaultOciCertificatesTlsManager manager = newManager(OciPrivateKeySource.CERTIFICATE_BUNDLE);

        assertThat(TestOciCertificatesDownloader.callCount_loadCertificates, equalTo(0));
        assertThat(TestOciCertificatesDownloader.callCount_loadCertificatesWithPrivateKey, equalTo(1));
        assertThat(TestOciCertificatesDownloader.callCount_loadCACertificate, equalTo(1));
        assertThat(TestOciPrivateKeyDownloader.callCount, equalTo(0));
        assertThat(privateKeyAlgorithm(manager), is("RSA"));

        assertFalse(manager.loadContext(false));
        assertThat(TestOciCertificatesDownloader.callCount_loadCACertificate, equalTo(2));

        TestOciCertificatesDownloader.version = "2";
        assertTrue(manager.loadContext(false));
        assertThat(TestOciCertificatesDownloader.callCount_loadCACertificate, equalTo(3));
        assertThat(TestOciPrivateKeyDownloader.callCount, equalTo(0));
        assertThat(privateKeyAlgorithm(manager), is("EC"));

        assertFalse(manager.loadContext(false));
        assertThat(TestOciCertificatesDownloader.callCount_loadCACertificate, equalTo(4));
    }

    @Test
    void managedBundleCanReloadWhenVersionIsUnchanged() {
        DefaultOciCertificatesTlsManager manager = newManager(OciPrivateKeySource.CERTIFICATE_BUNDLE, true);

        assertTrue(manager.loadContext(false));
        assertThat(TestOciCertificatesDownloader.callCount_loadCertificatesWithPrivateKey, equalTo(2));
        assertThat(TestOciCertificatesDownloader.callCount_loadCACertificate, equalTo(2));
        assertThat(TestOciPrivateKeyDownloader.callCount, equalTo(0));
        assertThat(privateKeyAlgorithm(manager), is("RSA"));
    }

    @Test
    void failedManagedRotationKeepsVersionRetryable() {
        DefaultOciCertificatesTlsManager manager = newManager(OciPrivateKeySource.CERTIFICATE_BUNDLE);
        TestOciCertificatesDownloader.version = "2";
        TestOciCertificatesDownloader.managedFailure = new IllegalStateException("synthetic managed download failure");

        assertThrows(IllegalStateException.class, () -> manager.loadContext(false));
        assertThat(TestOciCertificatesDownloader.callCount_loadCACertificate, equalTo(1));
        assertThat(privateKeyAlgorithm(manager), is("RSA"));

        TestOciCertificatesDownloader.managedFailure = null;
        assertTrue(manager.loadContext(false));
        assertThat(privateKeyAlgorithm(manager), is("EC"));
        assertFalse(manager.loadContext(false));
        assertThat(TestOciCertificatesDownloader.callCount_loadCACertificate, equalTo(3));
        assertThat(TestOciPrivateKeyDownloader.callCount, equalTo(0));
    }

    @Test
    void failureAfterManagedIdentityAcquisitionKeepsIdentityAndVersionRetryable() {
        DefaultOciCertificatesTlsManager manager = newManager(OciPrivateKeySource.CERTIFICATE_BUNDLE);
        TestOciCertificatesDownloader.version = "2";
        TestOciCertificatesDownloader.caFailure = new IllegalStateException("synthetic CA download failure");

        assertThrows(IllegalStateException.class, () -> manager.loadContext(false));
        assertThat(TestOciCertificatesDownloader.callCount_loadCertificatesWithPrivateKey, equalTo(2));
        assertThat(TestOciCertificatesDownloader.callCount_loadCACertificate, equalTo(2));
        assertThat(privateKeyAlgorithm(manager), is("RSA"));

        TestOciCertificatesDownloader.caFailure = null;
        assertTrue(manager.loadContext(false));
        assertThat(privateKeyAlgorithm(manager), is("EC"));
        assertFalse(manager.loadContext(false));
        assertThat(TestOciCertificatesDownloader.callCount_loadCACertificate, equalTo(4));
        assertThat(TestOciPrivateKeyDownloader.callCount, equalTo(0));
    }

    @Test
    void caOnlyRotationFailuresAreLoggedAndRetried() throws Exception {
        DefaultOciCertificatesTlsManager manager = newManager(OciPrivateKeySource.CERTIFICATE_BUNDLE);
        X509Certificate initialCa = trustedCa(manager);
        TestOciCertificatesDownloader.caCertificateResource = "test-keys/ecCert.pem";
        RuntimeException[] failures = {
                new UnsupportedOperationException("secret unsupported detail"),
                new IllegalArgumentException("secret material"),
                new IllegalStateException("secret download detail"),
                new RuntimeException("secret runtime detail")
        };
        String[] failureCategories = {
                "unsupported-operation",
                "invalid-tls-material",
                "oci-download-or-tls-state",
                "runtime-failure"
        };

        try (InMemoryLoggingHandler handler = InMemoryLoggingHandler.create(manager)) {
            for (int i = 0; i < failures.length; i++) {
                TestOciCertificatesDownloader.caFailure = failures[i];
                invokeScheduledReload(manager);

                assertThat(handler.logRecords().size(), is(i + 1));
                LogRecord record = handler.logRecords().get(i);
                assertThat(record.getLevel(), is(Level.WARNING));
                assertThat(record.getLoggerName(), is(DefaultOciCertificatesTlsManager.class.getName()));
                assertThat(record.getThrown(), nullValue());
                String warning = record.getMessage();
                assertThat(warning, containsString("Failed to refresh OCI certificate test-cert"));
                assertThat(warning, containsString("failure category: " + failureCategories[i]));
                assertThat(warning, containsString("previously installed TLS identity remains active"));
                assertThat(warning, not(containsString(failures[i].getMessage())));
            }
        }
        assertThat(trustedCa(manager), equalTo(initialCa));

        TestOciCertificatesDownloader.caFailure = null;
        assertTrue(manager.loadContext(false));
        X509Certificate rotatedCa = trustedCa(manager);
        assertThat(rotatedCa, not(equalTo(initialCa)));
        assertThat(rotatedCa.getSubjectX500Principal().getName(), containsString("CN=managed-ec"));
        assertThat(privateKeyAlgorithm(manager), is("RSA"));

        assertFalse(manager.loadContext(false));
        assertThat(trustedCa(manager), equalTo(rotatedCa));
        assertThat(TestOciCertificatesDownloader.callCount_loadCertificatesWithPrivateKey, equalTo(7));
        assertThat(TestOciCertificatesDownloader.callCount_loadCACertificate, equalTo(7));
        assertThat(TestOciPrivateKeyDownloader.callCount, equalTo(0));
    }

    @Test
    void configCallbackPropagatesRefreshFailure() {
        DefaultOciCertificatesTlsManager manager = newManager(OciPrivateKeySource.CERTIFICATE_BUNDLE);
        TestOciCertificatesDownloader.version = "2";
        TestOciCertificatesDownloader.managedFailure = new IllegalStateException("synthetic managed download failure");

        assertThrows(IllegalStateException.class, () -> manager.config(Config.empty()));
        assertThat(privateKeyAlgorithm(manager), is("RSA"));
    }

    @Test
    void concurrentManagedRotationReloadsVersionOnce() throws Exception {
        DefaultOciCertificatesTlsManager manager = newManager(OciPrivateKeySource.CERTIFICATE_BUNDLE);
        TestOciCertificatesDownloader.version = "2";
        TestOciCertificatesDownloader.managedDelayMillis = 25;

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> first = executor.submit(() -> {
                start.await();
                return manager.loadContext(false);
            });
            Future<Boolean> second = executor.submit(() -> {
                start.await();
                return manager.loadContext(false);
            });
            start.countDown();

            boolean firstResult = first.get(5, TimeUnit.SECONDS);
            boolean secondResult = second.get(5, TimeUnit.SECONDS);
            assertTrue(firstResult || secondResult, "One concurrent refresh should install the new identity");
            assertFalse(firstResult && secondResult, "Only one concurrent refresh should install the new identity");
            assertThat(TestOciCertificatesDownloader.callCount_loadCACertificate, equalTo(3));
            assertThat(TestOciPrivateKeyDownloader.callCount, equalTo(0));
            assertThat(privateKeyAlgorithm(manager), is("EC"));
        } finally {
            executor.shutdownNow();
        }
    }

    private static DefaultOciCertificatesTlsManager newManager(OciPrivateKeySource privateKeySource) {
        return newManager(privateKeySource, null);
    }

    private static DefaultOciCertificatesTlsManager newManager(OciPrivateKeySource privateKeySource,
                                                               Boolean alwaysReload) {
        OciCertificatesTlsManagerConfig.Builder builder = OciCertificatesTlsManagerConfig.builder()
                .schedule("0 * * * * ? 2099")
                .privateKeySource(privateKeySource)
                .caOcid("test-ca")
                .certOcid("test-cert");
        if (alwaysReload != null) {
            builder.alwaysReload(alwaysReload);
        }
        if (privateKeySource == OciPrivateKeySource.VAULT) {
            builder.vaultCryptoEndpoint(URI.create("https://vault.example.test"))
                    .keyOcid("test-key")
                    .keyPassword("test-password");
        }

        DefaultOciCertificatesTlsManager manager =
                new DefaultOciCertificatesTlsManager(builder.buildPrototype());
        manager.init(Tls.builder().buildPrototype());
        return manager;
    }

    private static String privateKeyAlgorithm(DefaultOciCertificatesTlsManager manager) {
        X509KeyManager keyManager = manager.keyManager().orElseThrow();
        for (String algorithm : new String[] {"RSA", "EC"}) {
            String alias = keyManager.chooseServerAlias(algorithm, null, null);
            if (alias != null) {
                PrivateKey privateKey = keyManager.getPrivateKey(alias);
                if (privateKey != null) {
                    return privateKey.getAlgorithm();
                }
            }
        }
        throw new AssertionError("No RSA or EC server key alias was available");
    }

    private static X509Certificate trustedCa(DefaultOciCertificatesTlsManager manager) {
        X509Certificate[] acceptedIssuers = manager.trustManager().orElseThrow().getAcceptedIssuers();
        assertThat(acceptedIssuers.length, is(1));
        return acceptedIssuers[0];
    }

    private static void invokeScheduledReload(DefaultOciCertificatesTlsManager manager) throws Exception {
        Method method = DefaultOciCertificatesTlsManager.class.getDeclaredMethod("maybeReload");
        method.setAccessible(true);
        method.invoke(manager);
    }
}
