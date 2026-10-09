package com.mhamzah.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mhamzah.gateway.extension.DefaultErrorHandler;
import com.mhamzah.gateway.sql.SqlDatasources;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Database query steps: datasource, one statement, read-only, a mapping rule for every :parameter. */
class SqlStepCompileTest {

    private Rows rows = new Rows();
    // never connected: compiling does not touch the database
    private final SqlDatasources sql = SqlDatasources.create(new GatewayProperties.Sql(100, Map.of(
            "MAIN", new GatewayProperties.SqlDatasource(null, null, null, null, 5, false),
            "REPORTS", new GatewayProperties.SqlDatasource(null, null, null, null, 5, true))),
            new DriverManagerDataSource("jdbc:none"));

    private FlowRegistry compile() {
        ConfigCompiler compiler = new ConfigCompiler(
                new ConfigCompiler.HandlerLookup() {
                    @Override
                    public <T> T find(String name, Class<T> type) {
                        Object bean = "defaultErrorHandler".equals(name) ? new DefaultErrorHandler() : null;
                        return type.isInstance(bean) ? type.cast(bean) : null;
                    }
                },
                Map.of(), s -> s, Duration.ofSeconds(30), Duration.ofSeconds(10), sql, com.mhamzah.gateway.storage.FileStores.none());
        return compiler.compile(rows.build());
    }

    private void assertInvalid(String... fragments) {
        assertThatThrownBy(this::compile).isInstanceOfSatisfying(ConfigValidationException.class, e -> {
            for (String f : fragments) {
                assertThat(e.errors()).anySatisfy(err -> assertThat(err).contains(f));
            }
        });
    }

    private ConfigRows.StepRow query(String datasource, String text) {
        var flow = rows.flow("F", "GET", "/f/{id}");
        return rows.step(flow, "q", 1, s -> s.withTarget(datasource).withPathTemplate(null).withSql(text));
    }

    @Test
    void compilesAQueryStepWithItsParameters() {
        var step = query("REPORTS", "SELECT * FROM t WHERE id = :id");
        rows.stepRule(step, "$.id", "$.request.path.id");

        StepDefinition def = compile().flows().getFirst().allSteps().getFirst();

        assertThat(def.isSql()).isTrue();
        assertThat(def.methodName()).isEqualTo("SQL");
        assertThat(def.sql().datasourceName()).isEqualTo("REPORTS");
        assertThat(def.sql().text().parameterNames()).containsExactly("id");
    }

    @Test
    void everyParameterNeedsAMappingRule() {
        var step = query("MAIN", "SELECT * FROM t WHERE id = :id AND kind = :kind");
        rows.stepRule(step, "$.id", "$.request.path.id");
        assertInvalid("SQL parameter :kind has no mapping rule (a BODY rule writing $.kind)");
    }

    @Test
    void unknownDatasourceSeveralStatementsAndWritesOnReadOnlyAreRejected() {
        query("NOPE", "SELECT 1");
        assertInvalid("target_system 'NOPE' is not a SQL datasource");

        rows = new Rows();
        query("MAIN", "SELECT 1; DROP TABLE t");
        assertInvalid("sql_text: only one SQL statement is allowed");

        rows = new Rows();
        query("REPORTS", "DELETE FROM t");
        assertInvalid("datasource 'REPORTS' is read-only: sql_text must be a SELECT or WITH query, not DELETE");
    }

    @Test
    void parametersAreBodyRulesOnly() {
        var step = query("MAIN", "SELECT 1");
        rows.rule(step.flowId(), step.id(), "STEP_REQUEST", "HEADER", "X-A", "$.request.path.id", null);
        assertInvalid("target_type HEADER is not allowed for a database query step");
    }

    @Test
    void writesAreFineOnAWritableDatasource() {
        var step = query("MAIN", "UPDATE t SET name = :name WHERE id = :id");
        rows.stepRule(step, "$.id", "$.request.path.id");
        rows.stepRule(step, "$.name", "$.request.query.name");
        assertThat(compile().flows()).hasSize(1);
    }
}
