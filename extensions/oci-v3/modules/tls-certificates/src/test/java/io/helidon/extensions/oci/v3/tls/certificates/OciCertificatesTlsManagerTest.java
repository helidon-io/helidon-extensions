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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSessionContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;

import io.helidon.common.configurable.Resource;
import io.helidon.common.pki.Keys;
import io.helidon.common.pki.PemReader;
import io.helidon.common.tls.Tls;
import io.helidon.common.tls.TlsMaterial;
import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.extensions.oci.v3.tls.certificates.TestOciCertificatesDownloader.State;
import io.helidon.extensions.oci.v3.tls.certificates.spi.OciCertificatesDownloader;
import io.helidon.extensions.oci.v3.tls.certificates.spi.OciPrivateKeyDownloader;
import io.helidon.scheduling.Task;
import io.helidon.scheduling.TaskManager;
import io.helidon.service.registry.GlobalServiceRegistry;
import io.helidon.service.registry.ServiceRegistry;
import io.helidon.service.registry.ServiceRegistryManager;

import com.oracle.bmc.model.BmcException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Isolated("Replaces the global service registry")
@Execution(ExecutionMode.SAME_THREAD)
class OciCertificatesTlsManagerTest {
    private static final String INACTIVE_SCHEDULE = "0 * * * * ? 2099";
    private static final String SECONDLY_SCHEDULE = "* * * * * ? *";

    private ServiceRegistry originalRegistry;
    private ServiceRegistryManager registryManager;
    private TaskManager taskManager;
    private TestOciCertificatesDownloader downloader;
    private TestOciPrivateKeyDownloader keyDownloader;
    private String certificateOcid;

    @BeforeEach
    void createRegistry() {
        originalRegistry = GlobalServiceRegistry.registry();
        registryManager = ServiceRegistryManager.create();
        GlobalServiceRegistry.registry(registryManager.registry());
        taskManager = registryManager.registry().get(TaskManager.class);
        downloader = (TestOciCertificatesDownloader) registryManager.registry().get(OciCertificatesDownloader.class);
        keyDownloader = (TestOciPrivateKeyDownloader) registryManager.registry().get(OciPrivateKeyDownloader.class);
        certificateOcid = "test-cert-" + UUID.randomUUID();
    }

    @AfterEach
    void closeRegistry() {
        try {
            if (taskManager != null) {
                taskManager.shutdown();
            }
        } finally {
            try {
                if (registryManager != null) {
                    registryManager.shutdown();
                }
            } finally {
                GlobalServiceRegistry.registry(originalRegistry);
            }
        }
    }

    @Test
    void providerSharedManagerInitializesOnlyOnce() throws Exception {
        Config config = Config.just(ConfigSources.create(Map.of(
                "manager.oci-certificates-tls-manager.private-key-source", "certificate-bundle",
                "manager.oci-certificates-tls-manager.schedule", INACTIVE_SCHEDULE,
                "manager.oci-certificates-tls-manager.ca-ocid", "test-ca",
                "manager.oci-certificates-tls-manager.cert-ocid", certificateOcid)));

        Tls first = Tls.create(config);
        Tls second = Tls.create(config);

        assertThat(first.prototype().manager(), sameInstance(second.prototype().manager()));
        assertThat(first.sslContext(), sameInstance(second.sslContext()));
        assertThat(taskManager.tasks().size(), is(1));
        assertThat(downloader.publicCalls(), is(1));
        assertThat(downloader.privateCalls(), is(1));
        assertThat(downloader.caCalls(), is(1));

        Future<Boolean> daemon = taskManager.tasks().iterator().next().executor()
                .submit(() -> Thread.currentThread().isDaemon());
        assertThat("managed scheduler must not prevent JVM termination",
                   daemon.get(5, TimeUnit.SECONDS),
                   is(true));
    }

    @Test
    void sharedManagerRejectsIncompatibleTlsContextConfiguration() {
        OciCertificatesTlsManager manager = newManager(false, INACTIVE_SCHEDULE);
        Tls.create(builder -> builder.manager(manager).protocol("TLS"));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> Tls.create(builder -> builder.manager(manager).protocol("TLSv1.2")));

