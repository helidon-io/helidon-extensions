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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.naming.Context;
import javax.naming.NamingException;
import javax.naming.spi.InitialContextFactory;

public final class AqTestInitialContextFactory implements InitialContextFactory {
    private static final String REGISTRATION = "aq.test.context";
    private static final Map<String, InitialContextFactory> FACTORIES = new ConcurrentHashMap<>();

    public AqTestInitialContextFactory() {
    }

    static Registration register(InitialContextFactory factory) {
        String id = UUID.randomUUID().toString();
        FACTORIES.put(id, factory);
        return new Registration(id);
    }

    @Override
    public Context getInitialContext(Hashtable<?, ?> environment) throws NamingException {
        InitialContextFactory factory = FACTORIES.get(environment.get(REGISTRATION));
        if (factory == null) {
            throw new NamingException("No test context registered");
        }
        return factory.getInitialContext(environment);
    }

    record Registration(String id) implements AutoCloseable {
        AqJndiConfig config(String destination) {
            return AqJndiConfig.builder().destination(destination)
                    .putEnvProperty(Context.INITIAL_CONTEXT_FACTORY, AqTestInitialContextFactory.class.getName())
                    .putEnvProperty(REGISTRATION, id).build();
        }

        @Override
        public void close() {
            FACTORIES.remove(id);
        }
    }
}
