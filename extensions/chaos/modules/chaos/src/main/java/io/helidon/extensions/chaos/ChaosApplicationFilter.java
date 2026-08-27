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
package io.helidon.extensions.chaos;

import java.util.Optional;

import io.helidon.http.HeaderNames;
import io.helidon.webserver.http.Filter;
import io.helidon.webserver.http.FilterChain;
import io.helidon.webserver.http.RoutingRequest;
import io.helidon.webserver.http.RoutingResponse;


/**
 * Applies accepted disruption reservations before application routing.
 */
final class ChaosApplicationFilter implements Filter {
    private final ChaosRunEngine engine;

    ChaosApplicationFilter(ChaosRunEngine engine) {
        this.engine = engine;
    }

    @Override
    public void filter(FilterChain chain, RoutingRequest request, RoutingResponse response) {
        String method = request.prologue().method().text();
        String path = request.path().absolute().path();
        Optional<ChaosRunEngine.Reservation> candidate = engine.reserve(method, path);
        if (candidate.isEmpty()) {
            chain.proceed();
            return;
        }

        try (ChaosRunEngine.Reservation reservation = candidate.orElseThrow()) {
            ChaosSyntheticResponse effect = (ChaosSyntheticResponse) reservation.effect();
            byte[] body = effect.body();
            response.status(effect.status());
            effect.headers().forEach(response::header);
            effect.mediaType().ifPresent(mediaType -> response.header(HeaderNames.CONTENT_TYPE, mediaType.text()));
            response.contentLength(body.length);
            if (body.length == 0) {
                response.send();
            } else {
                response.send(body);
            }
        }
    }

    @Override
    public void afterStop() {
        engine.close();
    }
}
