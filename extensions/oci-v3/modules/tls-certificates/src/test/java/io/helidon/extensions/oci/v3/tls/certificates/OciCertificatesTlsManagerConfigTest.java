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
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.config.MissingValueException;

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
        assertThat(config.vaultCryptoEndpoint(), is(VAULT_ENDPOINT));
        assertThat(config.keyOcid(), is("vault-key"));
        assertThat(new String(config.keyPassword().get()), is("password"));
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
        OciCertificatesTlsManagerConfig config = new LegacyConfigImplementation();

        assertThat(config.privateKeySource(), is(OciPrivateKeySource.VAULT));
        assertThat(config.alwaysReload(), is(Optional.empty()));
    }

    @Test
    void generatedMetadataDescribesAlwaysReloadAsOptional() throws IOException {
        try (InputStream input = OciCertificatesTlsManagerConfig.class.getResourceAsStream(
                "/META-INF/helidon/config-metadata.json")) {
            assertNotNull(input);
            String metadata = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            Matcher option = Pattern.compile("\\{\\\"key\\\":\\\"always-reload\\\"[^}]*}").matcher(metadata);

            assertThat(option.find(), is(true));
            assertThat(option.group(), containsString("\"type\":\"java.lang.Boolean\""));
            assertThat(option.group(), not(containsString("\"required\":true")));
            assertThat(option.group(), not(containsString("\"defaultValue\"")));
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
    }

    @Test
    void managedLegacyPasswordIsMissingAndConfigRemainsCopyable() {
        OciCertificatesTlsManagerConfig managed = baseBuilder()
                .privateKeySource(OciPrivateKeySource.CERTIFICATE_BUNDLE)
                .buildPrototype();

        MissingValueException missing = assertThrows(MissingValueException.class,
                                                      () -> managed.keyPassword().get());
        assertThat(missing.getMessage(), containsString("key-password"));

        OciCertificatesTlsManagerConfig copied = OciCertificatesTlsManagerConfig.builder(managed).buildPrototype();

        assertThat(copied.privateKeySource(), is(OciPrivateKeySource.CERTIFICATE_BUNDLE));
        assertThrows(MissingValueException.class, () -> copied.keyPassword().get());
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
        assertThat(vault.vaultCryptoEndpoint(), is(VAULT_ENDPOINT));
        assertThat(vault.keyOcid(), is("vault-key"));
        assertThat(new String(vault.keyPassword().get()), is("password"));
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

    private static final class LegacyConfigImplementation implements OciCertificatesTlsManagerConfig {
        @Override
        public String schedule() {
            return "0 0 * * * ?";
        }

        @Override
        public URI vaultCryptoEndpoint() {
            return VAULT_ENDPOINT;
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
        public String keyOcid() {
            return "vault-key";
        }

        @Override
        public Supplier<char[]> keyPassword() {
            return () -> "password".toCharArray();
        }

        @Override
        public OciCertificatesTlsManager build() {
            throw new UnsupportedOperationException();
        }
    }
}
