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
import java.util.Locale;

import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.MessageProducer;
import javax.jms.Session;

import io.helidon.messaging.MessagingException;

import oracle.jms.AQjmsDestination;
import oracle.jms.AQjmsProducer;
import oracle.jms.AQjmsSession;

final class AqEnqueueTransformation {
    private AqEnqueueTransformation() {
    }

    static void configure(MessageProducer producer, String name) {
        if (!(producer instanceof AQjmsProducer aq)) {
            throw new MessagingException("Enqueue transformation requires an Oracle AQ producer");
        }
        aq.setTransformation(name);
    }

    static void requireClassicDestination(Session session, Destination destination) throws JMSException {
        if (!(session instanceof AQjmsSession aqSession) || !(destination instanceof AQjmsDestination aqDestination)) {
            throw new MessagingException("Enqueue transformation requires an Oracle AQ session and destination");
        }
        String name = aqDestination.getQueueName();
        String owner = name == null ? aqDestination.getTopicOwner() : aqDestination.getQueueOwner();
        if (name == null) {
            name = aqDestination.getTopicName();
        }
        if (name == null || owner == null) {
            throw new MessagingException("Oracle AQ destination identity is unavailable for enqueue transformation");
        }
        String dictionaryOwner = dictionaryName(owner);
        String dictionaryQueue = dictionaryName(name);
        // Inspect the public dictionary without the legacy AQ wrapper's assumptions about the JDBC type map.
        try (var statement = aqSession.getDBConnection().prepareStatement(
                "SELECT SHARDED FROM SYS.ALL_QUEUES WHERE OWNER = ? AND NAME = ?")) {
            statement.setString(1, dictionaryOwner);
            statement.setString(2, dictionaryQueue);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new MessagingException("AQ destination metadata is not visible for enqueue transformation");
                }
                String sharded = result.getString(1);
                if (result.next()) {
                    throw new MessagingException("AQ destination metadata is ambiguous for enqueue transformation");
                }
                if ("TRUE".equalsIgnoreCase(sharded)) {
                    throw new MessagingException(
                            "Enqueue transformations are not supported on sharded AQ or TxEventQ queues");
                }
                if (!"FALSE".equalsIgnoreCase(sharded)) {
                    throw new MessagingException("AQ destination has unrecognized sharded-queue metadata");
                }
            }
        } catch (SQLException e) {
            throw new MessagingException("Cannot inspect AQ destination for enqueue transformation", e);
        }
    }

    private static String dictionaryName(String identifier) {
        boolean quoted = identifier.length() >= 2 && identifier.startsWith("\"") && identifier.endsWith("\"");
        String name = quoted ? identifier.substring(1, identifier.length() - 1) : identifier.toUpperCase(Locale.ROOT);
        if (name.isEmpty() || (!quoted && name.isBlank()) || name.indexOf('"') >= 0 || name.indexOf('\0') >= 0) {
            throw new MessagingException("AQ destination contains an invalid database identifier");
        }
        return name;
    }
}