        assertThat(exception.getMessage(), containsString("matching TLS context configuration"));
    }

    @Test
    @SuppressWarnings("removal")
    void externalTlsReloadIsRejected() {
        OciCertificatesTlsManager manager = newManager(false, INACTIVE_SCHEDULE);
        Tls tls = Tls.create(builder -> builder.manager(manager));
        Tls replacement = Tls.builder().build();

        UnsupportedOperationException exception = assertThrows(UnsupportedOperationException.class,
                                                               () -> tls.reload(replacement));

        assertThat(exception.getMessage(), containsString("reloaded from OCI"));
        assertThrows(UnsupportedOperationException.class, () -> tls.reload(TlsMaterial.builder().build()));
    }

    @Test
    void reconstructingSharedManagerRestartsPollingAfterTaskManagerShutdown() throws Exception {
        Config config = Config.just(ConfigSources.create(Map.of(
                "manager.oci-certificates-tls-manager.private-key-source", "certificate-bundle",
                "manager.oci-certificates-tls-manager.schedule", SECONDLY_SCHEDULE,
                "manager.oci-certificates-tls-manager.ca-ocid", "test-ca",
                "manager.oci-certificates-tls-manager.cert-ocid", certificateOcid)));
        Tls first = Tls.create(config);
        assertThat(taskManager.tasks().size(), is(1));
        Task originalTask = taskManager.tasks().iterator().next();
        await(() -> downloader.publicCalls() >= 2, "managed polling to start");

        taskManager.shutdown();
        assertThat(taskManager.tasks().isEmpty(), is(true));

        Tls second = Tls.create(config);
        assertThat(first.prototype().manager(), sameInstance(second.prototype().manager()));
        assertThat(first.sslContext(), sameInstance(second.sslContext()));
        assertThat(taskManager.tasks().size(), is(1));
        assertThat(taskManager.tasks().iterator().next(), not(sameInstance(originalTask)));
        int callsAfterReconstruction = downloader.publicCalls();
        // One old callback may finish after cancellation; two later polls require the replacement timer.
        await(() -> downloader.publicCalls() >= callsAfterReconstruction + 2, "restarted managed polling to run");
    }

    @Test
    void refreshFailureLogsSafeOciDiagnosticsAndRetries() throws Exception {
        OciCertificatesTlsManager manager = newManager(false, SECONDLY_SCHEDULE);
        Tls.create(builder -> builder.manager(manager));
        Logger logger = Logger.getLogger(DefaultOciCertificatesTlsManager.class.getName());

        try (TestLogHandler handler = new TestLogHandler(logger, certificateOcid)) {
            downloader.state(new State("2", "2", "test-keys/ca.pem",
                                       new IllegalStateException("wrapper-secret",
                                                                 new BmcException(404,
                                                                                  "NotAuthorizedOrNotFound",
                                                                                  "sdk-secret",
                                                                                  "opc-test")),
                                       null));

            await(() -> handler.records().stream().anyMatch(record -> record.getMessage()
                          .contains("phase: private-key-certificate-bundle")),
                  "an actionable OCI refresh warning");
            LogRecord warning = handler.records().stream()
                    .filter(record -> record.getMessage().contains("phase: private-key-certificate-bundle"))
                    .findFirst()
                    .orElseThrow();

            assertThat(warning.getMessage(), containsString("failure category: oci-service-failure"));
            assertThat(warning.getMessage(), containsString("status code: 404"));
            assertThat(warning.getMessage(), containsString("service code: NotAuthorizedOrNotFound"));
            assertThat(warning.getMessage(), containsString("opc request id: opc-test"));
            assertThat(warning.getMessage(), containsString("client side: false"));
            assertThat(warning.getMessage(), containsString("timeout: false"));
            assertThat(warning.getMessage(), not(containsString("wrapper-secret")));
            assertThat(warning.getMessage(), not(containsString("sdk-secret")));
            assertThat(warning.getThrown(), nullValue());
            assertThat(privateKeyAlgorithm(manager), is("RSA"));

            downloader.state(new State("2", "2", "test-keys/ca.pem", null, null));
            await(() -> "EC".equals(privateKeyAlgorithm(manager)), "the failed refresh to be retried");
        }
    }

    @Test
    void rotationAtomicallyUpdatesExistingTlsAndReplacesSessionCaches() throws Exception {
        OciCertificatesTlsManager manager = newManager(false, SECONDLY_SCHEDULE);
        Tls first = Tls.create(builder -> builder.manager(manager));
        Tls second = Tls.create(builder -> builder.manager(manager));
        SSLContext stableContext = first.sslContext();
        SSLServerSocketFactory cachedServerSocketFactory = stableContext.getServerSocketFactory();
        SSLSessionContext initialServerSessions = stableContext.getServerSessionContext();
        SSLSessionContext initialClientSessions = stableContext.getClientSessionContext();

        assertThat(first.generation(), is(0L));
        assertThat(second.sslContext(), sameInstance(stableContext));
        assertThat(peerCertificate(cachedServerSocketFactory).getPublicKey().getAlgorithm(), is("RSA"));
        await(() -> downloader.publicCalls() >= 2,
              "the unchanged public bundle to be polled");
        assertThat(downloader.privateCalls(), is(1));
        assertThat(stableContext.getServerSessionContext(), sameInstance(initialServerSessions));

        downloader.state(new State("2", "2", "test-keys/ecCert.pem", null, null));
        await(() -> "EC".equals(privateKeyAlgorithm(manager))
                      && "EC".equals(trustedCa(manager).getPublicKey().getAlgorithm()),
              "the EC identity and CA to be installed");

        // Identity and CA are independently polled and may become visible across two complete updates.
        assertThat(first.generation(), greaterThan(0L));
        assertThat(first.sslContext(), sameInstance(stableContext));
        assertThat(second.sslContext(), sameInstance(stableContext));
        assertThat(stableContext.getServerSessionContext(), not(sameInstance(initialServerSessions)));
        assertThat(stableContext.getClientSessionContext(), not(sameInstance(initialClientSessions)));
        assertThat(peerCertificate(cachedServerSocketFactory).getPublicKey().getAlgorithm(), is("EC"));
        assertThat(trustedCa(manager).getPublicKey().getAlgorithm(), is("EC"));
        assertThat(downloader.privateCalls(), is(2));
    }

    @Test
    void versionRaceKeepsOldContextAndRetriesCandidate() throws Exception {
        OciCertificatesTlsManager manager = newManager(false, SECONDLY_SCHEDULE);
        Tls tls = Tls.create(builder -> builder.manager(manager));
        SSLSessionContext initialSessions = tls.sslContext().getServerSessionContext();
        Logger logger = Logger.getLogger(DefaultOciCertificatesTlsManager.class.getName());
        try (TestLogHandler handler = new TestLogHandler(logger, certificateOcid)) {
            downloader.state(new State("2", "3", "test-keys/ca.pem", null, null));
            await(() -> handler.records().stream().anyMatch(record -> record.getMessage()
                          .contains("phase: identity-version-validation")),
                  "the raced private bundle to be rejected");
            assertThat(privateKeyAlgorithm(manager), is("RSA"));
            assertThat(tls.generation(), is(0L));
            assertThat(tls.sslContext().getServerSessionContext(), sameInstance(initialSessions));

            downloader.state(new State("2", "2", "test-keys/ca.pem", null, null));
            await(() -> "EC".equals(privateKeyAlgorithm(manager)), "the stable candidate to be retried");
            assertThat(tls.sslContext().getServerSessionContext(), not(sameInstance(initialSessions)));
        }
    }

    @Test
    void alwaysReloadRebuildsContextWithoutRetransmittingUnchangedPrivateKey() throws Exception {
        OciCertificatesTlsManager manager = newManager(true, SECONDLY_SCHEDULE);
        Tls tls = Tls.create(builder -> builder.manager(manager));
        SSLSessionContext initialSessions = tls.sslContext().getServerSessionContext();

        await(() -> tls.sslContext().getServerSessionContext() != initialSessions,
              "an explicitly requested reload");

        assertThat(downloader.publicCalls() >= 2, is(true));
        assertThat(downloader.privateCalls(), is(1));
        assertThat(privateKeyAlgorithm(manager), is("RSA"));
    }

    @Test
    void caRotationRejectsPreviouslyResumableMutualTlsSession() throws Exception {
        downloader.state(new State("1", "1", "test-keys/ecCert.pem", null, null));
        OciCertificatesTlsManager manager = newManager(false, SECONDLY_SCHEDULE);
        Tls tls = Tls.create(builder -> builder.manager(manager));
        SSLSocketFactory clientFactory = mutualTlsClientFactory();
        // Keep JSSE's default endpoint identification so the initial TLS 1.2 session is resumable.
        try (SSLServerSocket listener = (SSLServerSocket) tls.sslContext().getServerSocketFactory().createServerSocket()) {
            listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            listener.setEnabledProtocols(new String[] {"TLSv1.2"});
            listener.setSoTimeout(5000);
            listener.setNeedClientAuth(true);
            byte[] first = mutualTlsHandshake(listener, clientFactory);
            byte[] resumed = mutualTlsHandshake(listener, clientFactory);
            assertThat("the pre-rotation TLS 1.2 session should be resumable", Arrays.equals(first, resumed), is(true));

            downloader.state(new State("1", "1", "test-keys/serverCert.pem", null, null));
            await(() -> "RSA".equals(trustedCa(manager).getPublicKey().getAlgorithm()),
                  "the replacement trust anchor to be installed");
            assertThrows(SSLHandshakeException.class, () -> mutualTlsHandshake(listener, clientFactory));
            assertThat(downloader.privateCalls(), is(1));
        }
    }

    @Test
    void vaultDefaultsToReloadingUnchangedIdentity() throws Exception {
        OciCertificatesTlsManager manager = newVaultManager(null);
        Tls tls = Tls.create(builder -> builder.manager(manager));
        await(() -> tls.generation() > 0, "the default Vault refresh");
        assertThat(keyDownloader.calls() >= 2, is(true));
        assertThat(downloader.privateCalls(), is(0));
        assertThat(privateKeyAlgorithm(manager), is("RSA"));
    }

    @Test
    void vaultCanSkipUnchangedIdentityAndRotateCaIndependently() throws Exception {
        OciCertificatesTlsManager manager = newVaultManager(false);
        Tls tls = Tls.create(builder -> builder.manager(manager));
        await(() -> downloader.caCalls() >= 2, "unchanged Vault polling");
        assertThat(tls.generation(), is(0L));
        assertThat(keyDownloader.calls(), is(1));
        downloader.state(new State("1", "1", "test-keys/ecCert.pem", null, null));
        await(() -> tls.generation() == 1, "CA-only Vault rotation");
        assertThat(trustedCa(manager).getPublicKey().getAlgorithm(), is("EC"));
        assertThat(keyDownloader.calls(), is(2));
        assertThat(downloader.privateCalls(), is(0));
    }

    @Test
    void caDownloadFailureRetainsGenerationAndRetries() throws Exception {
        OciCertificatesTlsManager manager = newManager(false, SECONDLY_SCHEDULE);
        Tls tls = Tls.create(builder -> builder.manager(manager));
        SSLSessionContext initialSessions = tls.sslContext().getServerSessionContext();
        Logger logger = Logger.getLogger(DefaultOciCertificatesTlsManager.class.getName());
        try (TestLogHandler handler = new TestLogHandler(logger, certificateOcid)) {
            downloader.state(new State("1", "1", "test-keys/ecCert.pem", null,
                                       new IllegalStateException("secret CA detail")));
            await(() -> !handler.records().isEmpty(), "failed CA poll");
            LogRecord warning = handler.records().getFirst();
            assertThat(warning.getMessage(), containsString("failure category: oci-download-or-tls-state"));
            assertThat(warning.getMessage(), not(containsString("secret CA detail")));
            assertThat(warning.getThrown(), nullValue());
            assertThat(tls.generation(), is(0L));
            assertThat(tls.sslContext().getServerSessionContext(), sameInstance(initialSessions));
            downloader.state(new State("1", "1", "test-keys/ecCert.pem", null, null));
            await(() -> tls.generation() == 1, "CA retry");
            assertThat(trustedCa(manager).getPublicKey().getAlgorithm(), is("EC"));
            assertThat(downloader.privateCalls(), is(1));
        }
    }

    @Test
    void concurrentInitializationPublishesOneContextAndTask() throws Exception {
        OciCertificatesTlsManager manager = newManager(false, INACTIVE_SCHEDULE);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Tls> first = executor.submit(() -> {
                start.await();
                return Tls.create(builder -> builder.manager(manager));
            });
            Future<Tls> second = executor.submit(() -> {
                start.await();
                return Tls.create(builder -> builder.manager(manager));
            });
            start.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).sslContext(), sameInstance(second.get(5, TimeUnit.SECONDS).sslContext()));
        } finally {
            executor.shutdownNow();
        }
        assertThat(taskManager.tasks().size(), is(1));
        assertThat(downloader.privateCalls(), is(1));
        assertThat(manager.generation(), is(0L));
    }

    @Test
    void generationRemainsReadableDuringPrivateBundleDownload() throws Exception {
        OciCertificatesTlsManager manager = newManager(false, SECONDLY_SCHEDULE);
        Tls tls = Tls.create(builder -> builder.manager(manager));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        downloader.blockPrivateDownload(entered, release);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            downloader.state(new State("2", "2", "test-keys/ca.pem", null, null));
            assertThat(entered.await(5, TimeUnit.SECONDS), is(true));
            Future<Long> generation = executor.submit(tls::generation);
            assertThat("current TLS material remains available during OCI I/O",
                       generation.get(10, TimeUnit.SECONDS), is(0L));
            assertThat("the generation read completed before the download was released",
                       downloader.privateDownloadWaiting(), is(true));
            assertThat(privateKeyAlgorithm(manager), is("RSA"));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
        await(() -> tls.generation() == 1, "unblocked certificate rotation");
        assertThat(privateKeyAlgorithm(manager), is("EC"));
    }

    @Test
    void existingServerListenerUsesNewIdentityAfterRotation() throws Exception {
        OciCertificatesTlsManager manager = newManager(false, SECONDLY_SCHEDULE);
        Tls tls = Tls.create(builder -> builder.manager(manager));
        try (SSLServerSocket listener = tls.createServerSocket()) {
            listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            listener.setEnabledProtocols(new String[] {"TLSv1.2"});
            listener.setSoTimeout(5000);
            assertThat(peerCertificate(listener).getPublicKey().getAlgorithm(), is("RSA"));
            downloader.state(new State("2", "2", "test-keys/ca.pem", null, null));
            await(() -> tls.generation() == 1, "identity rotation with listener open");
            assertThat(peerCertificate(listener).getPublicKey().getAlgorithm(), is("EC"));
        }
    }

    private static String privateKeyAlgorithm(OciCertificatesTlsManager manager) {
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
        return "";
    }

    private static X509Certificate trustedCa(OciCertificatesTlsManager manager) {
        X509Certificate[] acceptedIssuers = manager.trustManager().orElseThrow().getAcceptedIssuers();
        assertThat(acceptedIssuers.length, is(1));
        return acceptedIssuers[0];
    }

    private static X509Certificate peerCertificate(SSLServerSocketFactory serverSocketFactory) throws Exception {
        try (SSLServerSocket serverSocket = (SSLServerSocket) serverSocketFactory.createServerSocket()) {
            serverSocket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            serverSocket.setEnabledProtocols(new String[] {"TLSv1.2"});
            serverSocket.setSoTimeout(5000);
            return peerCertificate(serverSocket);
        }
    }

    private static X509Certificate peerCertificate(SSLServerSocket serverSocket) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> serverHandshake = executor.submit(() -> {
                try (SSLSocket socket = (SSLSocket) serverSocket.accept()) {
                    socket.setSoTimeout(5000);
                    socket.startHandshake();
                }
                return null;
            });
            SSLContext clientContext = trustAllContext();
            try (SSLSocket client = (SSLSocket) clientContext.getSocketFactory()
                    .createSocket(InetAddress.getLoopbackAddress(), serverSocket.getLocalPort())) {
                client.setEnabledProtocols(new String[] {"TLSv1.2"});
                client.setSoTimeout(5000);
                client.startHandshake();
                X509Certificate peer = (X509Certificate) client.getSession().getPeerCertificates()[0];
                serverHandshake.get(5, TimeUnit.SECONDS);
                return peer;
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static SSLSocketFactory mutualTlsClientFactory() {
        String keyPem = testResource("ecKey.pem");
        PrivateKey privateKey = Keys.builder()
                .pem(pem -> pem.key(Resource.create("test client private key", keyPem)))
                .build()
                .privateKey()
                .orElseThrow();
        X509Certificate certificate = PemReader.readCertificates(new ByteArrayInputStream(
                testResource("ecCert.pem").getBytes(StandardCharsets.US_ASCII))).getFirst();
        Tls tls = Tls.builder()
                .privateKey(privateKey)
                .privateKeyCertChain(List.of(certificate))
                .trustAll(true)
                .build();
        return tls.sslContext().getSocketFactory();
    }

    private static byte[] mutualTlsHandshake(SSLServerSocket serverSocket,
                                             SSLSocketFactory clientFactory) throws Exception {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<byte[]> serverHandshake = executor.submit(() -> {
                try (SSLSocket socket = (SSLSocket) serverSocket.accept()) {
                    socket.setEnabledProtocols(new String[] {"TLSv1.2"});
                    socket.setSoTimeout(5000);
                    socket.startHandshake();
                    socket.getOutputStream().write(1);
                    socket.getOutputStream().flush();
                    assertThat(socket.getInputStream().read(), is(1));
                    return socket.getSession().getId();
                }
            });

            try (SSLSocket client = (SSLSocket) clientFactory.createSocket(loopback,
                                                                           serverSocket.getLocalPort())) {
                client.setEnabledProtocols(new String[] {"TLSv1.2"});
                client.setSoTimeout(5000);
                try {
                    client.startHandshake();
                } catch (IOException e) {
                    try {
                        serverHandshake.get(5, TimeUnit.SECONDS);
                    } catch (ExecutionException serverFailure) {
                        if (serverFailure.getCause() instanceof SSLHandshakeException handshakeFailure) {
                            throw handshakeFailure;
                        }
                        e.addSuppressed(serverFailure.getCause());
                    } catch (Exception serverFailure) {
                        e.addSuppressed(serverFailure);
                    }
                    throw e;
                }
                byte[] sessionId = client.getSession().getId();
                assertThat(client.getInputStream().read(), is(1));
                client.getOutputStream().write(1);
                client.getOutputStream().flush();
                assertThat("consume the server close_notify before reusing the session",
                           client.getInputStream().read(), is(-1));
                assertThat(Arrays.equals(serverHandshake.get(5, TimeUnit.SECONDS), sessionId), is(true));
                return sessionId;
            }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS), is(true));
        }
    }

    private static String testResource(String name) {
        try (InputStream input = OciCertificatesTlsManagerTest.class
                .getResourceAsStream("/test-keys/" + name)) {
            if (input == null) {
                throw new IllegalArgumentException("Missing test resource: " + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.US_ASCII);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read test resource: " + name, e);
        }
    }

    private static SSLContext trustAllContext() {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] {new TrustAllManager()}, null);
            return context;
        } catch (GeneralSecurityException e) {
            throw new AssertionError("Failed to create test TLS context", e);
        }
    }

    private static void await(BooleanSupplier condition, String description) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertThat("Timed out waiting for " + description, condition.getAsBoolean(), is(true));
    }

    private OciCertificatesTlsManager newVaultManager(Boolean alwaysReload) {
        OciCertificatesTlsManagerConfig.Builder builder = OciCertificatesTlsManagerConfig.builder()
                .schedule(SECONDLY_SCHEDULE)
                .caOcid("test-ca")
                .certOcid(certificateOcid)
                .vaultCryptoEndpoint(URI.create("https://vault.example.test"))
                .keyOcid("test-key")
                .keyPassword("test-password");
        if (alwaysReload != null) {
            builder.alwaysReload(alwaysReload);
        }
        return builder.build();
    }

    private OciCertificatesTlsManager newManager(boolean alwaysReload, String schedule) {
        return OciCertificatesTlsManager.create(OciCertificatesTlsManagerConfig.builder()
                                                             .schedule(schedule)
                                                             .privateKeySource(OciPrivateKeySource.CERTIFICATE_BUNDLE)
                                                             .alwaysReload(alwaysReload)
                                                             .caOcid("test-ca")
                                                             .certOcid(certificateOcid)
                                                             .buildPrototype());
    }

    private static final class TestLogHandler extends Handler implements AutoCloseable {
        private final List<LogRecord> records = new CopyOnWriteArrayList<>();
        private final Logger logger;
        private final String certificatePrefix;

        private TestLogHandler(Logger logger, String certificateOcid) {
            this.logger = logger;
            this.certificatePrefix = "Failed to refresh OCI certificate " + certificateOcid + " (";
            logger.addHandler(this);
        }

        @Override
        public void publish(LogRecord record) {
            if (record.getMessage().startsWith(certificatePrefix)) {
                records.add(record);
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
            logger.removeHandler(this);
        }

        private List<LogRecord> records() {
            return records;
        }
    }

    private static final class TrustAllManager implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
