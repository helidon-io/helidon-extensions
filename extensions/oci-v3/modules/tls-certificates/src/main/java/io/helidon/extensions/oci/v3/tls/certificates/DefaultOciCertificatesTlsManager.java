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

import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;

import io.helidon.common.tls.ConfiguredTlsManager;
import io.helidon.common.tls.TlsConfig;
import io.helidon.config.Config;
import io.helidon.extensions.oci.v3.tls.certificates.spi.OciCertificatesDownloader;
import io.helidon.extensions.oci.v3.tls.certificates.spi.OciPrivateKeyDownloader;
import io.helidon.scheduling.Cron;
import io.helidon.service.registry.GlobalServiceRegistry;
import io.helidon.service.registry.ServiceRegistry;

/**
 * The default implementation (service loader and provider-driven) implementation of {@link OciCertificatesTlsManager}.
 *
 * @see DefaultOciCertificatesTlsManagerProvider
 */
class DefaultOciCertificatesTlsManager extends ConfiguredTlsManager implements OciCertificatesTlsManager {
    static final String TYPE = "oci-certificates-tls-manager";
    private static final System.Logger LOGGER = System.getLogger(DefaultOciCertificatesTlsManager.class.getName());

    private final OciCertificatesTlsManagerConfig cfg;
    private final boolean alwaysReload;
    private final AtomicReference<ReloadToken> installedMaterial = new AtomicReference<>();
    private final ReentrantLock reloadLock = new ReentrantLock();

    private Supplier<OciPrivateKeyDownloader> pkDownloader;
    private Supplier<OciCertificatesDownloader> certDownloader;
    private TlsConfig tlsConfig;

    DefaultOciCertificatesTlsManager(OciCertificatesTlsManagerConfig cfg) {
        this(cfg, "@default", Config.empty());
    }

    DefaultOciCertificatesTlsManager(OciCertificatesTlsManagerConfig cfg,
                                     String name,
                                     Config config) {
        super(name, TYPE);
        this.cfg = Objects.requireNonNull(cfg);
        this.alwaysReload = cfg.alwaysReload().orElse(cfg.privateKeySource() == OciPrivateKeySource.VAULT);

        config.onChange(this::config);
    }

    @Override // TlsManager
    public void init(TlsConfig tls) {
        this.tlsConfig = tls;
        ServiceRegistry registry = GlobalServiceRegistry.registry();
        if (cfg.privateKeySource() == OciPrivateKeySource.VAULT) {
            this.pkDownloader = registry.supply(OciPrivateKeyDownloader.class);
        }
        this.certDownloader = registry.supply(OciCertificatesDownloader.class);
        ScheduledExecutorService asyncExecutor = Executors.newSingleThreadScheduledExecutor();

        // the initial loading of the tls
        loadContext(true);

        // now schedule for reload checking
        String taskIntervalDescription =
                Cron.builder()
                        .executor(asyncExecutor)
                        .expression(cfg.schedule())
                        .concurrentExecution(false)
                        .task(inv -> maybeReload())
                        .build()
                        .description();

        if (LOGGER.isLoggable(System.Logger.Level.DEBUG)) {
            LOGGER.log(System.Logger.Level.DEBUG,
                    "Scheduled: " + taskIntervalDescription);
        }
    }

    @Override // RuntimeType
    public OciCertificatesTlsManagerConfig prototype() {
        return cfg;
    }

    // ConfiguredTlsManager
    private void maybeReload() {
        try {
            if (loadContext(false)) {
                LOGGER.log(System.Logger.Level.DEBUG, "Certificates were downloaded and dynamically updated");
            }
        } catch (RuntimeException e) {
            String failureCategory;
            if (e instanceof UnsupportedOperationException) {
                failureCategory = "unsupported-operation";
            } else if (e instanceof IllegalArgumentException) {
                failureCategory = "invalid-tls-material";
            } else if (e instanceof IllegalStateException) {
                failureCategory = "oci-download-or-tls-state";
            } else {
                failureCategory = "runtime-failure";
            }
            LOGGER.log(System.Logger.Level.WARNING,
                    "Failed to refresh OCI certificate " + cfg.certOcid()
                            + " (failure category: " + failureCategory + ")"
                            + "; the previously installed TLS identity remains active and the refresh will be retried");
        }
    }

