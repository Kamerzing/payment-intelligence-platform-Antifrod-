package com.kamerzing.payment_intelligence;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import com.kamerzing.payment_intelligence.common.web.ApiException;
import com.kamerzing.payment_intelligence.integration.*;
import com.kamerzing.payment_intelligence.risk.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

@Tag("integration")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="pip.integration.core-token=isolated-test-credential-32-characters")
@AutoConfigureMockMvc
@ActiveProfiles("test-integration")
@Import({PostgresFoundationTests.Database.class,EventRiskIntegrationTests.Time.class})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class EventRiskIntegrationTests {
	static final Instant NOW=Instant.parse("2026-10-31T00:00:00Z");
	static final String AUTH="Bearer isolated-test-credential-32-characters";
	@Autowired JdbcTemplate jdbc;
	@Autowired RiskService risks;
	@Autowired RiskRepository riskRepository;
	@Autowired RiskRules rules;
	@Autowired EventService events;
	@Autowired HistoryImportService imports;
	@Autowired ObjectMapper json;
	@Autowired MockMvc mvc;
	@Autowired MutableClock clock;
	@Autowired PlatformTransactionManager transactions;
	@Autowired Flyway flyway;
	UUID wallet;
	@BeforeEach void reset() {
		jdbc.execute("TRUNCATE ai_analysis,investigation_history,investigation,risk_evaluation,risk_attempt,risk_wallet_lock,risk_rule_set,integration_event,operation_view,history_import");
		clock.time.set(NOW); wallet=UUID.randomUUID();
	}
	RiskRequest request(UUID attempt,UUID target,String amount) {
		return new RiskRequest(attempt,target==null?RiskRequest.CommandType.WITHDRAWAL:RiskRequest.CommandType.TRANSFER,
				new UUID(0,10),wallet,target,null,"RUB",amount,"0",0,NOW.minus(Duration.ofDays(2)));
	}
	@Test void concurrencyCountsUniqueAttemptsAndCannotBypassFifthAttempt() throws Exception {
		var responses=parallel(5,()->risks.evaluate(request(UUID.randomUUID(),null,"100")));
		assertThat(responses).filteredOn(r->r.decision().equals("DENY")).hasSize(1);
		assertThat(responses).filteredOn(r->r.decision().equals("DENY")).allSatisfy(r->assertThat(r.triggered_rules()).containsExactly("HIGH_VELOCITY"));
		assertThat(count("risk_attempt")).isEqualTo(5);
	}
	@Test void concurrentRetriesHaveOneDecisionAndChangedParametersConflict() throws Exception {
		var command=request(UUID.randomUUID(),UUID.randomUUID(),"100");
		var responses=parallel(8,()->risks.evaluate(command));
		assertThat(responses.stream().map(RiskResponse::decision_id).distinct()).hasSize(1);
		assertThat(count("risk_attempt")).isEqualTo(1); assertThat(count("risk_evaluation")).isEqualTo(1);
		assertThatThrownBy(()->risks.evaluate(request(command.attempt_id(),command.target_wallet_id(),"101")))
			.isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("ATTEMPT_CONFLICT"));
		wallet=UUID.randomUUID();
		assertThatThrownBy(()->risks.evaluate(request(command.attempt_id(),command.target_wallet_id(),"100"))).isInstanceOf(ApiException.class);
	}
	@Test void timeWindowsExcludeExactLowerBoundaryAndExpiredAllowRechecksWithoutRecounting() {
		var first=request(UUID.randomUUID(),null,"100");
		var allowed=risks.evaluate(first);
		for(int i=0;i<3;i++) risks.evaluate(request(UUID.randomUUID(),null,"100"));
		clock.time.set(NOW.plusSeconds(60));
		var fifth=risks.evaluate(request(UUID.randomUUID(),null,"100"));
		assertThat(fifth.triggered_rules()).isEmpty();
		var refreshed=risks.evaluate(first);
		assertThat(refreshed.decision_id()).isNotEqualTo(allowed.decision_id());
		assertThat(count("risk_attempt")).isEqualTo(5);
		assertThat(json.readTree(jdbc.queryForObject("SELECT facts FROM risk_evaluation WHERE decision_id=?",String.class,refreshed.decision_id())).path("unique_attempts").asInt()).isEqualTo(2);
	}
	@Test void deniedAttemptsRemainCountedAndDenySurvivesNewServiceAndRuleVersion() {
		var command=request(UUID.randomUUID(),null,"10000000");
		var denied=risks.evaluate(command);
		var restarted=new RiskService(new RiskRepository(jdbc),RiskRulesTests.rules("pilot-v2"),clock,json);
		var replay=new TransactionTemplate(transactions).execute(s->restarted.evaluate(command));
		assertThat(replay).isEqualTo(denied);
		for(int i=0;i<3;i++) risks.evaluate(request(UUID.randomUUID(),null,"1"));
		assertThat(risks.evaluate(request(UUID.randomUUID(),null,"1")).triggered_rules()).contains("HIGH_VELOCITY");
		assertThat(count("risk_attempt")).isEqualTo(5);
	}
	@Test void recipientsAreDistinctAndWithdrawalsDoNotAddRecipients() {
		UUID a=UUID.randomUUID(), b=UUID.randomUUID();
		risks.evaluate(request(UUID.randomUUID(),a,"1")); risks.evaluate(request(UUID.randomUUID(),a,"1"));
		clock.time.set(NOW.plusSeconds(61));
		risks.evaluate(request(UUID.randomUUID(),b,"1")); risks.evaluate(request(UUID.randomUUID(),null,"1"));
		var fanout=risks.evaluate(request(UUID.randomUUID(),UUID.randomUUID(),"1"));
		assertThat(fanout.triggered_rules()).containsExactly("RECIPIENT_FANOUT");
		clock.time.set(NOW.plusSeconds(600));
		assertThat(risks.evaluate(request(UUID.randomUUID(),UUID.randomUUID(),"1")).triggered_rules()).contains("RECIPIENT_FANOUT");
		clock.time.set(NOW.plusSeconds(661));
		assertThat(risks.evaluate(request(UUID.randomUUID(),null,"1")).triggered_rules()).doesNotContain("RECIPIENT_FANOUT");
	}
	@Test void rollbackDoesNotLeaveCountersOrDecisionsAndHistoryCannotBeChanged() {
		var command=request(UUID.randomUUID(),null,"1");
		new TransactionTemplate(transactions).executeWithoutResult(s->{risks.evaluate(command);s.setRollbackOnly();});
		assertThat(count("risk_attempt")).isZero();assertThat(count("risk_evaluation")).isZero();
		risks.evaluate(command);
		assertThatThrownBy(()->jdbc.update("UPDATE risk_evaluation SET decision='DENY'")).isInstanceOf(org.springframework.dao.DataAccessException.class);
		assertThatThrownBy(()->jdbc.update("DELETE FROM risk_attempt")).isInstanceOf(org.springframework.dao.DataAccessException.class);
		var changed=new RiskRules(rules.version(),20000000,70,5,Duration.ofSeconds(60),80,3,Duration.ofMinutes(10),40,Duration.ofHours(24),2500000,30,70,Duration.ofSeconds(1));
		var service=new RiskService(riskRepository,changed,clock,json);
		assertThatThrownBy(()->new TransactionTemplate(transactions).execute(s->service.evaluate(request(UUID.randomUUID(),null,"1"))))
			.isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("RISK_CONFIGURATION_CONFLICT"));
		assertThat(count("risk_attempt")).isEqualTo(1);
	}
	@Test void internalAuthIsIndependentOfCookiesAndBrowserCsrfRemainsEnabled() throws Exception {
		String command=json.writeValueAsString(request(UUID.randomUUID(),null,"1"));
		mvc.perform(post("/internal/v1/risk/evaluations").contentType("application/json").content(command)).andExpect(status().isUnauthorized());
		mvc.perform(post("/internal/v1/risk/evaluations").with(user("admin").roles("ADMIN")).with(csrf()).contentType("application/json").content(command)).andExpect(status().isUnauthorized());
		mvc.perform(post("/internal/v1/risk/evaluations").header("Authorization",AUTH).contentType("application/json").content(command)).andExpect(status().isOk()).andExpect(jsonPath("$.decision").value("ALLOW"));
		mvc.perform(post("/api/v1/investigations")).andExpect(status().isForbidden());
		mvc.perform(get("/internal/v1/events").header("Authorization",AUTH)).andExpect(status().isForbidden());
		mvc.perform(post("/internal/v1/risk/evaluations").header("Authorization",AUTH).contentType("application/json").content(command.replace("\"amount_minor\":\"1\"","\"amount_minor\":1"))).andExpect(status().isBadRequest());
		mvc.perform(post("/internal/v1/risk/evaluations").header("Authorization",AUTH).contentType("application/json").content(command.replace("\"fee_basis_points\":0","\"fee_basis_points\":0.5"))).andExpect(status().isBadRequest());
		mvc.perform(post("/internal/v1/risk/evaluations").header("Authorization",AUTH).contentType("application/json").content(command.replace("\"currency\":\"RUB\"","\"currency\":\"RUB\",\"decision\":\"ALLOW\""))).andExpect(status().isBadRequest());
		assertThat(count("risk_attempt")).isEqualTo(1);
	}
	@Test void invalidMoneyContextAndCaptureRelationshipsAreRejectedWithoutCounting() {
		for(String value:List.of("0","-1","01","1.0","9223372036854775808"))
			assertThatThrownBy(()->risks.evaluate(request(UUID.randomUUID(),null,value))).isInstanceOf(ApiException.class);
		var capture=new RiskRequest(UUID.randomUUID(),RiskRequest.CommandType.CAPTURE_TRANSFER,new UUID(0,10),wallet,UUID.randomUUID(),UUID.randomUUID(),"RUB","1","0",0,NOW);
		assertThat(risks.evaluate(capture).decision()).isEqualTo("ALLOW");
		assertThat(count("risk_attempt")).isEqualTo(1);
	}
	@Test void feeOverflowMissingCaptureAndFutureContextDoNotCreateAttempts() {
		var source=request(UUID.randomUUID(),null,"9223372036854775807");
		var overflow=new RiskRequest(source.attempt_id(),source.command_type(),source.initiator_id(),wallet,null,null,"RUB",source.amount_minor(),"1",0,source.user_created_at());
		assertThatThrownBy(()->risks.evaluate(overflow)).isInstanceOf(ApiException.class);
		var missing=new RiskRequest(UUID.randomUUID(),RiskRequest.CommandType.CAPTURE_WITHDRAWAL,source.initiator_id(),wallet,null,null,"RUB","1","0",0,NOW);
		assertThatThrownBy(()->risks.evaluate(missing)).isInstanceOf(ApiException.class);
		var future=new RiskRequest(UUID.randomUUID(),source.command_type(),source.initiator_id(),wallet,null,null,"RUB","1","0",0,NOW.plusSeconds(1));
		assertThatThrownBy(()->risks.evaluate(future)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("RISK_CONTEXT_UNAVAILABLE"));
		assertThat(count("risk_attempt")).isZero();
	}
	@Test void changingRuleVersionReevaluatesAnUnexpiredAllowWithoutCountingAgain() {
		var command=request(UUID.randomUUID(),null,"1"); var allowed=risks.evaluate(command);
		var service=new RiskService(riskRepository,RiskRulesTests.rules("pilot-v2"),clock,json);
		var updated=new TransactionTemplate(transactions).execute(s->service.evaluate(command));
		assertThat(updated.decision_id()).isNotEqualTo(allowed.decision_id());assertThat(updated.rule_set_version()).isEqualTo("pilot-v2");
		assertThat(count("risk_attempt")).isEqualTo(1);assertThat(count("risk_evaluation")).isEqualTo(2);
	}
	@Test void finalDenyWinsOverAnUnexpiredAllowWhenRulesReturnToAnOlderVersion() {
		var command=request(UUID.randomUUID(),null,"1");var allowed=risks.evaluate(command);
		// Fix the tie order deliberately: a previous ALLOW has the largest UUID at the same server time.
		var prior=new RiskResponse(command.attempt_id(),new UUID(-1L,-1L),"ALLOW",0,List.of(),rules.version(),allowed.command_hash(),NOW,NOW.plusSeconds(1));
		riskRepository.save(prior,json.writeValueAsString(prior),"{}");
		var strict=new RiskRules("pilot-strict",1,70,5,Duration.ofSeconds(60),80,3,Duration.ofMinutes(10),40,Duration.ofHours(24),2500000,30,70,Duration.ofSeconds(1));
		var service=new RiskService(riskRepository,strict,clock,json);
		var denied=new TransactionTemplate(transactions).execute(s->service.evaluate(command));
		assertThat(denied.decision()).isEqualTo("DENY");
		assertThat(risks.evaluate(command)).isEqualTo(denied);
		assertThat(count("risk_attempt")).isEqualTo(1);
	}
	CoreEvent event(UUID id,UUID operation,long position,long version,String amount) {
		return new CoreEvent(id,"OPERATION_POSTED",1,"payment-core",Long.toString(position),Long.toString(version),NOW,null,null,
				new CoreEvent.Operation(operation,new UUID(0,10),"TRANSFER","POSTED","RUB",wallet,new UUID(0,20),amount,"0",0,null,NOW,NOW));
	}
	@Test void duplicateConflictOutOfOrderAndRebuildPreserveNewestProjection() throws Exception {
		UUID operation=UUID.randomUUID(); var older=event(UUID.randomUUID(),operation,1,1,"1");var newer=event(UUID.randomUUID(),operation,2,2,"2");
		events.accept(newer);events.accept(older);
		assertThat(events.accept(older).status()).isEqualTo("DUPLICATE");
		assertThat(count("integration_event")).isEqualTo(2);assertThat(count("operation_view")).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT source_version FROM operation_view",Long.class)).isEqualTo(2);
		assertThatThrownBy(()->events.accept(event(older.event_id(),operation,1,1,"3"))).isInstanceOf(ApiException.class);
		events.rebuild();assertThat(jdbc.queryForObject("SELECT source_version FROM operation_view",Long.class)).isEqualTo(2);
		mvc.perform(post("/internal/v1/events").header("Authorization",AUTH).contentType("application/json").content(json.writeValueAsString(older)))
			.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DUPLICATE"));
	}
	@Test void concurrentDeliveryAndInterruptedImportResumeWithoutGapsOrDuplicates() throws Exception {
		var one=event(UUID.randomUUID(),new UUID(0,1),1,1,"1"); var two=event(UUID.randomUUID(),new UUID(0,2),2,1,"2");
		parallel(5,()->events.accept(one));
		UUID export=UUID.randomUUID();imports.begin(export,"2");
		var page1=new HistoryImportService.ExportPage(export,"payment-core","2",List.of(one),one.operation().operation_id(),false);
		imports.page(export,null,page1);
		assertThat(imports.checkpoint(export).after_operation_id()).isEqualTo(one.operation().operation_id());
		var invalid=event(UUID.randomUUID(),new UUID(0,3),3,1,"3");
		assertThatThrownBy(()->imports.page(export,one.operation().operation_id(),new HistoryImportService.ExportPage(export,"payment-core","2",List.of(two,invalid),new UUID(0,3),true))).isInstanceOf(ApiException.class);
		assertThat(count("integration_event")).isEqualTo(1);
		events.accept(two);
		imports.page(export,one.operation().operation_id(),new HistoryImportService.ExportPage(export,"payment-core","2",List.of(two),two.operation().operation_id(),true));
		assertThat(imports.checkpoint(export).complete()).isTrue(); assertThat(count("operation_view")).isEqualTo(2);
		assertThat(imports.page(export,null,page1).complete()).isTrue();
	}
	long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class); }
	<T> List<T> parallel(int count,Callable<T> work) throws Exception {
		var barrier=new CyclicBarrier(count);
		try(var executor=Executors.newFixedThreadPool(count)) {
			var futures=new ArrayList<Future<T>>();for(int i=0;i<count;i++) futures.add(executor.submit(()->{barrier.await(10,TimeUnit.SECONDS);return work.call();}));
			var values=new ArrayList<T>();for(var future:futures) values.add(future.get(20,TimeUnit.SECONDS));return values;
		}
	}
	@TestConfiguration(proxyBeanMethods=false) static class Time { @Bean @Primary MutableClock controlledClock() { return new MutableClock(); } }
	static class MutableClock extends Clock {
		final AtomicReference<Instant> time=new AtomicReference<>(NOW);
		@Override public ZoneId getZone() { return ZoneOffset.UTC; }
		@Override public Clock withZone(ZoneId zone) { return this; }
		@Override public Instant instant() { return time.get(); }
	}
}
