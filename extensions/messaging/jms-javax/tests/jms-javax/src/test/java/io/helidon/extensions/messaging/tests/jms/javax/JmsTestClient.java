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

package io.helidon.extensions.messaging.tests.jms.javax;

import java.io.Serializable;
import java.time.Duration;
import java.util.Objects;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.ObjectMessage;
import javax.jms.Queue;
import javax.jms.Session;
import javax.jms.TextMessage;
import javax.jms.Topic;

final class JmsTestClient {
    private JmsTestClient() {
    }

    static void sendText(ConnectionFactory factory,
                         String destination,
                         boolean topic,
                         String text,
                         MessageCustomizer<TextMessage> customizer) throws JMSException {
        send(factory, destination, topic, session -> {
            TextMessage message = session.createTextMessage(text);
            customizer.customize(message);
            return message;
        });
    }

    static void sendObject(ConnectionFactory factory,
                           String destination,
                           Serializable object,
                           MessageCustomizer<ObjectMessage> customizer) throws JMSException {
        send(factory, destination, false, session -> {
            ObjectMessage message = session.createObjectMessage(object);
            customizer.customize(message);
            return message;
        });
    }

    static Message receive(ConnectionFactory factory,
                           String destination,
                           boolean topic,
                           Duration timeout) throws JMSException {
        try (Connection connection = factory.createConnection();
             Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
            Destination target = destination(session, destination, topic);
            try (MessageConsumer consumer = session.createConsumer(target)) {
                connection.start();
                return consumer.receive(timeout.toMillis());
            }
        }
    }

    static TextMessage receiveText(ConnectionFactory factory,
                                   String destination,
                                   Duration timeout) throws JMSException {
        return (TextMessage) receive(factory, destination, false, timeout);
    }

    private static void send(ConnectionFactory factory,
                             String destination,
                             boolean topic,
                             MessageFactory messageFactory) throws JMSException {
        try (Connection connection = factory.createConnection();
             Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
            Destination target = destination(session, destination, topic);
            try (MessageProducer producer = session.createProducer(target)) {
                producer.send(Objects.requireNonNull(messageFactory.create(session)));
            }
        }
    }

    private static Destination destination(Session session, String name, boolean topic) throws JMSException {
        if (topic) {
            Topic result = session.createTopic(name);
            return result;
        }
        Queue result = session.createQueue(name);
        return result;
    }

    @FunctionalInterface
    private interface MessageFactory {
        Message create(Session session) throws JMSException;
    }

    @FunctionalInterface
    interface MessageCustomizer<T extends Message> {
        void customize(T message) throws JMSException;
    }
}
