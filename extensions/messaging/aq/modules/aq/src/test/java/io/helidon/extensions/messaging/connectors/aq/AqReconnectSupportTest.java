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

package io.helidon.extensions.messaging.connectors.aq;

import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;
import java.util.List;
import java.util.concurrent.CancellationException;

import javax.jms.JMSException;
import javax.naming.AuthenticationException;
import javax.naming.CommunicationException;
import javax.naming.InvalidNameException;
import javax.naming.NameNotFoundException;
import javax.naming.NamingException;
import javax.naming.ServiceUnavailableException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

@Timeout(5)
class AqReconnectSupportTest {
    @Test
    void testConnectionFailuresAcrossProviderChains() {
        for (Exception cause : List.of(new SQLRecoverableException("lost"),
                                       new SQLTransientConnectionException("unavailable"),
                                       new SQLException("connection lost", "08006"),
                                       new SQLException("killed session", null, 28),
                                       new SQLException("no listener", null, 12541))) {
            JMSException linked = new JMSException("JMS-120");
            linked.setLinkedException(cause);
            assertThat(AqReconnectSupport.recoverable(new IllegalStateException(linked)), is(true));
        }
        SQLException sql = new SQLException("wrapper");
        sql.setNextException(new SQLRecoverableException("lost"));
        assertThat(AqReconnectSupport.recoverable(sql), is(true));
    }

    @Test
    void testPermanentFailuresAreNotConnectionFailures() {
        for (Throwable failure : List.of(new JMSException("unknown"),
                                         new SQLException("invalid credentials", "72000", 1017),
                                         new SQLException("queue missing", "72000", 24010),
                                         new SQLException("invalid payload", "72000", 17059),
                                         new InterruptedException("shutdown"),
                                         new IllegalArgumentException("configuration"))) {
            assertThat(AqReconnectSupport.recoverable(failure), is(false));
        }
    }

    @Test
    void testLinkedExceptionCycleTerminates() {
        JMSException first = new JMSException("first");
        JMSException second = new JMSException("second");
        first.setLinkedException(second);
        second.setLinkedException(first);
        assertThat(AqReconnectSupport.recoverable(first), is(false));
    }

    @Test
    void testNamingFailuresPreserveTransientAndPermanentDistinctions() {
        for (NamingException transientFailure : List.of(new CommunicationException("connection lost"),
                                                        new ServiceUnavailableException("offline"))) {
            NamingException wrapper = new NamingException("lookup failed");
            wrapper.setRootCause(transientFailure);
            assertThat(AqReconnectSupport.recoverable(wrapper), is(true));
        }
        for (NamingException permanent : List.of(new NameNotFoundException("missing"),
                                                 new InvalidNameException("malformed"),
                                                 new AuthenticationException("denied"))) {
            permanent.setRootCause(new CommunicationException("provider context"));
            assertThat(AqReconnectSupport.recoverable(permanent), is(false));
        }
    }

    @Test
    void testFatalAndCancellationCausesOverrideConnectionFailure() {
        SQLRecoverableException connection = new SQLRecoverableException("connection lost");
        OutOfMemoryError fatal = new OutOfMemoryError("fatal");
        fatal.initCause(connection);
        assertThat(AqReconnectSupport.recoverable(fatal), is(false));
        CancellationException cancelled = new CancellationException("shutdown");
        cancelled.initCause(connection);
        assertThat(AqReconnectSupport.recoverable(cancelled), is(false));
        SQLRecoverableException interrupted = new SQLRecoverableException("cancelled", new InterruptedException());
        assertThat(AqReconnectSupport.recoverable(interrupted), is(false));
        SQLRecoverableException credentials = new SQLRecoverableException("authentication", "08000", 1017);
        assertThat(AqReconnectSupport.recoverable(credentials), is(false));
    }
}
