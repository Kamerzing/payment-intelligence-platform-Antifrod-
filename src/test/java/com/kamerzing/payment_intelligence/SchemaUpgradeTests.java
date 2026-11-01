package com.kamerzing.payment_intelligence;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@SpringBootTest
@ActiveProfiles("test-integration")
@Import(PostgresFoundationTests.Database.class)
class SchemaUpgradeTests {
	@Autowired DataSource dataSource;
	@Test void upgradesV2AndBackfillsOneInvestigationForAnExistingDenial() throws Exception {
		try(var connection=dataSource.getConnection()) {
			connection.createStatement().execute("CREATE SCHEMA upgrade_risk");connection.setSchema("upgrade_risk");
			var ds=new SingleConnectionDataSource(connection,true);Flyway.configure().dataSource(ds).schemas("upgrade_risk").defaultSchema("upgrade_risk").target("2").load().migrate();
			var jdbc=new JdbcTemplate(ds);var wallet=java.util.UUID.randomUUID();var attempt=java.util.UUID.randomUUID();
			jdbc.update("INSERT INTO risk_wallet_lock VALUES (?)",wallet);jdbc.update("INSERT INTO risk_rule_set(version,configuration) VALUES ('legacy','{}')");
			jdbc.update("INSERT INTO risk_attempt VALUES (?,?,null,'WITHDRAWAL','{}',clock_timestamp())",attempt,wallet);
			jdbc.update("INSERT INTO risk_evaluation VALUES (?,?, 'legacy','DENY',clock_timestamp(),null,'{}','{}')",java.util.UUID.randomUUID(),attempt);
			var current=Flyway.configure().dataSource(ds).schemas("upgrade_risk").defaultSchema("upgrade_risk").load();assertThat(current.migrate().migrationsExecuted).isEqualTo(2);
			assertThat(jdbc.queryForObject("SELECT count(*) FROM investigation",Long.class)).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT count(*) FROM investigation_history",Long.class)).isEqualTo(1);
			assertThat(current.migrate().migrationsExecuted).isZero();connection.setSchema("public");
		}
	}
	@Test void upgradesV1WithMetadataAndRepeatsWithoutChanges() throws Exception {
		try(var connection=dataSource.getConnection()) {
			connection.createStatement().execute("CREATE SCHEMA upgrade_platform");connection.setSchema("upgrade_platform");
			var ds=new SingleConnectionDataSource(connection,true);
			Flyway.configure().dataSource(ds).schemas("upgrade_platform").defaultSchema("upgrade_platform").target("1").load().migrate();
			var jdbc=new JdbcTemplate(ds);jdbc.update("INSERT INTO platform_metadata(key,value) VALUES ('upgrade-proof','preserved')");
			var current=Flyway.configure().dataSource(ds).schemas("upgrade_platform").defaultSchema("upgrade_platform").load();
			assertThat(current.migrate().migrationsExecuted).isEqualTo(3);
			assertThat(jdbc.queryForObject("SELECT value FROM platform_metadata WHERE key='upgrade-proof'",String.class)).isEqualTo("preserved");
			assertThat(current.migrate().migrationsExecuted).isZero();connection.setSchema("public");
		}
	}
}
