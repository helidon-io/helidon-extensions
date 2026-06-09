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

package io.helidon.extensions.oci.v3.examples.imperative;

import io.helidon.http.Status;
import io.helidon.webclient.http1.Http1Client;
import io.helidon.webserver.WebServerConfig;
import io.helidon.webserver.testing.junit5.ServerTest;
import io.helidon.webserver.testing.junit5.SetUpServer;

import com.oracle.bmc.model.BmcException;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

@ServerTest
class MainTest {
    private static final String NAMESPACE = "example-namespace";
    private static final TestObjectStorage OBJECT_STORAGE = new TestObjectStorage(NAMESPACE);

    private final Http1Client client;

    MainTest(Http1Client client) {
        this.client = client;
    }

    @SetUpServer
    static void server(WebServerConfig.Builder server) {
        server.routing(routing -> Main.routing(routing, OBJECT_STORAGE.client()));
    }

    @Test
    void testNamespace() {
        var response = client.get("/objectstorage/namespace")
                .request(String.class);

        assertThat(response.status(), is(Status.OK_200));
        assertThat(response.entity(), is(NAMESPACE));
    }

    @Test
    void testObjectStorageClosedAfterStop() {
        TestObjectStorage objectStorage = new TestObjectStorage(NAMESPACE);

        new ObjectStorageService(objectStorage.client()).afterStop();

        assertThat(objectStorage.closed(), is(true));
    }

    @Test
    void testStatusForServiceException() {
        assertThat(Main.status(new BmcException(404, "NotFound", "missing", "request-id")), is(Status.NOT_FOUND_404));
    }

    @Test
    void testStatusForClientSideException() {
        BmcException exception = BmcException.createClientSide("client failed",
                                                              new IllegalStateException("client failed"),
                                                              null,
                                                              null);

        assertThat(Main.status(exception), is(Status.BAD_GATEWAY_502));
    }

    @Test
    void testStatusForTimeoutException() {
        BmcException exception = new BmcException(true,
                                                 "timeout",
                                                 new IllegalStateException("timeout"),
                                                 null);

        assertThat(Main.status(exception), is(Status.GATEWAY_TIMEOUT_504));
    }
}
