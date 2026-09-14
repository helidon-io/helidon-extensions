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

import java.util.function.Consumer;

import io.helidon.builder.api.RuntimeType;
import io.helidon.common.tls.TlsManager;
import io.helidon.config.Config;

/**
 * The OCI Certificates contract of {@link io.helidon.common.tls.TlsManager}. The implementation should load/create
 * {@link io.helidon.common.tls.Tls} instances from integrating to the certificates stored remotely in OCI's
 * Certificates Service, and then allow for a scheduled update check of the Tls instance for changes.
 * <p>
 * Configuration is fixed when the manager is created. TLS material is refreshed from OCI on its configured schedule;
 * external calls to {@link #reload(io.helidon.common.tls.Tls)} or
 * {@link #reload(io.helidon.common.tls.TlsMaterial)} throw {@link java.lang.UnsupportedOperationException}.
 * <p>
 * New TLS engines and sockets use the latest complete identity and trust material with fresh session caches after rotation.
 * Existing connections retain their TLS state. Listening {@link javax.net.ssl.SSLServerSocket} instances use the latest
 * material for each newly accepted connection; the Helidon WebServer creates engines per connection.
 */
public interface OciCertificatesTlsManager extends TlsManager, RuntimeType.Api<OciCertificatesTlsManagerConfig> {

    /**
     * Creates a default {@link OciCertificatesTlsManager} instance.
     *
     * @return a default instance
     */
    static OciCertificatesTlsManager create() {
        return builder().build();
    }

    /**
     * Creates a configured {@link OciCertificatesTlsManager} instance.
     *
     * @param config the config
     * @return a configured instance
     */
    static OciCertificatesTlsManager create(Config config) {
        return builder().config(config).build();
    }

    /**
     * Creates a configured {@link OciCertificatesTlsManager} instance.
     *
     * @param cfg the config
     * @return a configured instance
     */
    static OciCertificatesTlsManager create(OciCertificatesTlsManagerConfig cfg) {
        return new DefaultOciCertificatesTlsManager(cfg);
    }

    /**
     * Creates a {@link OciCertificatesTlsManager} builder instance.
     *
     * @return a builder instance
     */
    static OciCertificatesTlsManagerConfig.Builder builder() {
        return OciCertificatesTlsManagerConfig.builder();
    }

    /**
     * Creates a consumer based {@link OciCertificatesTlsManager} instance.
     *
     * @param consumer the consumer
     * @return a consumer based instance
     */
    static OciCertificatesTlsManager create(Consumer<OciCertificatesTlsManagerConfig.Builder> consumer) {
        var builder = OciCertificatesTlsManagerConfig.builder();
        consumer.accept(builder);
        return builder.build();
    }

}
