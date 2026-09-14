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
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.helidon.common.Weight;
import io.helidon.common.Weighted;
import io.helidon.common.configurable.Resource;
import io.helidon.common.pki.Keys;
import io.helidon.common.pki.PemKeys;
import io.helidon.common.pki.PemReader;
import io.helidon.extensions.oci.v3.tls.certificates.spi.OciCertificatesDownloader;
import io.helidon.service.registry.Service;

@Service.Singleton
@Weight(Weighted.DEFAULT_WEIGHT + 1)
class TestOciCertificatesDownloader implements OciCertificatesDownloader {
    private final AtomicInteger publicCalls = new AtomicInteger();
    private final AtomicInteger privateCalls = new AtomicInteger();
    private final AtomicInteger caCalls = new AtomicInteger();

    private volatile State state = new State("1", "1", "test-keys/ca.pem", null, null);
    private volatile PrivateDownloadGate privateDownloadGate;
    private volatile boolean privateDownloadWaiting;

    @Override
    public Certificates loadCertificates(String certOcid) {
        State currentState = state;
        publicCalls.incrementAndGet();
        Objects.requireNonNull(certOcid);
        String resource = "2".equals(currentState.version()) ? "test-keys/ecCert.pem" : "test-keys/serverCert.pem";
        try (InputStream certIs =
                TestOciCertificatesDownloader.class.getClassLoader().getResourceAsStream(resource)) {
            X509Certificate certificate = toCertificate(certIs);
            return OciCertificatesDownloader.create(currentState.version(), new X509Certificate[] {certificate});
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public CertificatesWithPrivateKey loadCertificatesWithPrivateKey(String certOcid) {
        State currentState = state;
        PrivateDownloadGate gate = privateDownloadGate;
        privateCalls.incrementAndGet();
        Objects.requireNonNull(certOcid);
        if (gate != null) {
            privateDownloadWaiting = true;
            try {
                gate.entered().countDown();
                if (!gate.release().await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting for test download release");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } finally {
                privateDownloadWaiting = false;
            }
        }
        if (currentState.managedFailure() != null) {
            throw currentState.managedFailure();
        }

        ClassLoader classLoader = TestOciCertificatesDownloader.class.getClassLoader();
        String certificateResource = "2".equals(currentState.version()) ? "test-keys/ecCert.pem" : "test-keys/serverCert.pem";
        String keyResource = "2".equals(currentState.version()) ? "test-keys/ecKey.pem" : "test-keys/serverKey.pem";
        try (InputStream certIs = classLoader.getResourceAsStream(certificateResource);
                InputStream keyIs = classLoader.getResourceAsStream(keyResource)) {
            X509Certificate certificate = toCertificate(certIs);
            String keyPem = new String(Objects.requireNonNull(keyIs).readAllBytes(), StandardCharsets.US_ASCII);
            PemKeys pemKeys = PemKeys.builder()
                    .key(Resource.create("test private key", keyPem))
                    .build();
            PrivateKey privateKey = Keys.builder()
                    .pem(pemKeys)
                    .build()
                    .privateKey()
                    .orElseThrow();
            return OciCertificatesDownloader.create(currentState.privateVersion(),
                                                    new X509Certificate[] {certificate},
                                                    privateKey);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public X509Certificate loadCACertificate(String caCertOcid) {
        State currentState = state;
        caCalls.incrementAndGet();
        Objects.requireNonNull(caCertOcid);
        if (currentState.caFailure() != null) {
            throw currentState.caFailure();
        }

        try (InputStream caCertIs =
                TestOciCertificatesDownloader.class.getClassLoader().getResourceAsStream(currentState.caResource())) {
            return toCertificate(caCertIs);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    int publicCalls() {
        return publicCalls.get();
    }

    int privateCalls() {
        return privateCalls.get();
    }

    int caCalls() {
        return caCalls.get();
    }

    void state(State state) {
        this.state = Objects.requireNonNull(state);
    }

    void blockPrivateDownload(CountDownLatch entered, CountDownLatch release) {
        privateDownloadGate = new PrivateDownloadGate(Objects.requireNonNull(entered), Objects.requireNonNull(release));
    }

    boolean privateDownloadWaiting() {
        return privateDownloadWaiting;
    }

    private static X509Certificate toCertificate(InputStream inputStream) {
        List<X509Certificate> certificates = PemReader.readCertificates(Objects.requireNonNull(inputStream));
        if (certificates.size() != 1) {
            throw new IllegalStateException("Expected one test certificate, got: " + certificates.size());
        }
        return certificates.getFirst();
    }

    record State(String version,
                 String privateVersion,
                 String caResource,
                 RuntimeException managedFailure,
                 RuntimeException caFailure) {
    }

    private record PrivateDownloadGate(CountDownLatch entered, CountDownLatch release) {
    }
}
