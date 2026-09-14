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

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLContextSpi;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSessionContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;

import io.helidon.common.Api;
import io.helidon.common.tls.ConfiguredTlsManager;
import io.helidon.common.tls.RevocationConfig;
import io.helidon.common.tls.Tls;
import io.helidon.common.tls.TlsConfig;
import io.helidon.common.tls.TlsMaterial;
import io.helidon.extensions.oci.v3.tls.certificates.spi.OciCertificatesDownloader;
import io.helidon.extensions.oci.v3.tls.certificates.spi.OciPrivateKeyDownloader;
import io.helidon.scheduling.Cron;
import io.helidon.scheduling.TaskManager;
import io.helidon.service.registry.GlobalServiceRegistry;
import io.helidon.service.registry.ServiceRegistry;

import com.oracle.bmc.model.BmcException;

/**
 * Default implementation of {@link OciCertificatesTlsManager}.
 *
 * @see DefaultOciCertificatesTlsManagerProvider
 */
class DefaultOciCertificatesTlsManager extends ConfiguredTlsManager
        implements OciCertificatesTlsManager {
    static final String TYPE = "oci-certificates-tls-manager";
    private static final System.Logger LOGGER =
            System.getLogger(DefaultOciCertificatesTlsManager.class.getName());

    private final OciCertificatesTlsManagerConfig cfg;
    private final boolean alwaysReload;
    private final AtomicReference<InstalledMaterial> installedMaterial = new AtomicReference<>();
    private final ReentrantLock lifecycleLock = new ReentrantLock();

    private Supplier<OciCertificatesDownloader> certDownloader;
    private Supplier<OciPrivateKeyDownloader> keyDownloader;
    private ContextConfiguration contextConfiguration;
    private SSLContext sslContext;
    private ServiceRegistry serviceRegistry;
    private TlsConfig tlsConfig;
    private Cron reloadTask;
    private TaskManager taskManager;
    private boolean initialized;

    DefaultOciCertificatesTlsManager(OciCertificatesTlsManagerConfig cfg) {
        this(cfg, "@default");
    }

    DefaultOciCertificatesTlsManager(OciCertificatesTlsManagerConfig cfg,
                                    String name) {
        super(name, TYPE);
        this.cfg = Objects.requireNonNull(cfg);
        this.alwaysReload = cfg.alwaysReload().orElse(cfg.privateKeySource() == OciPrivateKeySource.VAULT);
    }

    @Override
    public void init(TlsConfig tls) {
        Objects.requireNonNull(tls, "tls");
        lifecycleLock.lock();
        try {
            ContextConfiguration requestedContext = ContextConfiguration.create(tls);
            if (initialized) {
                if (!contextConfiguration.equals(requestedContext)) {
                    throw new IllegalArgumentException("A shared OCI certificates TLS manager requires matching "
                                                               + "TLS context configuration");
                }
                ServiceRegistry registry = GlobalServiceRegistry.registry();
                TaskManager requestedTaskManager = registry.get(TaskManager.class);
                if (!reloadTaskActive(registry, requestedTaskManager)) {
                    Supplier<OciCertificatesDownloader> requestedDownloader =
                            registry.supply(OciCertificatesDownloader.class);
                    if (reloadTask != null) {
                        reloadTask.close();
                        reloadTask = null;
                    }
                    certDownloader = requestedDownloader;
                    keyDownloader = cfg.privateKeySource() == OciPrivateKeySource.VAULT
                            ? registry.supply(OciPrivateKeyDownloader.class) : null;
                    serviceRegistry = registry;
                    taskManager = requestedTaskManager;
                    maybeReload();
                    scheduleReloadTask();
                }
                return;
            }

            ServiceRegistry registry = GlobalServiceRegistry.registry();
            certDownloader = registry.supply(OciCertificatesDownloader.class);
            keyDownloader = cfg.privateKeySource() == OciPrivateKeySource.VAULT
                    ? registry.supply(OciPrivateKeyDownloader.class) : null;
            serviceRegistry = registry;
            taskManager = registry.get(TaskManager.class);
            tlsConfig = tls;
            contextConfiguration = requestedContext;

            InstalledMaterial initialMaterial = loadMaterial();
            installedMaterial.set(initialMaterial);
            sslContext = new SwitchingSslContext(installedMaterial);

            try {
                scheduleReloadTask();
                initialized = true;
            } catch (RuntimeException e) {
                if (reloadTask != null) {
                    reloadTask.close();
                    reloadTask = null;
                }
                installedMaterial.set(null);
                certDownloader = null;
                keyDownloader = null;
                contextConfiguration = null;
                sslContext = null;
                serviceRegistry = null;
                taskManager = null;
                tlsConfig = null;
                throw e;
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    @Override
    public SSLContext sslContext() {
        return Objects.requireNonNull(sslContext, "TLS manager is not initialized");
    }

    @Override
    public Optional<X509KeyManager> keyManager() {
        InstalledMaterial material = installedMaterial.get();
        return material == null ? Optional.empty() : Optional.of(material.keyManager());
    }

    @Override
    public Optional<X509TrustManager> trustManager() {
        InstalledMaterial material = installedMaterial.get();
        return material == null ? Optional.empty() : Optional.of(material.trustManager());
    }

    @Override
    public OciCertificatesTlsManagerConfig prototype() {
        return cfg;
    }

    @Override
    @SuppressWarnings("removal")
    public void reload(Tls tls) {
        Objects.requireNonNull(tls);
        throw new UnsupportedOperationException("OCI TLS material is reloaded from OCI");
    }

    @Override
    public void reload(TlsMaterial material) {
        Objects.requireNonNull(material);
        throw new UnsupportedOperationException("OCI TLS material is reloaded from OCI");
    }

    @Override
    @Api.Internal
    public long generation() {
        InstalledMaterial material = installedMaterial.get();
        return material == null ? 0 : material.generation();
    }

    private static Optional<X509KeyManager> x509KeyManager(KeyManager[] keyManagers) {
        return Arrays.stream(keyManagers)
                .filter(X509KeyManager.class::isInstance)
                .map(X509KeyManager.class::cast)
                .findFirst();
    }

    private static Optional<X509TrustManager> x509TrustManager(TrustManager[] trustManagers) {
        return Arrays.stream(trustManagers)
                .filter(X509TrustManager.class::isInstance)
                .map(X509TrustManager.class::cast)
                .findFirst();
    }

    private static String failureDetails(RuntimeException exception) {
        Optional<MaterialLoadException> materialLoadFailure = findCause(exception, MaterialLoadException.class);
        String phase = materialLoadFailure.map(failure -> failure.phase().description())
                .orElse(MaterialLoadPhase.UNKNOWN.description());
        Optional<BmcException> bmcFailure = findCause(exception, BmcException.class);
        if (bmcFailure.isPresent()) {
            BmcException failure = bmcFailure.get();
            String category;
            if (failure.isTimeout()) {
                category = "oci-timeout";
            } else if (failure.isClientSide()) {
                category = "oci-client-failure";
            } else {
                category = "oci-service-failure";
            }
            return "phase: " + phase
                    + ", failure category: " + category
                    + ", status code: " + failure.getStatusCode()
                    + ", service code: " + safeOciDiagnostic(failure.getServiceCode())
                    + ", opc request id: " + safeOciDiagnostic(failure.getOpcRequestId())
                    + ", client side: " + failure.isClientSide()
                    + ", timeout: " + failure.isTimeout();
        }

        Throwable failure = materialLoadFailure.map(Throwable::getCause).orElse(exception);
        String category;
        if (failure instanceof UnsupportedOperationException) {
            category = "unsupported-operation";
        } else if (failure instanceof IllegalArgumentException) {
            category = "invalid-tls-material";
        } else if (failure instanceof IllegalStateException) {
            category = "oci-download-or-tls-state";
        } else {
            category = "runtime-failure";
        }
        return "phase: " + phase + ", failure category: " + category;
    }

    private static String safeOciDiagnostic(String value) {
        if (value == null || value.isBlank()) {
            return "unavailable";
        }
        int length = Math.min(value.length(), 256);
        StringBuilder result = new StringBuilder(length + 3);
        for (int i = 0; i < length; i++) {
            char character = value.charAt(i);
            result.append(Character.isISOControl(character) ? '?' : character);
        }
        if (value.length() > length) {
            result.append("...");
        }
        return result.toString();
    }

    private static <T extends Throwable> Optional<T> findCause(Throwable throwable, Class<T> type) {
        Throwable current = throwable;
        for (int i = 0; current != null && i < 32; i++) {
            if (type.isInstance(current)) {
                return Optional.of(type.cast(current));
            }
            current = current.getCause();
        }
        return Optional.empty();
    }

    private static <T> T load(MaterialLoadPhase phase, Supplier<T> loader) {
        try {
            return loader.get();
        } catch (MaterialLoadException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new MaterialLoadException(phase, e);
        }
    }

    private boolean reloadTaskActive(ServiceRegistry requestedRegistry, TaskManager requestedTaskManager) {
        return serviceRegistry == requestedRegistry
                && taskManager == requestedTaskManager
                && reloadTask != null
                && requestedTaskManager.tasks().contains(reloadTask);
    }

    private void scheduleReloadTask() {
        reloadTask = Cron.builder()
                .expression(cfg.schedule())
                .concurrentExecution(false)
                .taskManager(taskManager)
                .task(invocation -> maybeReload())
                .build();
        if (LOGGER.isLoggable(System.Logger.Level.DEBUG)) {
            LOGGER.log(System.Logger.Level.DEBUG, "Scheduled: " + reloadTask.description());
        }
    }

    private void maybeReload() {
        try {
            lifecycleLock.lock();
            try {
                if (!initialized) {
                    return;
                }
                InstalledMaterial candidate = loadMaterial();
                if (candidate != null) {
                    installedMaterial.set(candidate);
                    LOGGER.log(System.Logger.Level.DEBUG, "Certificates were downloaded and dynamically updated");
                }
            } finally {
                lifecycleLock.unlock();
            }
        } catch (RuntimeException e) {
            LOGGER.log(System.Logger.Level.WARNING,
                       "Failed to refresh OCI certificate " + cfg.certOcid()
                               + " (" + failureDetails(e) + ")"
                               + "; the previously installed TLS identity remains active and the refresh will be retried");
        }
    }

    private InstalledMaterial loadMaterial() {
        OciCertificatesDownloader downloader = load(MaterialLoadPhase.DOWNLOADER_RESOLUTION, certDownloader::get);
        OciCertificatesDownloader.Certificates publicIdentity =
                load(MaterialLoadPhase.PUBLIC_CERTIFICATE_BUNDLE,
                     () -> downloader.loadCertificates(cfg.certOcid()));
        X509Certificate ca = load(MaterialLoadPhase.CA_CERTIFICATE_BUNDLE,
                                  () -> downloader.loadCACertificate(cfg.caOcid()));

        InstalledMaterial current = installedMaterial.get();
        ReloadToken candidateToken = new ReloadToken(publicIdentity.version(), ca);
        if (current != null && !alwaysReload && candidateToken.equals(current.reloadToken())) {
            return null;
        }

        OciCertificatesDownloader.CertificatesWithPrivateKey identity;
        if (cfg.privateKeySource() == OciPrivateKeySource.VAULT) {
            identity = load(MaterialLoadPhase.VAULT_PRIVATE_KEY, () -> {
                PrivateKey privateKey = keyDownloader.get().loadKey(cfg.keyOcid().orElseThrow(),
                                                                   cfg.vaultCryptoEndpoint().orElseThrow());
                return OciCertificatesDownloader.create(publicIdentity.version(), publicIdentity.certificates(), privateKey);
            });
        } else if (current == null || !publicIdentity.version().equals(current.identity().version())) {
            identity = load(MaterialLoadPhase.PRIVATE_KEY_CERTIFICATE_BUNDLE,
                            () -> downloader.loadCertificatesWithPrivateKey(cfg.certOcid()));
            if (!publicIdentity.version().equals(identity.version())) {
                throw new MaterialLoadException(
                        MaterialLoadPhase.IDENTITY_VERSION_VALIDATION,
                        new IllegalStateException("OCI certificate version changed while the private-key bundle was downloaded: "
                                                          + publicIdentity.version() + " -> " + identity.version()));
            }
        } else {
            identity = current.identity();
        }

        OciCertificatesDownloader.CertificatesWithPrivateKey candidateIdentity = identity;
        return load(MaterialLoadPhase.TLS_CONTEXT_BUILD,
                    () -> createMaterial(candidateIdentity, ca, candidateToken));
    }

    private InstalledMaterial createMaterial(OciCertificatesDownloader.CertificatesWithPrivateKey identity,
                                             X509Certificate ca,
                                             ReloadToken reloadToken) {
        try {
            Certificate[] certificates = identity.certificates();
            PrivateKey privateKey = identity.privateKey();
            SecureRandom secureRandom = secureRandom(tlsConfig);
            KeyManagerFactory kmf = buildKmf(tlsConfig, secureRandom, privateKey, certificates);

            TrustManagerFactory tmf;
            if (tlsConfig.trustAll()) {
                tmf = trustAllTmf();
            } else {
                tmf = createTmf(tlsConfig);
                KeyStore keyStore = internalKeystore(tlsConfig);
                keyStore.setCertificateEntry("trust-ca", ca);
                initializeTmf(tmf, keyStore, tlsConfig);
            }

            KeyManager[] keyManagers = kmf.getKeyManagers();
            TrustManager[] trustManagers = tmf.getTrustManagers();
            X509KeyManager keyManager = x509KeyManager(keyManagers)
                    .orElseThrow(() -> new IllegalStateException("Unable to find X.509 key manager in download: "
                                                                         + cfg.certOcid()));
            X509TrustManager trustManager = x509TrustManager(trustManagers)
                    .orElseThrow(() -> new IllegalStateException("Unable to find X.509 trust manager in download: "
                                                                           + cfg.certOcid()));
            SSLContext context = tlsConfig.provider().isPresent()
                    ? SSLContext.getInstance(tlsConfig.protocol(), tlsConfig.provider().get())
                    : SSLContext.getInstance(tlsConfig.protocol());
            context.init(keyManagers, trustManagers, secureRandom);
            configureSessions(context.getServerSessionContext());
            configureSessions(context.getClientSessionContext());
            InstalledMaterial current = installedMaterial.get();
            long generation = current == null ? 0 : current.generation() + 1;
            return new InstalledMaterial(context, keyManager, trustManager, identity, reloadToken, generation);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Error while loading context from OCI", e);
        }
    }

    private void configureSessions(SSLSessionContext sessions) {
        if (sessions != null) {
            if (tlsConfig.sessionCacheSize() != TlsConfig.DEFAULT_SESSION_CACHE_SIZE) {
                sessions.setSessionCacheSize(tlsConfig.sessionCacheSize());
            }
            sessions.setSessionTimeout((int) tlsConfig.sessionTimeout().toSeconds());
        }
    }

    private enum MaterialLoadPhase {
        DOWNLOADER_RESOLUTION("downloader-resolution"),
        PUBLIC_CERTIFICATE_BUNDLE("public-certificate-bundle"),
        CA_CERTIFICATE_BUNDLE("ca-certificate-bundle"),
        PRIVATE_KEY_CERTIFICATE_BUNDLE("private-key-certificate-bundle"),
        VAULT_PRIVATE_KEY("vault-private-key"),
        IDENTITY_VERSION_VALIDATION("identity-version-validation"),
        TLS_CONTEXT_BUILD("tls-context-build"),
        UNKNOWN("unknown");

        private final String description;

        MaterialLoadPhase(String description) {
            this.description = description;
        }

        private String description() {
            return description;
        }
    }

    private record ReloadToken(String identityVersion, X509Certificate caCertificate) {
    }

    private record ContextConfiguration(Optional<SecureRandom> secureRandom,
                                        Optional<String> secureRandomProvider,
                                        Optional<String> secureRandomAlgorithm,
                                        Optional<String> keyManagerFactoryProvider,
                                        Optional<String> keyManagerFactoryAlgorithm,
                                        Optional<String> trustManagerFactoryProvider,
                                        Optional<String> trustManagerFactoryAlgorithm,
                                        boolean trustAll,
                                        String protocol,
                                        Optional<String> provider,
                                        int sessionCacheSize,
                                        Duration sessionTimeout,
                                        Optional<String> internalKeystoreType,
                                        Optional<String> internalKeystoreProvider,
                                        Optional<RevocationConfig> revocation) {
        private static ContextConfiguration create(TlsConfig config) {
            return new ContextConfiguration(config.secureRandom(),
                                            config.secureRandomProvider(),
                                            config.secureRandomAlgorithm(),
                                            config.keyManagerFactoryProvider(),
                                            config.keyManagerFactoryAlgorithm(),
                                            config.trustManagerFactoryProvider(),
                                            config.trustManagerFactoryAlgorithm(),
                                            config.trustAll(),
                                            config.protocol(),
                                            config.provider(),
                                            config.sessionCacheSize(),
                                            config.sessionTimeout(),
                                            config.internalKeystoreType(),
                                            config.internalKeystoreProvider(),
                                            config.revocation());
        }
    }

    private record InstalledMaterial(SSLContext sslContext,
                                     X509KeyManager keyManager,
                                     X509TrustManager trustManager,
                                     OciCertificatesDownloader.CertificatesWithPrivateKey identity,
                                     ReloadToken reloadToken,
                                     long generation) {
    }

    private static final class MaterialLoadException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        private final MaterialLoadPhase phase;

        private MaterialLoadException(MaterialLoadPhase phase, RuntimeException cause) {
            super("Failed to load OCI-managed TLS material during phase: " + phase.description(), cause);
            this.phase = phase;
        }

        private MaterialLoadPhase phase() {
            return phase;
        }
    }

    private static final class SwitchingSslContext extends SSLContext {
        private SwitchingSslContext(AtomicReference<InstalledMaterial> material) {
            super(new SwitchingSslContextSpi(material),
                  material.get().sslContext().getProvider(),
                  material.get().sslContext().getProtocol());
        }
    }

    private static final class SwitchingSslContextSpi extends SSLContextSpi {
        private final AtomicReference<InstalledMaterial> material;
        private final SSLSocketFactory socketFactory;
        private final SSLServerSocketFactory serverSocketFactory;

        private SwitchingSslContextSpi(AtomicReference<InstalledMaterial> material) {
            this.material = material;
            socketFactory = new SwitchingSslSocketFactory(material);
            serverSocketFactory = new SwitchingSslServerSocketFactory(material);
        }

        @Override
        protected void engineInit(KeyManager[] keyManagers,
                                  TrustManager[] trustManagers,
                                  SecureRandom secureRandom) throws KeyManagementException {
            throw new KeyManagementException("OCI-managed SSLContext cannot be initialized externally");
        }

        @Override
        protected SSLSocketFactory engineGetSocketFactory() {
            return socketFactory;
        }

        @Override
        protected SSLServerSocketFactory engineGetServerSocketFactory() {
            return serverSocketFactory;
        }

        @Override
        protected SSLEngine engineCreateSSLEngine() {
            return sslContext().createSSLEngine();
        }

        @Override
        protected SSLEngine engineCreateSSLEngine(String peerHost, int peerPort) {
            return sslContext().createSSLEngine(peerHost, peerPort);
        }

        @Override
        protected SSLSessionContext engineGetServerSessionContext() {
            return sslContext().getServerSessionContext();
        }

        @Override
        protected SSLSessionContext engineGetClientSessionContext() {
            return sslContext().getClientSessionContext();
        }

        @Override
        protected SSLParameters engineGetDefaultSSLParameters() {
            return sslContext().getDefaultSSLParameters();
        }

        @Override
        protected SSLParameters engineGetSupportedSSLParameters() {
            return sslContext().getSupportedSSLParameters();
        }

        private SSLContext sslContext() {
            return material.get().sslContext();
        }
    }

    private static final class SwitchingSslSocketFactory extends SSLSocketFactory {
        private final AtomicReference<InstalledMaterial> material;

        private SwitchingSslSocketFactory(AtomicReference<InstalledMaterial> material) {
            this.material = material;
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return delegate().getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return delegate().getSupportedCipherSuites();
        }

        @Override
        public Socket createSocket() throws IOException {
            return delegate().createSocket();
        }

        @Override
        public Socket createSocket(Socket socket,
                                   String host,
                                   int port,
                                   boolean autoClose) throws IOException {
            return delegate().createSocket(socket, host, port, autoClose);
        }

        @Override
        public Socket createSocket(Socket socket,
                                   InputStream consumed,
                                   boolean autoClose) throws IOException {
            return delegate().createSocket(socket, consumed, autoClose);
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return delegate().createSocket(host, port);
        }

        @Override
        public Socket createSocket(String host,
                                   int port,
                                   InetAddress localAddress,
                                   int localPort) throws IOException {
            return delegate().createSocket(host, port, localAddress, localPort);
        }

        @Override
        public Socket createSocket(InetAddress address, int port) throws IOException {
            return delegate().createSocket(address, port);
        }

        @Override
        public Socket createSocket(InetAddress address,
                                   int port,
                                   InetAddress localAddress,
                                   int localPort) throws IOException {
            return delegate().createSocket(address, port, localAddress, localPort);
        }

        private SSLSocketFactory delegate() {
            return material.get().sslContext().getSocketFactory();
        }
    }

    private static final class SwitchingSslServerSocketFactory extends SSLServerSocketFactory {
        private final AtomicReference<InstalledMaterial> material;

        private SwitchingSslServerSocketFactory(AtomicReference<InstalledMaterial> material) {
            this.material = material;
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return material.get().sslContext().getServerSocketFactory().getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return material.get().sslContext().getServerSocketFactory().getSupportedCipherSuites();
        }

        @Override
        public ServerSocket createServerSocket() throws IOException {
            return new SwitchingSslServerSocket(material);
        }

        @Override
        public ServerSocket createServerSocket(int port) throws IOException {
            return createServerSocket(port, 50);
        }

        @Override
        public ServerSocket createServerSocket(int port, int backlog) throws IOException {
            return createServerSocket(port, backlog, null);
        }

        @Override
        public ServerSocket createServerSocket(int port,
                                               int backlog,
                                               InetAddress ifAddress) throws IOException {
            SwitchingSslServerSocket socket = new SwitchingSslServerSocket(material);
            try {
                socket.bind(new InetSocketAddress(ifAddress, port), backlog);
                return socket;
            } catch (IOException | RuntimeException | Error failure) {
                try {
                    socket.close();
                } catch (IOException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
                throw failure;
            }
        }
    }

    private static final class SwitchingSslServerSocket extends SSLServerSocket {
        private final AtomicReference<InstalledMaterial> material;
        private final ReentrantLock configurationLock = new ReentrantLock();
        private final SSLEngine configuration;

        private SwitchingSslServerSocket(AtomicReference<InstalledMaterial> material) throws IOException {
            this.material = material;
            configuration = material.get().sslContext().createSSLEngine();
            configuration.setUseClientMode(false);
        }

        @Override
        public Socket accept() throws IOException {
            Socket accepted = super.accept();
            Socket result = accepted;
            try {
                InstalledMaterial current = material.get();
                SSLParameters parameters;
                boolean clientMode;
                boolean sessionCreation;
                configurationLock.lock();
                try {
                    parameters = configuration.getSSLParameters();
                    clientMode = configuration.getUseClientMode();
                    sessionCreation = configuration.getEnableSessionCreation();
                } finally {
                    configurationLock.unlock();
                }
                SSLSocket socket = (SSLSocket) current.sslContext().getSocketFactory()
                        .createSocket(accepted, accepted.getInetAddress().getHostAddress(), accepted.getPort(), true);
                result = socket;
                socket.setUseClientMode(clientMode);
                socket.setSSLParameters(parameters);
                socket.setEnableSessionCreation(sessionCreation);
                return socket;
            } catch (IOException | RuntimeException | Error failure) {
                try {
                    result.close();
                } catch (IOException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
                throw failure;
            }
        }

        @Override
        public String[] getEnabledCipherSuites() {
            configurationLock.lock();
            try {
                return configuration.getEnabledCipherSuites();
            } finally {
                configurationLock.unlock();
            }
        }

        @Override
        public void setEnabledCipherSuites(String[] suites) {
            configurationLock.lock();
            try {
                configuration.setEnabledCipherSuites(suites);
            } finally {
                configurationLock.unlock();
            }
        }

        @Override
        public String[] getSupportedCipherSuites() {
            configurationLock.lock();
            try {
                return configuration.getSupportedCipherSuites();
            } finally {
                configurationLock.unlock();
            }
        }

        @Override
        public String[] getSupportedProtocols() {
            configurationLock.lock();
            try {
                return configuration.getSupportedProtocols();
            } finally {
                configurationLock.unlock();
            }
        }

        @Override
        public String[] getEnabledProtocols() {
            configurationLock.lock();
            try {
                return configuration.getEnabledProtocols();
            } finally {
                configurationLock.unlock();
            }
        }

        @Override
        public void setEnabledProtocols(String[] protocols) {
            configurationLock.lock();
            try {
                configuration.setEnabledProtocols(protocols);
            } finally {
                configurationLock.unlock();
            }
        }

        @Override
        public void setNeedClientAuth(boolean need) {
            configurationLock.lock();
            try {
                configuration.setNeedClientAuth(need);
            } finally {
                configurationLock.unlock();
            }
        }

        @Override
        public boolean getNeedClientAuth() {
            configurationLock.lock();
            try {
                return configuration.getNeedClientAuth();
            } finally {
                configurationLock.unlock();
            }
        }

        @Override
        public void setWantClientAuth(boolean want) {
            configurationLock.lock();
            try {
                configuration.setWantClientAuth(want);
            } finally {
                configurationLock.unlock();
            }
        }

        @Override
        public boolean getWantClientAuth() {
            configurationLock.lock();
            try {
                return configuration.getWantClientAuth();
            } finally {
                configurationLock.unlock();
            }
        }

        @Override
        public void setUseClientMode(boolean mode) {
            configurationLock.lock();
            try {
                configuration.setUseClientMode(mode);
            } finally {
                configurationLock.unlock();
            }
        }

        @Override
        public boolean getUseClientMode() {
            configurationLock.lock();
            try {
                return configuration.getUseClientMode();
            } finally {
                configurationLock.unlock();
            }
        }

        @Override
        public void setEnableSessionCreation(boolean enabled) {
            configurationLock.lock();
            try {
                configuration.setEnableSessionCreation(enabled);
            } finally {
                configurationLock.unlock();
            }
        }

        @Override
        public boolean getEnableSessionCreation() {
            configurationLock.lock();
            try {
                return configuration.getEnableSessionCreation();
            } finally {
                configurationLock.unlock();
            }
        }

        @Override
        public SSLParameters getSSLParameters() {
            configurationLock.lock();
            try {
                return configuration.getSSLParameters();
            } finally {
                configurationLock.unlock();
            }
        }

        @Override
        public void setSSLParameters(SSLParameters parameters) {
            configurationLock.lock();
            try {
                configuration.setSSLParameters(parameters);
            } finally {
                configurationLock.unlock();
            }
        }
    }
}
