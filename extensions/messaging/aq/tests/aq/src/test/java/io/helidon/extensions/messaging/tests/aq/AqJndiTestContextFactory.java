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

package io.helidon.extensions.messaging.tests.aq;

import java.util.Hashtable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.jms.Destination;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NameNotFoundException;
import javax.naming.NamingException;
import javax.naming.spi.InitialContextFactory;

import io.helidon.extensions.messaging.connectors.aq.AqJndiConfig;

public final class AqJndiTestContextFactory implements InitialContextFactory {
    private static final String REGISTRATION = "aq.test.context";
    private static final Map<String, Registration> REGISTRATIONS = new ConcurrentHashMap<>();

    public AqJndiTestContextFactory() {
    }

    static Registration register() {
        Registration registration = new Registration(UUID.randomUUID().toString());
        REGISTRATIONS.put(registration.id, registration);
        return registration;
    }

    @Override
    public Context getInitialContext(Hashtable<?, ?> environment) throws NamingException {
        Registration registration = REGISTRATIONS.get(environment.get(REGISTRATION));
        if (registration == null) {
            throw new NamingException("No test context registered");
        }
        registration.opened.incrementAndGet();
        return new BoundContext(registration);
    }

    static final class Registration implements AutoCloseable {
        private final String id;
        private final Map<String, Destination> destinations = new ConcurrentHashMap<>();
        private final AtomicInteger opened = new AtomicInteger();
        private final AtomicInteger closed = new AtomicInteger();

        private Registration(String id) {
            this.id = id;
        }

        AqJndiConfig config(String destination) {
            return AqJndiConfig.builder().destination(destination)
                    .putEnvProperty(Context.INITIAL_CONTEXT_FACTORY, AqJndiTestContextFactory.class.getName())
                    .putEnvProperty(REGISTRATION, id).build();
        }

        void rebind(String name, Destination destination) {
            destinations.put(name, destination);
        }

        int opened() {
            return opened.get();
        }

        int closed() {
            return closed.get();
        }

        @Override
        public void close() {
            REGISTRATIONS.remove(id);
        }
    }

    private static final class BoundContext extends InitialContext {
        private final Registration registration;

        private BoundContext(Registration registration) throws NamingException {
            super(true);
            this.registration = registration;
        }

        @Override
        public Object lookup(String name) throws NamingException {
            Destination destination = registration.destinations.get(name);
            if (destination == null) {
                throw new NameNotFoundException(name);
            }
            return destination;
        }

        @Override
        public void close() {
            registration.closed.incrementAndGet();
        }
    }
}
