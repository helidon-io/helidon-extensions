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

import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.MessageProducer;
import javax.jms.Session;

import io.helidon.messaging.MessagingException;

import oracle.AQ.AQException;
import oracle.AQ.AQOracleQueue;
import oracle.AQ.AQOracleSession;
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
        try {
            AQOracleSession metadata = new AQOracleSession(aqSession.getDBConnection());
            try {
                AQOracleQueue queue = (AQOracleQueue) metadata.getQueue(owner, name);
                try {
                    // getQueue loads SHARDED; the no-argument property getter uses an older query without that flag.
                    if (queue.getProperty(true).isSharded()) {
                        throw new MessagingException(
                                "Enqueue transformations are not supported on sharded AQ or TxEventQ queues");
                    }
                } finally {
                    queue.close();
                }
            } finally {
                // These metadata handles close their own statements, not the JMS session's borrowed JDBC connection.
                metadata.close();
            }
        } catch (AQException e) {
            throw new MessagingException("Cannot inspect AQ destination for enqueue transformation", e);
        }
    }
}
