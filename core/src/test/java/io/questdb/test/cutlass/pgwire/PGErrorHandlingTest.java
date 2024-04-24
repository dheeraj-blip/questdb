/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2024 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.test.cutlass.pgwire;

import io.questdb.*;
import io.questdb.cutlass.pgwire.UsernamePasswordMatcher;
import io.questdb.std.FilesFacadeImpl;
import io.questdb.std.str.LPSZ;
import io.questdb.test.BootstrapTest;
import io.questdb.test.tools.TestUtils;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.postgresql.util.PSQLException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public class PGErrorHandlingTest extends BootstrapTest {

    @Before
    public void setUp() {
        super.setUp();
        TestUtils.unchecked(() -> createDummyConfiguration());
        dbPath.parent().$();
    }

    @Test
    public void testUnexpectedErrorDuringSQLExecutionHandled() throws Exception {
        final AtomicInteger counter = new AtomicInteger(0);
        final Bootstrap bootstrap = new Bootstrap(
                new PropBootstrapConfiguration() {
                    @Override
                    public ServerConfiguration getServerConfiguration(Bootstrap bootstrap) throws Exception {
                        return new PropServerConfiguration(
                                bootstrap.getRootDirectory(),
                                bootstrap.loadProperties(),
                                getEnv(),
                                bootstrap.getLog(),
                                bootstrap.getBuildInformation(),
                                new FilesFacadeImpl() {
                                    @Override
                                    public int openRW(LPSZ name, long opts) {
                                        if (counter.incrementAndGet() > 28) {
                                            throw new RuntimeException("Test error");
                                        }
                                        return super.openRW(name, opts);
                                    }
                                },
                                bootstrap.getMicrosecondClock(),
                                (configuration, engine, freeOnExit) -> new FactoryProviderImpl(configuration)
                        );
                    }
                },
                getServerMainArgs()
        );

        TestUtils.assertMemoryLeak(() -> {
            try (ServerMain serverMain = new ServerMain(bootstrap)) {
                serverMain.start();

                try (Connection conn = getConnection()) {
                    conn.createStatement().execute("create table x(y long)");
                    Assert.fail("Expected exception is missing");
                } catch (PSQLException e) {
                    TestUtils.assertContains(e.getMessage(), "ERROR: Test error");
                }
            }
        });
    }

    @Test
    public void testUnexpectedErrorOutsideSQLExecutionResultsInDisconnect() throws Exception {
        Supplier<UsernamePasswordMatcher> supplier = () -> {
            return new UsernamePasswordMatcher() {
                @Override
                public boolean verifyPassword(CharSequence username, long passwordPtr, int passwordLen) {
                    throw new RuntimeException("error outside of sql execution");
                }
            };
        };

        final Bootstrap bootstrap = new Bootstrap(
                new PropBootstrapConfiguration() {
                    @Override
                    public ServerConfiguration getServerConfiguration(Bootstrap bootstrap) throws Exception {
                        return new PropServerConfiguration(
                                bootstrap.getRootDirectory(),
                                bootstrap.loadProperties(),
                                getEnv(),
                                bootstrap.getLog(),
                                bootstrap.getBuildInformation(),
                                FilesFacadeImpl.INSTANCE,
                                bootstrap.getMicrosecondClock(),
                                (configuration, engine, freeOnExit) -> new FactoryProviderImpl(configuration),
                                supplier
                        );
                    }
                },

                getServerMainArgs()
        );

        TestUtils.assertMemoryLeak(() -> {
            try (ServerMain serverMain = new ServerMain(bootstrap)) {
                serverMain.start();
                try (Connection ignored = getConnection()) {
                    Assert.fail("Expected exception is missing");
                } catch (PSQLException e) {
                    TestUtils.assertContains(e.getMessage(), "The connection attempt failed.");
                }
            }
        });
    }

    private static Connection getConnection() throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", "admin");
        properties.setProperty("password", "quest");
        final String url = String.format("jdbc:postgresql://127.0.0.1:%d/qdb", PG_PORT);
        return DriverManager.getConnection(url, properties);
    }
}
