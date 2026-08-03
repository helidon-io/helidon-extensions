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

package io.helidon.extensions.oci.v3.tls.certificates;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import io.helidon.builder.api.Prototype;
import io.helidon.config.Config;
import io.helidon.config.MissingValueException;

/**
 * Support for the OCI certificates TLS manager configuration prototype.
 */
final class OciCertificatesTlsManagerConfigSupport {
    private static final URI UNUSED_VAULT_CRYPTO_ENDPOINT =
            URI.create("urn:helidon:oci-certificates:unused-vault-crypto-endpoint");
    private static final String UNUSED_KEY_OCID = "helidon:oci-certificates:unused-key-ocid";
    private static final Supplier<char[]> MISSING_KEY_PASSWORD =
            () -> {
                throw MissingValueException.create(Config.Key.create("key-password"));
            };

    private OciCertificatesTlsManagerConfigSupport() {
    }

    static final class BuilderDecorator
            implements Prototype.BuilderDecorator<OciCertificatesTlsManagerConfig.BuilderBase<?, ?>> {

        BuilderDecorator() {
        }

        @Override
        public void decorate(OciCertificatesTlsManagerConfig.BuilderBase<?, ?> builder) {
            if (builder.privateKeySource() == OciPrivateKeySource.CERTIFICATE_BUNDLE) {
                validateCertificateBundleOptions(builder);
                populateUnusedVaultOptions(builder);
            } else {
                validateVaultOptions(builder);
            }
        }

        private static void validateCertificateBundleOptions(OciCertificatesTlsManagerConfig.BuilderBase<?, ?> builder) {
            List<String> configuredOptions = new ArrayList<>();
            if (builder.vaultCryptoEndpoint()
                    .filter(endpoint -> !UNUSED_VAULT_CRYPTO_ENDPOINT.equals(endpoint))
                    .isPresent()) {
                configuredOptions.add("vault-crypto-endpoint");
            }
            if (builder.vaultManagementEndpoint().isPresent()) {
                configuredOptions.add("vault-management-endpoint");
            }
            if (builder.keyOcid()
                    .filter(keyOcid -> !UNUSED_KEY_OCID.equals(keyOcid))
                    .isPresent()) {
                configuredOptions.add("key-ocid");
            }
            if (hasConfiguredKeyPassword(builder)) {
                configuredOptions.add("key-password");
            }
            if (!configuredOptions.isEmpty()) {
                throw new IllegalArgumentException("private-key-source=CERTIFICATE_BUNDLE cannot be combined with "
                                                           + "Vault private-key options: "
                                                           + String.join(", ", configuredOptions));
            }
        }

        private static void populateUnusedVaultOptions(OciCertificatesTlsManagerConfig.BuilderBase<?, ?> builder) {
            builder.vaultCryptoEndpoint(UNUSED_VAULT_CRYPTO_ENDPOINT);
            builder.keyOcid(UNUSED_KEY_OCID);
            builder.keyPassword(MISSING_KEY_PASSWORD);
        }

        private static void validateVaultOptions(OciCertificatesTlsManagerConfig.BuilderBase<?, ?> builder) {
            List<String> missingOptions = new ArrayList<>();
            if (builder.vaultCryptoEndpoint()
                    .filter(endpoint -> !UNUSED_VAULT_CRYPTO_ENDPOINT.equals(endpoint))
                    .isEmpty()) {
                missingOptions.add("vault-crypto-endpoint");
            }
            if (builder.keyOcid()
                    .filter(keyOcid -> !UNUSED_KEY_OCID.equals(keyOcid))
                    .isEmpty()) {
                missingOptions.add("key-ocid");
            }
            if (!hasConfiguredKeyPassword(builder)) {
                missingOptions.add("key-password");
            }
            if (!missingOptions.isEmpty()) {
                throw new IllegalArgumentException("private-key-source=VAULT requires: "
                                                           + String.join(", ", missingOptions));
            }
        }

        private static boolean hasConfiguredKeyPassword(OciCertificatesTlsManagerConfig.BuilderBase<?, ?> builder) {
            if (builder.config()
                    .map(config -> config.get("key-password").exists())
                    .orElse(false)) {
                return true;
            }

            return builder.keyPassword()
                    .map(BuilderDecorator::isConfiguredKeyPassword)
                    .orElse(false);
        }

        private static boolean isConfiguredKeyPassword(Supplier<char[]> supplier) {
            try {
                supplier.get();
                return true;
            } catch (MissingValueException e) {
                return false;
            }
        }
    }
}
