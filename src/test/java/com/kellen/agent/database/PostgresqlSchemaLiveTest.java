package com.kellen.agent.database;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本服务的 Flyway 迁移在 PostgreSQL 18 上执行：新空库首次启动建出 Seata AT 的 undo_log，不建任何业务表（ai-agent 不放业务表）。
 * 只用独立测试库，每个测试独占一个 schema，结束后只删自己的 schema；没给测试库时跳过（与其他服务的同名测试一致）。
 */
@EnabledIfEnvironmentVariable(named = "FLEET_PG_TEST_URL", matches = ".+")
class PostgresqlSchemaLiveTest {

    private Connection connection;

    private String schema;

    @BeforeEach
    void createIsolatedSchema() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("user", System.getenv("FLEET_PG_TEST_USERNAME"));
        properties.setProperty("password", System.getenv("FLEET_PG_TEST_PASSWORD"));
        properties.setProperty("connectTimeout", "3");
        properties.setProperty("socketTimeout", "5");
        connection = DriverManager.getConnection(System.getenv("FLEET_PG_TEST_URL"), properties);
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT current_database(), current_setting('server_version_num')::int")) {
            result.next();
            // 先守卫库名，再执行任何 DDL；禁止把业务库当测试库。
            assertThat(result.getString(1)).startsWith("fleet_pg_test_");
            assertThat(result.getInt(2)).isGreaterThanOrEqualTo(180000);
        }
        schema = "fixture_" + UUID.randomUUID().toString().replace("-", "");
        execute("CREATE SCHEMA " + schema);
    }

    @AfterEach
    void removeOnlyOwnSchema() throws Exception {
        if (connection != null) {
            try {
                if (schema != null) {
                    execute("DROP SCHEMA " + schema + " CASCADE");
                }
            } finally {
                connection.close();
            }
        }
    }

    @Test
    void migratesEmptySchemaToOnlyTheSeataUndoLog() throws Exception {
        var result = Flyway.configure()
                .dataSource(System.getenv("FLEET_PG_TEST_URL"), System.getenv("FLEET_PG_TEST_USERNAME"), System.getenv("FLEET_PG_TEST_PASSWORD"))
                .schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration")
                .cleanDisabled(true)
                .load().migrate();
        assertThat(result.success).isTrue();
        assertThat(strings("SELECT version FROM " + schema + ".flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank"))
                .containsExactly("1");
        assertThat(strings("SELECT table_name FROM information_schema.tables WHERE table_schema = '" + schema
                + "' AND table_name <> 'flyway_schema_history' ORDER BY table_name")).containsExactly("undo_log");
        assertThat(strings("SELECT column_name FROM information_schema.columns WHERE table_schema = '" + schema
                + "' AND table_name = 'undo_log' ORDER BY ordinal_position"))
                .containsExactly("id", "branch_id", "xid", "context", "rollback_info", "log_status", "log_created", "log_modified");
    }

    private List<String> strings(String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            while (result.next()) {
                values.add(result.getString(1));
            }
        }
        return values;
    }

    private void execute(String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
