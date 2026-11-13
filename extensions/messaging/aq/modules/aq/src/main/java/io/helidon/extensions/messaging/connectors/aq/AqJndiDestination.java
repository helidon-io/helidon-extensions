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

import java.util.Hashtable;

import javax.jms.Destination;
import javax.jms.InvalidDestinationException;
import javax.jms.JMSException;
import javax.jms.Queue;
import javax.jms.Topic;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;

import oracle.jms.AQjmsDestination;

final class AqJndiDestination {
    private AqJndiDestination() {
    }

    static Destination resolve(AqJndiConfig config, AqDestinationType type) throws NamingException, JMSException {
        Context context = new InitialContext(new Hashtable<>(config.envProperties()));
        Throwable failure = null;
        try {
            return destination(context.lookup(config.destination()), type);
        } catch (NamingException | JMSException | RuntimeException | Error e) {
            failure = e;
            throw e;
        } finally {
            try {
                context.close();
            } catch (NamingException | RuntimeException | Error closeFailure) {
                if (failure == null) {
                    throw closeFailure;
                }
                if (closeFailure != failure) {
                    failure.addSuppressed(closeFailure);
                }
            }
        }
    }

    private static Destination destination(Object value, AqDestinationType type) throws JMSException {
        if (value instanceof AQjmsDestination aq) {
            // Oracle destinations implement both interfaces; only the actual kind's name accessor returns a value.
            String name = switch (type) {
                case QUEUE -> aq.getQueueName();
                case TOPIC -> aq.getTopicName();
            };
            if (name != null && !name.isBlank()) {
                return aq;
            }
        } else {
            if (type == AqDestinationType.QUEUE && value instanceof Queue queue) {
                return queue;
            }
            if (type == AqDestinationType.TOPIC && value instanceof Topic topic) {
                return topic;
            }
        }
        throw new InvalidDestinationException("JNDI lookup must return a javax.jms destination matching destination-type "
                                                      + type);
    }
}
