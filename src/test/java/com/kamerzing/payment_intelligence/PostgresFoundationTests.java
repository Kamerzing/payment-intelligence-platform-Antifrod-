package com.kamerzing.payment_intelligence;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test-integration")
@Import(PostgresFoundationTests.Database.class)
@ExtendWith(OutputCaptureExtension.class)
class PostgresFoundationTests {
	@Autowired JdbcTemplate jdbc;
	@Autowired Flyway flyway;
	@Value("${local.server.port}") int port;

	@Test
	void migratesAnIsolatedPostgresAndPreservesMetadataOnRepeatedMigration() {
		assertThat(jdbc.queryForObject("SHOW server_version_num", Integer.class)).isBetween(180000, 189999);
		assertThat(jdbc.queryForObject("SELECT value FROM platform_metadata WHERE key='service_name'", String.class))
				.isEqualTo("payment-intelligence-platform");
		flyway.validate();
		assertThat(flyway.migrate().migrationsExecuted).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM platform_metadata", Integer.class)).isEqualTo(1);
	}

	@Test
	void servesLiveReadinessWithoutDatabaseDetails() throws Exception {
		try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
			var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health/readiness"))
					.timeout(Duration.ofSeconds(5)).GET().build();
			var response = client.send(request, HttpResponse.BodyHandlers.ofString());
			assertThat(response.statusCode()).isEqualTo(200);
			assertThat(response.body()).contains("\"status\":\"UP\"").doesNotContain("jdbc:", "components", "password");
		}
	}

	@Test
	void keepsExceptionMessagesAndStackTracesOutOfStructuredLogs(CapturedOutput output) {
		LoggerFactory.getLogger(PostgresFoundationTests.class).warn("Diagnostic probe",
				new IllegalStateException("private-exception-detail"));
		assertThat(output.getOut()).contains("Diagnostic probe")
				.doesNotContain("private-exception-detail", "error.stack_trace", "\"stack_trace\"");
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class Database {
		@Bean
		@ServiceConnection
		PostgreSQLContainer postgres() {
			return new PostgreSQLContainer("postgres:18.6-alpine");
		}
	}
}
