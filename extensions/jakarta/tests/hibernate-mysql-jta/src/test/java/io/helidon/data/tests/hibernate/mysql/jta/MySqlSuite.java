/*
 * Copyright (c) 2024, 2026 Oracle and/or its affiliates.
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
package io.helidon.data.tests.hibernate.mysql.jta;

import io.helidon.config.ConfigSources;
import io.helidon.data.sql.testing.SqlTestContainerConfig;
import io.helidon.data.sql.testing.TestContainerHandler;
import io.helidon.data.tests.common.InitialData;
import io.helidon.data.tests.repository.PokemonRepository;
import io.helidon.service.registry.Services;
import io.helidon.testing.junit5.suite.TestSuite;
import io.helidon.testing.junit5.suite.spi.SuiteProvider;

import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Transaction method and annotation tests using Hibernate and MySQL.
 */
public class MySqlSuite implements SuiteProvider {

    private final TestContainerHandler containerHandler;

    public MySqlSuite() {
        MySQLContainer<?> container = new MySQLContainer<>(DockerImageName.parse(
                "container-registry.oracle.com/mysql/community-server:9.7.3")
                .asCompatibleSubstituteFor("mysql"));
        this.containerHandler = SqlTestContainerConfig.configureContainer(container,
                                                                          ConfigSources.classpath("application.yaml"));
    }

    @TestSuite.BeforeSuite
    public void beforeSuite() {
        this.containerHandler.startContainer();
        this.containerHandler.setConfig();

        PokemonRepository pokemonRepository = Services.get(PokemonRepository.class);
        pokemonRepository.run(InitialData::init);
        pokemonRepository.run(InitialData::verify);
    }

    @TestSuite.AfterSuite
    public void afterSuite() {
        containerHandler.stopContainer();
    }

    @TestSuite.Suite(MySqlSuite.class)
    @Testcontainers(disabledWithoutDocker = true)
    public static class TestTxMethods extends io.helidon.data.tests.common.TestTxMethods {
    }

    @TestSuite.Suite(MySqlSuite.class)
    @Testcontainers(disabledWithoutDocker = true)
    public static class TestTxAnnotations extends io.helidon.data.tests.common.TestTxAnnotations {
    }
}
