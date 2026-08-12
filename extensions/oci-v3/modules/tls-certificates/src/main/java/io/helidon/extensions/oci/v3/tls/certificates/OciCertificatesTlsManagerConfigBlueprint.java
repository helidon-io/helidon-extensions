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

import java.net.URI;
import java.util.Optional;
import java.util.function.Supplier;

import io.helidon.builder.api.Option;
import io.helidon.builder.api.Prototype;

/**
 * Blueprint configuration for {@link OciCertificatesTlsManager}.
 */
@Prototype.Blueprint(decorator = OciCertificatesTlsManagerConfigSupport.BuilderDecorator.class,
                     createEmptyPublic = false)
@Prototype.Configured
@Prototype.CustomMethods(OciCertificatesTlsManagerConfigSupport.CustomMethods.class)
@Prototype.IncludeDefaultMethods({"privateKeySource", "alwaysReload"})
interface OciCertificatesTlsManagerConfigBlueprint extends Prototype.Factory<OciCertificatesTlsManager> {

    /**
     * The schedule for trigger a reload check, testing whether there is a new {@link io.helidon.common.tls.Tls} instance
     * available.
     *
     * @return the schedule for reload
     */
    @Option.Configured
    String schedule();

    /**
     * Source of the private key used with the certificate.
     *
     * @return private key source
     */
    @Option.Configured
    @Option.Default("VAULT")
    default OciPrivateKeySource privateKeySource() {
        return OciPrivateKeySource.VAULT;
    }

    /**
     * Whether to reload the TLS identity even when the downloaded certificate version and CA certificate have not changed.
     * The CA certificate is retrieved on every scheduled poll. When this option is {@code false}, rebuilding is skipped
     * only when both the identity certificate version and the CA certificate are unchanged. When not configured, Vault
     * mode preserves its existing always-reload behavior, while certificate-bundle mode uses this change detection.
     *
     * @return whether unchanged certificate material should still be reloaded, if explicitly configured
     */
    @Option.Configured
    default Optional<Boolean> alwaysReload() {
        return Optional.empty();
    }

    /**
     * The address to use for the OCI Key Management Service / Vault crypto usage.
     * Each OCI Vault has public crypto and management endpoints. We need to specify the crypto endpoint of the vault we are
     * rotating the private keys in. The implementation expects both client and server to store the private key in the same vault.
     * This option is required when {@link #privateKeySource()} is {@link OciPrivateKeySource#VAULT} and must be omitted for
     * {@link OciPrivateKeySource#CERTIFICATE_BUNDLE}.
     *
     * @return the address for the key management service / vault crypto usage
     */
    @Option.Configured
    Optional<URI> vaultCryptoEndpoint();

    /**
     * The address to use for the OCI Key Management Service / Vault management usage.
     * The crypto endpoint of the vault we are rotating the private keys in.
     * This option must be omitted when {@link #privateKeySource()} is
     * {@link OciPrivateKeySource#CERTIFICATE_BUNDLE}.
     *
     * @return the address for the key management service / vault management usage
     */
    @Option.Configured
    Optional<URI> vaultManagementEndpoint();

    /**
     * The OCID of the compartment the services are in.
     *
     * @return the compartment OCID
     */
    @Option.Configured
    Optional<String> compartmentOcid();

    /**
     * The Certificate Authority OCID.
     *
     * @return certificate authority OCID
     */
    @Option.Configured
    String caOcid();

    /**
     * The Certificate OCID.
     *
     * @return certificate OCID
     */
    @Option.Configured
    String certOcid();

    /**
     * The Key OCID. This option is required when {@link #privateKeySource()} is {@link OciPrivateKeySource#VAULT} and must
     * be omitted for {@link OciPrivateKeySource#CERTIFICATE_BUNDLE}.
     *
     * @return key OCID
     */
    @Option.Configured
    Optional<String> keyOcid();

    /**
     * The Key password. This option is required when {@link #privateKeySource()} is {@link OciPrivateKeySource#VAULT} and
     * must be omitted for {@link OciPrivateKeySource#CERTIFICATE_BUNDLE}; OCI bundle passphrases are supplied by OCI and do
     * not use this value.
     *
     * @return key password
     */
    @Option.Configured
    @Option.Confidential
    Optional<Supplier<char[]>> keyPassword();

}
