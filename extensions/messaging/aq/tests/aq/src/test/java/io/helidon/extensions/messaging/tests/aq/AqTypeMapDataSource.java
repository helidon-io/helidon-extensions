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

import java.io.PrintWriter;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLInput;
import java.sql.SQLOutput;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

import javax.sql.DataSource;

public final class AqTypeMapDataSource implements DataSource {
    private final DataSource delegate;
    private final Map<String, Class<?>> initialTypeMap;
    private final List<BorrowedConnection> connections = new CopyOnWriteArrayList<>();

    AqTypeMapDataSource(DataSource delegate, String customType) {
        this.delegate = delegate;
        this.initialTypeMap = Map.of(customType, MappedTarget.class);
    }

    List<BorrowedConnection> connections() {
        return List.copyOf(connections);
    }

    Map<String, Class<?>> initialTypeMap() {
        return initialTypeMap;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return prepare(delegate.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return prepare(delegate.getConnection(username, password));
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter writer) throws SQLException {
        delegate.setLogWriter(writer);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        delegate.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return delegate.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return delegate.getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> type) throws SQLException {
        return type.isInstance(this) ? type.cast(this) : delegate.unwrap(type);
    }

    @Override
    public boolean isWrapperFor(Class<?> type) throws SQLException {
        return type.isInstance(this) || delegate.isWrapperFor(type);
    }

    private Connection prepare(Connection connection) throws SQLException {
        Map<String, Class<?>> typeMap = new HashMap<>(initialTypeMap);
        try {
            connection.setTypeMap(typeMap);
            connections.add(new BorrowedConnection(connection, typeMap));
            return connection;
        } catch (SQLException | RuntimeException failure) {
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    record BorrowedConnection(Connection connection, Map<String, Class<?>> typeMap) {
    }

    public static final class MappedTarget implements SQLData {
        private String typeName = "AQ_TEST_TARGET";
        private String description = "";
        private BigDecimal total = BigDecimal.ZERO;

        public MappedTarget() {
        }

        @Override
        public String getSQLTypeName() {
            return typeName;
        }

        @Override
        public void readSQL(SQLInput input, String name) throws SQLException {
            typeName = name;
            description = input.readString();
            total = input.readBigDecimal();
        }

        @Override
        public void writeSQL(SQLOutput output) throws SQLException {
            output.writeString(description);
            output.writeBigDecimal(total);
        }
    }
}
