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
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CancellationException;

import javax.jms.InvalidDestinationException;
import javax.jms.JMSException;
import javax.jms.JMSSecurityException;
import javax.naming.CommunicationException;
import javax.naming.ConfigurationException;
import javax.naming.InterruptedNamingException;
import javax.naming.InvalidNameException;
import javax.naming.NameNotFoundException;
import javax.naming.NamingSecurityException;
import javax.naming.NoInitialContextException;
import javax.naming.NotContextException;
import javax.naming.OperationNotSupportedException;
import javax.naming.ServiceUnavailableException;

import io.helidon.builder.api.Prototype;

import oracle.AQ.AQException;

final class AqReconnectSupport {
    private AqReconnectSupport() {
    }

    static boolean recoverable(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Throwable> pending = new ArrayDeque<>();
        pending.add(failure);
        boolean connectionFailure = false;
        while (!pending.isEmpty()) {
            Throwable current = pending.removeFirst();
            if (!seen.add(current)) {
                continue;
            }
            if (current instanceof Error
                    || current instanceof InterruptedException
                    || current instanceof CancellationException
                    || current instanceof InvalidDestinationException
                    || current instanceof JMSSecurityException
                    || current instanceof InterruptedNamingException
                    || current instanceof NamingSecurityException
                    || current instanceof NameNotFoundException
                    || current instanceof InvalidNameException
                    || current instanceof NoInitialContextException
                    || current instanceof ConfigurationException
                    || current instanceof NotContextException
                    || current instanceof OperationNotSupportedException) {
                return false;
            }
            if (current instanceof AqConnectionTimeoutException
                    || current instanceof SQLRecoverableException
                    || current instanceof SQLTransientConnectionException
                    || current instanceof CommunicationException
                    || current instanceof ServiceUnavailableException) {
                connectionFailure = true;
            }
            if (current instanceof SQLException sql) {
                if (permanentError(sql.getErrorCode())) {
                    return false;
                }
                String state = sql.getSQLState();
                if ((state != null && state.startsWith("08")) || connectionError(sql.getErrorCode())) {
                    connectionFailure = true;
                }
                if (sql.getNextException() != null) {
                    pending.add(sql.getNextException());
                }
            }
            if (current instanceof JMSException jms && jms.getLinkedException() != null) {
                pending.add(jms.getLinkedException());
            }
            if (current instanceof AQException aq && aq.getNextException() != null) {
                pending.add(aq.getNextException());
            }
            if (current.getCause() != null) {
                pending.add(current.getCause());
            }
        }
        return connectionFailure;
    }

    private static boolean permanentError(int code) {
        return switch (code) {
        // Invalid credentials, locked/expired accounts, authentication protocol, and missing queue.
        case 1017, 28000, 28001, 28040, 24010 -> true;
        default -> false;
        };
    }

    private static boolean connectionError(int code) {
        return switch (code) {
        // Killed/disconnected sessions, unavailable database, and Oracle Net connection failures.
        case 28, 1012, 1033, 1034, 1089, 1090, 2396, 3113, 3114, 3135,
             12170, 12514, 12516, 12518, 12519, 12520, 12528, 12537, 12541, 12543, 12545, 12547,
             17002, 17008, 17410 -> true;
        default -> false;
        };
    }

    private static void positiveMillis(String name, Duration duration) {
        try {
            if (duration.isNegative() || duration.toMillis() < 1) {
                throw new IllegalArgumentException(name + " must be at least one millisecond");
            }
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(name + " is too large", e);
        }
    }

    static final class BuilderDecorator implements Prototype.BuilderDecorator<AqReconnectConfig.BuilderBase<?, ?>> {
        @Override
        public void decorate(AqReconnectConfig.BuilderBase<?, ?> builder) {
            positiveMillis("reconnect.initial-delay", builder.initialDelay());
            positiveMillis("reconnect.max-delay", builder.maxDelay());
            positiveMillis("reconnect.connect-timeout", builder.connectTimeout());
            positiveMillis("reconnect.wait-timeout", builder.waitTimeout());
            if (builder.maxDelay().compareTo(builder.initialDelay()) < 0) {
                throw new IllegalArgumentException("reconnect.max-delay must not be less than initial-delay");
            }
            if (!Double.isFinite(builder.jitter()) || builder.jitter() < 0 || builder.jitter() > 1) {
                throw new IllegalArgumentException("reconnect.jitter must be between zero and one");
            }
        }
    }
}
