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

import java.util.Collections;

import javax.jms.Destination;
import javax.jms.Queue;
import javax.jms.Session;
import javax.jms.TextMessage;
import javax.jms.Topic;

import oracle.jms.AQjmsAgent;
import oracle.jms.AQjmsDestination;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AqReplyToTest {
    @Test
    void forwardsNativeQueueWithItsOwnerAndKind() throws Exception {
        AQjmsDestination destination = mock(AQjmsDestination.class);
        when(destination.getQueueName()).thenReturn("REPLIES");
        when(destination.getCompleteName()).thenReturn("OTHER_OWNER.REPLIES");

        assertForwarded(destination, "OTHER_OWNER.REPLIES", AqDestinationType.QUEUE);
    }

    @Test
    void forwardsNativeTopicWithItsOwnerAndKind() throws Exception {
        AQjmsDestination destination = mock(AQjmsDestination.class);
        when(destination.getTopicName()).thenReturn("REPLIES");
        when(destination.getCompleteName()).thenReturn("OTHER_OWNER.REPLIES");

        assertForwarded(destination, "OTHER_OWNER.REPLIES", AqDestinationType.TOPIC);
    }

    @Test
    void forwardsStandardQueue() throws Exception {
        Queue destination = mock(Queue.class);
        when(destination.getQueueName()).thenReturn("APP.REPLIES");

        assertForwarded(destination, "APP.REPLIES", AqDestinationType.QUEUE);
    }

    @Test
    void forwardsStandardTopic() throws Exception {
        Topic destination = mock(Topic.class);
        when(destination.getTopicName()).thenReturn("APP.REPLIES");

        assertForwarded(destination, "APP.REPLIES", AqDestinationType.TOPIC);
    }

    @Test
    void forwardsDetachedReplyToAgent() throws Exception {
        AQjmsAgent agent = new AQjmsAgent("consumer", "APP.REPLIES@REMOTE", 0);
        AqMessage<?> snapshot = AqMessageMapper.fromNative(incoming(agent), config());
        agent.setAddress("MUTATED");
        assertThat(snapshot.replyTo().isEmpty(), is(true));
        assertThat(snapshot.replyToAgent().orElseThrow().address().orElseThrow(), is("APP.REPLIES@REMOTE"));

        Session session = mock(Session.class);
        TextMessage outgoing = mock(TextMessage.class);
        when(session.createTextMessage("body")).thenReturn(outgoing);
        AqMessageMapper.toNative(session, snapshot, config());

        ArgumentCaptor<AQjmsAgent> forwarded = ArgumentCaptor.forClass(AQjmsAgent.class);
        verify(outgoing).setJMSReplyTo(forwarded.capture());
        assertThat(forwarded.getValue().getName(), is("consumer"));
        assertThat(forwarded.getValue().getAddress(), is("APP.REPLIES@REMOTE"));
        assertThat(forwarded.getValue().getProtocol(), is(0));
    }

    private static void assertForwarded(Destination destination, String name, AqDestinationType type) throws Exception {
        TextMessage incoming = incoming(destination);
        AqMessage<?> snapshot = AqMessageMapper.fromNative(incoming, config());
        assertThat(snapshot.replyToType(), is(type));
        assertThat(snapshot.replyTo().orElseThrow(), is(name));
        assertThat(snapshot.replyToAgent().isEmpty(), is(true));

        AqMessage<?> metadata = AqMessageMapper.metadataOnly(incoming);
        assertThat(metadata.replyTo().orElseThrow(), is(name));
        assertThat(metadata.replyToType(), is(type));

        Session session = mock(Session.class);
        TextMessage outgoing = mock(TextMessage.class);
        when(session.createTextMessage("body")).thenReturn(outgoing);
        Destination forwarded;
        if (type == AqDestinationType.QUEUE) {
            Queue queue = mock(Queue.class);
            when(session.createQueue(name)).thenReturn(queue);
            forwarded = queue;
        } else {
            Topic topic = mock(Topic.class);
            when(session.createTopic(name)).thenReturn(topic);
            forwarded = topic;
        }
        AqMessageMapper.toNative(session, snapshot, config());
        verify(outgoing).setJMSReplyTo(forwarded);
        if (type == AqDestinationType.QUEUE) {
            verify(session, never()).createTopic(name);
        } else {
            verify(session, never()).createQueue(name);
        }
    }

    private static TextMessage incoming(Destination destination) throws Exception {
        TextMessage incoming = mock(TextMessage.class);
        when(incoming.getText()).thenReturn("body");
        when(incoming.getPropertyNames()).thenReturn(Collections.emptyEnumeration());
        when(incoming.getJMSReplyTo()).thenReturn(destination);
        return incoming;
    }

    private static AqRuntimeConfig config() {
        return AqRuntimeConfig.incoming(AqConnectorConfig.builder().name("aq").buildPrototype(),
                                        AqIncomingConfig.builder()
                                                .connector("aq")
                                                .channelName("requests")
                                                .destination("APP.REQUESTS")
                                                .build());
    }
}
