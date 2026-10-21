package com.example.ai_controll;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the baseline migration actually runs and produces the expected schema.
 *
 * <p>This exists because the failure it guards against is silent: with Spring Boot 4's modular
 * autoconfiguration, depending on {@code flyway-core} instead of {@code spring-boot-starter-flyway}
 * leaves Flyway on the classpath but never wired up. The application still starts, Hibernate's
 * {@code ddl-auto: validate} still passes while there are no entities, and nothing looks wrong until
 * the first repository query hits a table that was never created.
 */
@SpringBootTest
class SchemaMigrationTest {

	@Autowired
	private DataSource dataSource;

	@Test
	@DisplayName("Flyway applies the baseline migration")
	void baselineMigrationIsApplied() {
		JdbcTemplate jdbc = new JdbcTemplate(dataSource);

		// Flyway quotes every identifier it creates, in lower case, so the table and the column both
		// have to be quoted here; unquoted identifiers would be folded to upper case and not match.
		Integer applied = jdbc.queryForObject(
				"SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"success\" = TRUE", Integer.class);
		assertThat(applied)
				.as("Flyway must have applied at least the baseline migration")
				.isNotNull()
				.isPositive();
	}

	@Test
	@DisplayName("every table the harness plans to use exists")
	void expectedTablesExist() throws Exception {
		List<String> tables = new ArrayList<>();
		try (Connection connection = dataSource.getConnection()) {
			DatabaseMetaData metaData = connection.getMetaData();
			try (ResultSet rs = metaData.getTables(null, "PUBLIC", "%", new String[] { "TABLE" })) {
				while (rs.next()) {
					tables.add(rs.getString("TABLE_NAME").toUpperCase());
				}
			}
		}

		assertThat(tables).contains("MCP_SERVER", "TOOL_DEFINITION", "TOOLSET", "TOOLSET_MEMBER",
				"TOOL_POLICY", "AGENT_PROFILE", "RUN", "RUN_MESSAGE", "RUN_EVENT");
	}

	@Test
	@DisplayName("at most one version of a tool can be current")
	void currentToolVersionIsUniquePerName() {
		JdbcTemplate jdbc = new JdbcTemplate(dataSource);
		String insert = """
				INSERT INTO tool_definition
				  (id, name, version, current_of, description, params_schema, executor_type, source, created_at)
				VALUES (?, ?, ?, ?, 'desc', '{}', 'BUILTIN', 'USER', CURRENT_TIMESTAMP)
				""";

		jdbc.update(insert, java.util.UUID.randomUUID(), "demo_tool", 1, "demo_tool");
		// A superseded version carries a NULL marker, and NULLs are distinct in a unique
		// constraint, so any number of historical versions may coexist.
		jdbc.update(insert, java.util.UUID.randomUUID(), "demo_tool", 2, null);
		jdbc.update(insert, java.util.UUID.randomUUID(), "demo_tool", 3, null);

		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM tool_definition WHERE name = 'demo_tool'", Integer.class))
				.isEqualTo(3);

		// But a second row claiming to be current for the same tool must be rejected by the database,
		// not merely by application code.
		org.assertj.core.api.Assertions
				.assertThatThrownBy(() -> jdbc.update(insert, java.util.UUID.randomUUID(), "demo_tool", 4,
						"demo_tool"))
				.isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
	}
}
