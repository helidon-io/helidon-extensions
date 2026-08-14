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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import io.helidon.builder.api.Prototype;
import io.helidon.config.Config;

/**
 * Support for the OCI certificates TLS manager configuration prototype.
 */
final class OciCertificatesTlsManagerConfigSupport {
    private OciCertificatesTlsManagerConfigSupport() {
    }

    static final class CustomMethods {
        private CustomMethods() {
        }

        /**
         * Sets the Vault key password from a string.
         *
         * @param builder builder to update
         * @param keyPassword key password
         */
        @Prototype.BuilderMethod
        static void keyPassword(OciCertificatesTlsManagerConfig.BuilderBase<?, ?> builder, String keyPassword) {
            char[] password = keyPassword.toCharArray();
            builder.keyPassword(() -> password);
        }

        /**
         * Sets the Vault key password from a character array.
         *
         * @param builder builder to update
         * @param keyPassword key password
         */
        @Prototype.BuilderMethod
        static void keyPassword(OciCertificatesTlsManagerConfig.BuilderBase<?, ?> builder, char[] keyPassword) {
            Objects.requireNonNull(keyPassword);
            builder.keyPassword(() -> keyPassword);
        }

        /**
         * Creates a lazy Vault key password supplier from configuration.
         *
         * @param config key password configuration
         * @return lazy key password supplier
         */
        @Prototype.ConfigFactoryMethod("keyPassword")
        static Supplier<char[]> createKeyPassword(Config config) {
            return config.asString().as(String::toCharArray).supplier();
        }

    }

    static final class BuilderDecorator
            implements Prototype.BuilderDecorator<OciCertificatesTlsManagerConfig.BuilderBase<?, ?>> {

        BuilderDecorator() {
        }

        @Override
        public void decorate(OciCertificatesTlsManagerConfig.BuilderBase<?, ?> builder) {
            if (builder.privateKeySource() == OciPrivateKeySource.CERTIFICATE_BUNDLE) {
                validateCertificateBundleOptions(builder);
            } else {
                validateVaultOptions(builder);
            }
        }

        private static void validateCertificateBundleOptions(OciCertificatesTlsManagerConfig.BuilderBase<?, ?> builder) {
            List<String> configuredOptions = new ArrayList<>();
            if (builder.vaultCryptoEndpoint().isPresent()) {
                configuredOptions.add("vault-crypto-endpoint");
            }
            if (builder.vaultManagementEndpoint().isPresent()) {
                configuredOptions.add("vault-management-endpoint");
            }
            if (builder.keyOcid().isPresent()) {
                configuredOptions.add("key-ocid");
            }
            if (builder.keyPassword().isPresent()) {
                configuredOptions.add("key-password");
            }
            if (!configuredOptions.isEmpty()) {
                throw new IllegalArgumentException("private-key-source=CERTIFICATE_BUNDLE cannot be combined with "
                                                           + "Vault private-key options: "
                                                           + String.join(", ", configuredOptions));
            }
        }

        private static void validateVaultOptions(OciCertificatesTlsManagerConfig.BuilderBase<?, ?> builder) {
            List<String> missingOptions = new ArrayList<>();
            if (builder.vaultCryptoEndpoint().isEmpty()) {
                missingOptions.add("vault-crypto-endpoint");
            }
            if (builder.keyOcid().isEmpty()) {
                missingOptions.add("key-ocid");
            }
            if (builder.keyPassword().isEmpty()) {
                missingOptions.add("key-password");
            }
            if (!missingOptions.isEmpty()) {
                throw new IllegalArgumentException("private-key-source=VAULT requires: "
                                                           + String.join(", ", missingOptions));
            }
        }
    }
}
