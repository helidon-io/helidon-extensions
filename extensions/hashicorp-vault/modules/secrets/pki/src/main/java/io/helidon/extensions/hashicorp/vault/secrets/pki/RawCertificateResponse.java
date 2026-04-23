/*
 * Copyright (c) 2021, 2026 Oracle and/or its affiliates.
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

package io.helidon.extensions.hashicorp.vault.secrets.pki;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import io.helidon.extensions.hashicorp.vault.VaultResponse;

import io.helidon.json.JsonObject;

/**
 * Bytes of certificates as obtained from the server.
 */
abstract class RawCertificateResponse extends VaultResponse {
    private final PkiFormat format;
    private final String privateKeyType;
    private final String serialNumber;
    private final Instant expiration;
    private final byte[] certificate;
    private final byte[] issuingCa;
    private final byte[] privateKey;

    protected RawCertificateResponse(PkiFormat format,
                                     Builder<?, ? extends VaultResponse, JsonObject> builder) {
        super(builder);

        this.format = format;

        JsonObject data = builder.entity().objectValue("data").orElseThrow();

        this.expiration = Instant.ofEpochSecond(data.numberValue("expiration").orElseThrow().longValue());
        this.serialNumber = data.stringValue("serial_number").orElseThrow();
        this.privateKeyType = data.stringValue("private_key_type").orElseThrow();

        String certificate = data.stringValue("certificate").orElseThrow();
        String privateKey = data.stringValue("private_key").orElseThrow();
        String issuingCa = data.stringValue("issuing_ca").orElseThrow();

        if (format == PkiFormat.DER) {
            // DER is base64 encoded
            Base64.Decoder decoder = Base64.getDecoder();
            this.certificate = decoder.decode(certificate);
            this.issuingCa = decoder.decode(issuingCa);
            this.privateKey = decoder.decode(privateKey);
        } else {
            // this will work for PEM_BUNDLE and PEM
            this.certificate = certificate.getBytes(StandardCharsets.UTF_8);
            this.issuingCa = issuingCa.getBytes(StandardCharsets.UTF_8);
            this.privateKey = privateKey.getBytes(StandardCharsets.UTF_8);
        }
    }

    /**
     * Format of the bytes.
     *
     * @return format
     */
    public PkiFormat format() {
        return format;
    }

    /**
     * Type of the private key (such as {@code rsa}.
     *
     * @return private key type
     */
    public String privateKeyType() {
        return privateKeyType;
    }

    /**
     * Serial number of the certificate.
     *
     * @return serial number
     */
    public String serialNumber() {
        return serialNumber;
    }

    /**
     * Certificate bytes, when {@link #format()} is set to {@link PkiFormat#PEM_BUNDLE}, it contains
     * private key as well.
     *
     * @return certificate bytes
     */
    public byte[] certificate() {
        return certificate;
    }

    /**
     * Issuing certification authority certificate bytes.
     *
     * @return certificate bytes of issuing certification authority
     */
    public byte[] issuingCa() {
        return issuingCa;
    }

    /**
     * Private key bytes.
     *
     * @return private key bytes.
     */
    public byte[] privateKey() {
        return privateKey;
    }

    /**
     * Certificate expiration instant.
     *
     * @return instant of expiration
     */
    public Instant expiration() {
        return expiration;
    }
}
