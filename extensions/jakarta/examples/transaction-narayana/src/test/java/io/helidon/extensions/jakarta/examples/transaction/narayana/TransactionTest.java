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

package io.helidon.extensions.jakarta.examples.transaction.narayana;

import java.util.ArrayList;
import java.util.List;

import io.helidon.common.Api;
import io.helidon.service.registry.Services;
import io.helidon.transaction.Tx;
import io.helidon.transaction.TxException;
import io.helidon.transaction.spi.TxSupport;

import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.TransactionSynchronizationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SuppressWarnings(Api.SUPPRESS_INCUBATING)
class TransactionTest {
    private final TransactionManager manager = Services.get(TransactionManager.class);
    private final TransactionSynchronizationRegistry registry = Services.get(TransactionSynchronizationRegistry.class);

    @AfterEach
    void cleanUpTransaction() throws Exception {
        if (manager.getTransaction() != null) {
            manager.rollback();
        }
    }

    @Test
    void discoversJtaAndCommits() throws Exception {
        assertThat(Services.get(TxSupport.class).type(), is("jta"));
        var completion = new Completion();

        var transaction = Tx.transaction(() -> {
            registry.registerInterposedSynchronization(completion);
            assertThat(manager.getStatus(), is(Status.STATUS_ACTIVE));
            return manager.getTransaction();
        });

        assertThat(transaction.getStatus(), is(Status.STATUS_COMMITTED));
        assertThat(completion.events, contains("beforeCompletion", "afterCompletion"));
        assertThat(completion.status, is(Status.STATUS_COMMITTED));
        assertThat(manager.getStatus(), is(Status.STATUS_NO_TRANSACTION));
    }

    @Test
    void applicationFailureRollsBack() throws Exception {
        var completion = new Completion();
        var failure = new IllegalStateException("Application failure");

        var thrown = assertThrows(TxException.class, () -> Tx.transaction(() -> {
            registry.registerInterposedSynchronization(completion);
            throw failure;
        }));

        assertThat(thrown.getCause(), sameInstance(failure));
        assertThat(completion.events, contains("afterCompletion"));
        assertThat(completion.status, is(Status.STATUS_ROLLEDBACK));
        assertThat(manager.getStatus(), is(Status.STATUS_NO_TRANSACTION));
    }

    @Test
    void transactionOnlyRuntime() {
        for (var resource : List.of("io/helidon/transaction/jta/JtaProvider.class",
                                    "io/helidon/transaction/narayana/NarayanaProvider.class",
                                    "io/helidon/extensions/jakarta/http/media/jsonp/JsonpSupport.class",
                                    "io/helidon/extensions/jakarta/http/media/jsonb/JsonbSupport.class")) {
            assertThat(resource, ClassLoader.getSystemResource(resource), nullValue());
        }
        assertThat("Narayana implementation dependency",
                   ClassLoader.getSystemResource("com/fasterxml/jackson/core/JsonFactory.class"),
                   notNullValue());
    }

    private static class Completion implements Synchronization {
        private final List<String> events = new ArrayList<>();
        private int status = Status.STATUS_UNKNOWN;

        @Override
        public void beforeCompletion() {
            events.add("beforeCompletion");
        }

        @Override
        public void afterCompletion(int completionStatus) {
            events.add("afterCompletion");
            status = completionStatus;
        }
    }
}
