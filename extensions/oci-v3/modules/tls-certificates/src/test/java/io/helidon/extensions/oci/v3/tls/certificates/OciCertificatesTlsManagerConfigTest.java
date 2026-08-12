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

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.helidon.config.Config;
import io.helidon.config.ConfigSources;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OciCertificatesTlsManagerConfigTest {
    private static final URI VAULT_ENDPOINT = URI.create("https://vault.example.test");

    @Test
    void existingVaultBuilderApiRemainsCompatible() {
        OciCertificatesTlsManagerConfig.Builder builder = baseBuilder()
                .vaultCryptoEndpoint(VAULT_ENDPOINT)
                .keyOcid("vault-key")
                .keyPassword("password");

        Optional<URI> endpoint = builder.vaultCryptoEndpoint();
        Optional<String> keyOcid = builder.keyOcid();
        Optional<Supplier<char[]>> keyPassword = builder.keyPassword();
        OciCertificatesTlsManagerConfig config = builder.buildPrototype();

        assertThat(endpoint, is(Optional.of(VAULT_ENDPOINT)));
        assertThat(keyOcid, is(Optional.of("vault-key")));
        assertThat(keyPassword.isPresent(), is(true));
        assertThat(config.privateKeySource(), is(OciPrivateKeySource.VAULT));
        assertThat(config.vaultCryptoEndpoint(), is(Optional.of(VAULT_ENDPOINT)));
        assertThat(config.keyOcid(), is(Optional.of("vault-key")));
        assertThat(new String(config.keyPassword().orElseThrow().get()), is("password"));
    }

    @Test
    void generatedBuilderRetainsVaultOptionSetters() throws NoSuchMethodException {
        Class<?> builderType = OciCertificatesTlsManagerConfig.BuilderBase.class;

        assertNotNull(builderType.getMethod("vaultCryptoEndpoint", URI.class));
        assertNotNull(builderType.getMethod("keyOcid", String.class));
        assertNotNull(builderType.getMethod("keyPassword", Supplier.class));
        assertNotNull(builderType.getMethod("keyPassword", String.class));
        assertNotNull(builderType.getMethod("keyPassword", char[].class));
    }

    @Test
    void alwaysReloadIsOptionalAndConfigurable() {
        OciCertificatesTlsManagerConfig.Builder builder = baseBuilder();

        assertThat(builder.alwaysReload(), is(Optional.empty()));
        assertThat(builder.alwaysReload(true).alwaysReload(), is(Optional.of(true)));
        assertThat(builder.clearAlwaysReload().alwaysReload(), is(Optional.empty()));

        Config config = Config.just(ConfigSources.create(Map.of("always-reload", "false")));
        builder.config(config);
        assertThat(builder.alwaysReload(), is(Optional.of(false)));
    }

    @Test
    void thirdPartyConfigImplementationInheritsOptionalDefaults() {
        OciCertificatesTlsManagerConfig config = new ThirdPartyConfigImplementation();

        assertThat(config.privateKeySource(), is(OciPrivateKeySource.VAULT));
        assertThat(config.alwaysReload(), is(Optional.empty()));
    }

    @Test
    void generatedMetadataDescribesModeDependentOptionsAsOptional() throws IOException {
        try (InputStream input = OciCertificatesTlsManagerConfig.class.getResourceAsStream(
                "/META-INF/helidon/config-metadata.json")) {
            assertNotNull(input);
            String metadata = new String(input.readAllBytes(), StandardCharsets.UTF_8);

            String alwaysReload = metadataOption(metadata, "always-reload");
            assertThat(alwaysReload, containsString("\"type\":\"java.lang.Boolean\""));
            assertThat(alwaysReload, not(containsString("\"required\":true")));
            assertThat(alwaysReload, not(containsString("\"defaultValue\"")));

            assertThat(metadataOption(metadata, "vault-crypto-endpoint"),
                       not(containsString("\"required\":true")));
            assertThat(metadataOption(metadata, "key-ocid"), not(containsString("\"required\":true")));
            // The code generator currently reports the configured factory's helper type for this option.
            String keyPassword = metadataOption(metadata, "key-password");
            assertThat(keyPassword, not(containsString("\"required\":true")));

            assertThat(metadataOption(metadata, "schedule"), containsString("\"required\":true"));
            assertThat(metadataOption(metadata, "ca-ocid"), containsString("\"required\":true"));
            assertThat(metadataOption(metadata, "cert-ocid"), containsString("\"required\":true"));
        }
    }

    @Test
    void unusableEmptyCreateFactoryIsNotGenerated() {
        assertThrows(NoSuchMethodException.class,
                     () -> OciCertificatesTlsManagerConfig.class.getDeclaredMethod("create"));
    }

    @Test
    void certificateBundleCanOmitVaultOptionsAndBeBuiltRepeatedly() {
        OciCertificatesTlsManagerConfig.Builder builder = baseBuilder()
                .privateKeySource(OciPrivateKeySource.CERTIFICATE_BUNDLE);

        OciCertificatesTlsManagerConfig first = builder.buildPrototype();
        OciCertificatesTlsManagerConfig second = builder.buildPrototype();
        OciCertificatesTlsManagerConfig copied = OciCertificatesTlsManagerConfig.builder(first).buildPrototype();
        OciCertificatesTlsManagerConfig copiedFromBuilder = OciCertificatesTlsManagerConfig.builder()
                .from(builder)
                .buildPrototype();

        assertThat(first.privateKeySource(), is(OciPrivateKeySource.CERTIFICATE_BUNDLE));
        assertThat(second.privateKeySource(), is(OciPrivateKeySource.CERTIFICATE_BUNDLE));
        assertThat(copied.privateKeySource(), is(OciPrivateKeySource.CERTIFICATE_BUNDLE));
        assertThat(copiedFromBuilder.privateKeySource(), is(OciPrivateKeySource.CERTIFICATE_BUNDLE));
        assertThat(copied.certOcid(), is("certificate"));
        assertThat(first.vaultCryptoEndpoint(), is(Optional.empty()));
        assertThat(first.keyOcid(), is(Optional.empty()));
        assertThat(first.keyPassword(), is(Optional.empty()));
        assertThat(first.toString(), not(containsString("urn:helidon:oci-certificates")));
        assertThat(first.toString(), not(containsString("unused-key-ocid")));
    }

    @Test
    void copiedCertificateBundleCanSwitchToVaultWithRealVaultOptions() {
        OciCertificatesTlsManagerConfig managed = baseBuilder()
                .privateKeySource(OciPrivateKeySource.CERTIFICATE_BUNDLE)
                .buildPrototype();
        OciCertificatesTlsManagerConfig.Builder vaultBuilder = OciCertificatesTlsManagerConfig.builder(managed)
                .privateKeySource(OciPrivateKeySource.VAULT);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                                                           vaultBuilder::buildPrototype);
        assertThat(exception.getMessage(), containsString("vault-crypto-endpoint"));
        assertThat(exception.getMessage(), containsString("key-ocid"));
        assertThat(exception.getMessage(), containsString("key-password"));

        OciCertificatesTlsManagerConfig vault = vaultBuilder
                .vaultCryptoEndpoint(VAULT_ENDPOINT)
                .keyOcid("vault-key")
                .keyPassword("password")
                .buildPrototype();

        assertThat(vault.privateKeySource(), is(OciPrivateKeySource.VAULT));
        assertThat(vault.vaultCryptoEndpoint(), is(Optional.of(VAULT_ENDPOINT)));
        assertThat(vault.keyOcid(), is(Optional.of("vault-key")));
        assertThat(new String(vault.keyPassword().orElseThrow().get()), is("password"));
    }

    @Test
    void copiedVaultCanSwitchToCertificateBundleAfterClearingVaultOptions() {
        OciCertificatesTlsManagerConfig vault = baseBuilder()
                .vaultCryptoEndpoint(VAULT_ENDPOINT)
                .vaultManagementEndpoint(VAULT_ENDPOINT)
                .keyOcid("vault-key")
                .keyPassword("password")
                .buildPrototype();

        OciCertificatesTlsManagerConfig certificateBundle = OciCertificatesTlsManagerConfig.builder(vault)
                .privateKeySource(OciPrivateKeySource.CERTIFICATE_BUNDLE)
                .clearVaultCryptoEndpoint()
                .clearVaultManagementEndpoint()
                .clearKeyOcid()
                .clearKeyPassword()
                .buildPrototype();

        assertThat(certificateBundle.privateKeySource(), is(OciPrivateKeySource.CERTIFICATE_BUNDLE));
        assertThat(certificateBundle.vaultCryptoEndpoint(), is(Optional.empty()));
        assertThat(certificateBundle.vaultManagementEndpoint(), is(Optional.empty()));
        assertThat(certificateBundle.keyOcid(), is(Optional.empty()));
        assertThat(certificateBundle.keyPassword(), is(Optional.empty()));
    }

    @Test
    void passwordSupplierIsNotConsumedByValidationOrCopying() {
        AtomicInteger invocations = new AtomicInteger();
        Supplier<char[]> password = () -> {
            invocations.incrementAndGet();
            return "password".toCharArray();
        };
        OciCertificatesTlsManagerConfig.Builder builder = baseBuilder()
                .vaultCryptoEndpoint(VAULT_ENDPOINT)
                .keyOcid("vault-key")
                .keyPassword(password);

        OciCertificatesTlsManagerConfig config = builder.buildPrototype();
        OciCertificatesTlsManagerConfig copied = OciCertificatesTlsManagerConfig.builder(config).buildPrototype();
        OciCertificatesTlsManagerConfig copiedFromBuilder = OciCertificatesTlsManagerConfig.builder()
                .from(builder)
                .buildPrototype();

        assertThat(invocations.get(), is(0));
        assertThat(new String(copied.keyPassword().orElseThrow().get()), is("password"));
        assertThat(invocations.get(), is(1));
        assertThat(copiedFromBuilder.keyPassword().isPresent(), is(true));
    }

    @Test
    void certificateBundleRejectsPasswordSupplierWithoutConsumingIt() {
        AtomicInteger invocations = new AtomicInteger();
        Supplier<char[]> password = () -> {
            invocations.incrementAndGet();
            return "password".toCharArray();
        };

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                                                           () -> baseBuilder()
                                                                   .privateKeySource(OciPrivateKeySource.CERTIFICATE_BUNDLE)
                                                                   .keyPassword(password)
                                                                   .buildPrototype());

        assertThat(exception.getMessage(), containsString("key-password"));
        assertThat(invocations.get(), is(0));
    }

    @Test
    void certificateBundleCanBeCreatedFromConfigWithoutVaultOptions() {
        Config config = Config.just(ConfigSources.create(Map.of("schedule", "0 0 * * * ?",
                                                                "private-key-source", "certificate-bundle",
                                                                "ca-ocid", "certificate-authority",
                                                                "cert-ocid", "certificate")));

        OciCertificatesTlsManagerConfig result = OciCertificatesTlsManagerConfig.create(config);

        assertThat(result.privateKeySource(), is(OciPrivateKeySource.CERTIFICATE_BUNDLE));
        assertThat(result.certOcid(), is("certificate"));
        assertThat(result.vaultCryptoEndpoint(), is(Optional.empty()));
        assertThat(result.keyOcid(), is(Optional.empty()));
        assertThat(result.keyPassword(), is(Optional.empty()));
    }

    @Test
    void vaultPasswordCanBeCreatedFromConfigAsLazySupplier() {
        Config config = Config.just(ConfigSources.create(Map.of("schedule", "0 0 * * * ?",
                                                                "vault-crypto-endpoint", VAULT_ENDPOINT.toString(),
                                                                "ca-ocid", "certificate-authority",
                                                                "cert-ocid", "certificate",
                                                                "key-ocid", "vault-key",
                                                                "key-password", "password")));

        OciCertificatesTlsManagerConfig result = OciCertificatesTlsManagerConfig.create(config);

        Supplier<char[]> password = result.keyPassword().orElseThrow();
        assertThat(new String(password.get()), is("password"));
    }

    @Test
    void configuredPasswordOverridesExistingBuilderValueAndCanBeUpdated() {
        OciCertificatesTlsManagerConfig.Builder builder = baseBuilder()
                .vaultCryptoEndpoint(VAULT_ENDPOINT)
                .keyOcid("vault-key")
                .keyPassword("programmatic");

        builder.config(passwordConfig("first-configured"));
        assertThat(new String(builder.keyPassword().orElseThrow().get()), is("first-configured"));

        OciCertificatesTlsManagerConfig result = builder
                .config(passwordConfig("second-configured"))
                .buildPrototype();

        assertThat(new String(result.keyPassword().orElseThrow().get()), is("second-configured"));
    }

    @Test
    void configuredPasswordCanBeClearedBeforeSwitchingToCertificateBundle() {
        OciCertificatesTlsManagerConfig result = baseBuilder()
                .vaultCryptoEndpoint(VAULT_ENDPOINT)
                .keyOcid("vault-key")
                .config(passwordConfig("configured"))
                .clearKeyPassword()
                .clearKeyOcid()
                .clearVaultCryptoEndpoint()
                .privateKeySource(OciPrivateKeySource.CERTIFICATE_BUNDLE)
                .buildPrototype();

        assertThat(result.privateKeySource(), is(OciPrivateKeySource.CERTIFICATE_BUNDLE));
        assertThat(result.keyPassword(), is(Optional.empty()));
    }

    @Test
    void passwordIsRedactedFromConfigToString() {
        OciCertificatesTlsManagerConfig result = baseBuilder()
                .vaultCryptoEndpoint(VAULT_ENDPOINT)
                .keyOcid("vault-key")
                .keyPassword("secret-password")
                .buildPrototype();

        assertThat(result.toString(), containsString("keyPassword=****"));
        assertThat(result.toString(), not(containsString("secret-password")));
    }

    @Test
    void certificateBundleRejectsVaultOptions() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                                                           () -> baseBuilder()
                                                                   .privateKeySource(OciPrivateKeySource.CERTIFICATE_BUNDLE)
                                                                   .vaultCryptoEndpoint(VAULT_ENDPOINT)
                                                                   .vaultManagementEndpoint(VAULT_ENDPOINT)
                                                                   .keyOcid("vault-key")
                                                                   .keyPassword("password")
                                                                   .buildPrototype());

        assertThat(exception.getMessage(), containsString("private-key-source=CERTIFICATE_BUNDLE"));
        assertThat(exception.getMessage(), containsString("vault-crypto-endpoint"));
        assertThat(exception.getMessage(), containsString("vault-management-endpoint"));
        assertThat(exception.getMessage(), containsString("key-ocid"));
        assertThat(exception.getMessage(), containsString("key-password"));
    }

    @Test
    void vaultModeRequiresVaultOptions() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                                                           () -> baseBuilder().buildPrototype());

        assertThat(exception.getMessage(), containsString("private-key-source=VAULT"));
        assertThat(exception.getMessage(), containsString("vault-crypto-endpoint"));
        assertThat(exception.getMessage(), containsString("key-ocid"));
        assertThat(exception.getMessage(), containsString("key-password"));
    }

    private static OciCertificatesTlsManagerConfig.Builder baseBuilder() {
        return OciCertificatesTlsManagerConfig.builder()
                .schedule("0 0 * * * ?")
                .caOcid("certificate-authority")
                .certOcid("certificate");
    }

    private static Config passwordConfig(String password) {
        return Config.just(ConfigSources.create(Map.of("key-password", password)));
    }

    private static String metadataOption(String metadata, String key) {
        Matcher option = Pattern.compile("\\{\\\"key\\\":\\\"" + Pattern.quote(key) + "\\\"[^}]*}").matcher(metadata);
        assertThat("metadata option " + key, option.find(), is(true));
        return option.group();
    }

    private static final class ThirdPartyConfigImplementation implements OciCertificatesTlsManagerConfig {
        @Override
        public String schedule() {
            return "0 0 * * * ?";
        }

        @Override
        public Optional<URI> vaultCryptoEndpoint() {
            return Optional.of(VAULT_ENDPOINT);
        }

        @Override
        public Optional<URI> vaultManagementEndpoint() {
            return Optional.empty();
        }

        @Override
        public Optional<String> compartmentOcid() {
            return Optional.empty();
        }

        @Override
        public String caOcid() {
            return "certificate-authority";
        }

        @Override
        public String certOcid() {
            return "certificate";
        }

        @Override
        public Optional<String> keyOcid() {
            return Optional.of("vault-key");
        }

        @Override
        public Optional<Supplier<char[]>> keyPassword() {
            return Optional.of(() -> "password".toCharArray());
        }

        @Override
        public OciCertificatesTlsManager build() {
            throw new UnsupportedOperationException();
        }
    }
}
