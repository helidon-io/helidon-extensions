/*
 * Copyright (c) 2021, 2025 Oracle and/or its affiliates.
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

package io.helidon.extensions.hashicorp.vault.examples.declarative;

import java.util.List;

import io.helidon.common.Base64Value;
import io.helidon.extensions.hashicorp.vault.secrets.transit.CreateKey;
import io.helidon.extensions.hashicorp.vault.secrets.transit.Decrypt;
import io.helidon.extensions.hashicorp.vault.secrets.transit.DecryptBatch;
import io.helidon.extensions.hashicorp.vault.secrets.transit.DeleteKey;
import io.helidon.extensions.hashicorp.vault.secrets.transit.Encrypt;
import io.helidon.extensions.hashicorp.vault.secrets.transit.EncryptBatch;
import io.helidon.extensions.hashicorp.vault.secrets.transit.Hmac;
import io.helidon.extensions.hashicorp.vault.secrets.transit.Sign;
import io.helidon.extensions.hashicorp.vault.secrets.transit.TransitSecrets;
import io.helidon.extensions.hashicorp.vault.secrets.transit.UpdateKeyConfig;
import io.helidon.extensions.hashicorp.vault.secrets.transit.Verify;
import io.helidon.extensions.hashicorp.vault.sys.Sys;
import io.helidon.http.Http;
import io.helidon.service.registry.Service;
import io.helidon.webserver.http.RestServer;

@SuppressWarnings("deprecation")
@Service.Singleton
@RestServer.Endpoint
@Http.Path("transit")
class TransitEndpoint {
    private static final String ENCRYPTION_KEY = "encryption-key";
    private static final String SIGNATURE_KEY = "signature-key";
    private static final Base64Value SECRET_STRING = Base64Value.create("Hello World");

    private final Sys sys;
    private final TransitSecrets secrets;

    @Service.Inject
    TransitEndpoint(Sys sys, TransitSecrets secrets) {
        this.sys = sys;
        this.secrets = secrets;
    }

    @Http.GET
    @Http.Path("/enable")
    String enableEngine() {
        sys.enableEngine(TransitSecrets.ENGINE);
        return "Transit Secret engine enabled";
    }

    @Http.GET
    @Http.Path("/disable")
    String disableEngine() {
        sys.disableEngine(TransitSecrets.ENGINE);
        return "Transit Secret engine disabled";
    }

    @Http.GET
    @Http.Path("/keys")
    String createKeys() {
        CreateKey.Request request = CreateKey.Request.builder()
                .name(ENCRYPTION_KEY);

        secrets.createKey(request);
        secrets.createKey(CreateKey.Request.builder()
                                  .name(SIGNATURE_KEY)
                                  .type("rsa-2048"));

        return "Created keys";
    }

    @Http.DELETE
    @Http.Path("/keys")
    String deleteKeys() {
        secrets.updateKeyConfig(UpdateKeyConfig.Request.builder()
                                        .name(ENCRYPTION_KEY)
                                        .allowDeletion(true));
        System.out.println("Updated key config");

        secrets.deleteKey(DeleteKey.Request.create(ENCRYPTION_KEY));

        return "Deleted key.";
    }

    @Http.GET
    @Http.Path("/decrypt/{text:.*}")
    String decryptSecret(@Http.PathParam("text") String encrypted) {
        Decrypt.Response decryptResponse = secrets.decrypt(Decrypt.Request.builder()
                                                                   .encryptionKeyName(ENCRYPTION_KEY)
                                                                   .cipherText(encrypted));

        return String.valueOf(decryptResponse.decrypted().toDecodedString());
    }

    @Http.GET
    @Http.Path("/encrypt/{text:.*}")
    String encryptSecret(@Http.PathParam("text") String secret) {
        Encrypt.Response encryptResponse = secrets.encrypt(Encrypt.Request.builder()
                                                                   .encryptionKeyName(ENCRYPTION_KEY)
                                                                   .data(Base64Value.create(secret)));

        return encryptResponse.encrypted().cipherText();
    }

    @Http.GET
    @Http.Path("/hmac")
    String hmac() {
        Hmac.Response hmacResponse = secrets.hmac(Hmac.Request.builder()
                                                          .hmacKeyName(ENCRYPTION_KEY)
                                                          .data(SECRET_STRING));

        return hmacResponse.hmac();
    }

    @Http.GET
    @Http.Path("/sign")
    String sign() {
        Sign.Response signResponse = secrets.sign(Sign.Request.builder()
                                                          .signatureKeyName(SIGNATURE_KEY)
                                                          .data(SECRET_STRING));

        return signResponse.signature();
    }

    @Http.GET
    @Http.Path("/verify/hmac/{text:.*}")
    String verifyHmac(@Http.PathParam("text") String hmac) {
        Verify.Response verifyResponse = secrets.verify(Verify.Request.builder()
                                                                .digestKeyName(ENCRYPTION_KEY)
                                                                .data(SECRET_STRING)
                                                                .hmac(hmac));

        return "Valid: " + verifyResponse.isValid();
    }

    @Http.GET
    @Http.Path("/verify/sign/{text:.*}")
    String verify(@Http.PathParam("text") String signature) {
        Verify.Response verifyResponse = secrets.verify(Verify.Request.builder()
                                                                .digestKeyName(SIGNATURE_KEY)
                                                                .data(SECRET_STRING)
                                                                .signature(signature));

        return "Valid: " + verifyResponse.isValid();
    }

    @Http.GET
    @Http.Path("/batch")
    String batch() {
        String[] data = new String[] {"one", "two", "three", "four"};
        EncryptBatch.Request request = EncryptBatch.Request.builder()
                .encryptionKeyName(ENCRYPTION_KEY);
        DecryptBatch.Request decryptRequest = DecryptBatch.Request.builder()
                .encryptionKeyName(ENCRYPTION_KEY);

        for (String item : data) {
            request.addEntry(EncryptBatch.BatchEntry.create(Base64Value.create(item)));
        }
        List<Encrypt.Encrypted> batchResult = secrets.encrypt(request).batchResult();
        for (Encrypt.Encrypted encrypted : batchResult) {
            System.out.println("Encrypted: " + encrypted.cipherText());
            decryptRequest.addEntry(DecryptBatch.BatchEntry.create(encrypted.cipherText()));
        }

        List<Base64Value> base64Values = secrets.decrypt(decryptRequest).batchResult();
        for (int i = 0; i < data.length; i++) {
            String decryptedValue = base64Values.get(i).toDecodedString();
            if (!data[i].equals(decryptedValue)) {
                return "Data at index " + i + " is invalid. Decrypted " + decryptedValue
                        + ", expected: " + data[i];
            }
        }
        return "Batch encryption/decryption completed";
    }
}
