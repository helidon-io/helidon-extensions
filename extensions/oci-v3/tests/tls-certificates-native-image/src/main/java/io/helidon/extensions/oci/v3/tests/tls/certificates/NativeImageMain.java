/*
 * Copyright (c) 2026 Oracle and/or its affiliates.
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

package io.helidon.extensions.oci.v3.tests.tls.certificates;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Objects;

import io.helidon.extensions.oci.v3.tls.certificates.spi.OciCertificatesDownloader;

/**
 * Exercises OCI certificate bundle validation in JVM and native-image packaging.
 */
public final class NativeImageMain {
    private NativeImageMain() {
    }

    /**
     * Validates matching RSA and EC material and rejects a mismatched pair.
     *
     * @param args command-line arguments
     * @throws IOException if test material cannot be read
     * @throws GeneralSecurityException if test material cannot be decoded
     * @throws ClassNotFoundException if the default downloader is missing
     */
    public static void main(String[] args) throws IOException, GeneralSecurityException, ClassNotFoundException {
        Class.forName("io.helidon.extensions.oci.v3.tls.certificates.DefaultOciCertificatesDownloader");
        X509Certificate rsaCertificate = certificate("serverCert.pem");
        PrivateKey rsaKey = privateKey("serverKey.pem", "RSA");
        X509Certificate ecCertificate = certificate("ecCert.pem");
        PrivateKey ecKey = privateKey("ecKey.pem", "EC");

        OciCertificatesDownloader.create("rsa", new X509Certificate[] {rsaCertificate}, rsaKey);
        OciCertificatesDownloader.create("ec", new X509Certificate[] {ecCertificate}, ecKey);
        try {
            OciCertificatesDownloader.create("mismatched", new X509Certificate[] {rsaCertificate}, ecKey);
        } catch (IllegalArgumentException ignored) {
            System.out.println("OCI certificate key-pair validation passed");
            return;
        }
        throw new IllegalStateException("A certificate with a mismatched private key was accepted");
    }

    private static X509Certificate certificate(String file) throws IOException, GeneralSecurityException {
        try (InputStream input = resource(file)) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
        }
    }

    private static PrivateKey privateKey(String file, String algorithm) throws IOException, GeneralSecurityException {
        try (InputStream input = resource(file)) {
            String pem = new String(input.readAllBytes(), StandardCharsets.US_ASCII)
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "");
            byte[] encoded = Base64.getMimeDecoder().decode(pem);
            return KeyFactory.getInstance(algorithm).generatePrivate(new PKCS8EncodedKeySpec(encoded));
        }
    }

    private static InputStream resource(String file) {
        return Objects.requireNonNull(NativeImageMain.class.getResourceAsStream("/test-keys/" + file), file);
    }
}