    /**
     * Sets the backing (possibly updated) configuration for this manager. This will trigger a reload.
     *
     * @param config the new config
     */
    void config(Config config) {
        Objects.requireNonNull(config);
        if (loadContext(false)) {
            LOGGER.log(System.Logger.Level.DEBUG, "Certificates were downloaded and dynamically updated");
        }
    }

    /**
     * Will download new certificates, and if those are determined to be changed will affect the reload of the new key and trust
     * managers.
     *
     * @return true if a reload occurred
     */
    boolean loadContext(boolean initialLoad) {
        reloadLock.lock();
        try {
            // download all of our security collateral from OCI
            OciCertificatesDownloader cd = certDownloader.get();
            String identityVersion;
            Certificate[] certificates;
            PrivateKey privateKey = null;
            if (cfg.privateKeySource() == OciPrivateKeySource.CERTIFICATE_BUNDLE) {
                OciCertificatesDownloader.CertificatesWithPrivateKey identity =
                        cd.loadCertificatesWithPrivateKey(cfg.certOcid());
                identityVersion = identity.version();
                certificates = identity.certificates();
                privateKey = identity.privateKey();
            } else {
                OciCertificatesDownloader.Certificates downloadedCertificates = cd.loadCertificates(cfg.certOcid());
                identityVersion = downloadedCertificates.version();
                certificates = downloadedCertificates.certificates();
            }

            X509Certificate ca = cd.loadCACertificate(cfg.caOcid());
            ReloadToken candidate = new ReloadToken(identityVersion, ca);
            if (!alwaysReload && candidate.equals(installedMaterial.get())) {
                return false;
            }

            if (privateKey == null) {
                OciPrivateKeyDownloader privateKeyDownloader = pkDownloader.get();
                privateKey = privateKeyDownloader.loadKey(cfg.keyOcid().orElseThrow(),
                                                         cfg.vaultCryptoEndpoint().orElseThrow());
            }

            SecureRandom secureRandom = secureRandom(tlsConfig);
            KeyManagerFactory kmf = buildKmf(tlsConfig,
                                             secureRandom,
                                             privateKey,
                                             certificates);

            TrustManagerFactory tmf;
            if (tlsConfig.trustAll()) {
                tmf = trustAllTmf();
            } else {
                tmf = createTmf(tlsConfig);
                KeyStore keyStore = internalKeystore(tlsConfig);
                keyStore.setCertificateEntry("trust-ca", ca);
                initializeTmf(tmf, keyStore, tlsConfig);
            }

            Optional<X509KeyManager> keyManager = Arrays.stream(kmf.getKeyManagers())
                    .filter(m -> m instanceof X509KeyManager)
                    .map(X509KeyManager.class::cast)
                    .findFirst();
            if (keyManager.isEmpty()) {
                throw new RuntimeException("Unable to find X.509 key manager in download: " + cfg.certOcid());
            }

            Optional<X509TrustManager> trustManager = Arrays.stream(tmf.getTrustManagers())
                    .filter(m -> m instanceof X509TrustManager)
                    .map(X509TrustManager.class::cast)
                    .findFirst();
            if (trustManager.isEmpty()) {
                throw new RuntimeException("Unable to find X.509 trust manager in download: " + cfg.certOcid());
            }

            if (initialLoad) {
                initSslContext(tlsConfig, secureRandom, kmf.getKeyManagers(), tmf.getTrustManagers());
            } else {
                reload(keyManager, trustManager);
            }

            installedMaterial.set(candidate);
            return true;
        } catch (KeyStoreException e) {
            throw new IllegalStateException("Error while loading context from OCI", e);
        } finally {
            reloadLock.unlock();
        }
    }

    private record ReloadToken(String identityVersion, X509Certificate caCertificate) {
    }

}
