package com.kamerzing.payment_intelligence;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import com.kamerzing.payment_intelligence.ai.*;
import com.kamerzing.payment_intelligence.risk.*;
import static org.assertj.core.api.Assertions.*;

@Tag("integration")
@SpringBootTest(properties="pip.ai.poll-ms=3600000")
@ActiveProfiles("test-integration")
@Import(PostgresFoundationTests.Database.class)
class AiIntegrationTests {
	@Autowired AiRepository jobs;
	@Autowired AiFacts facts;
	@Autowired JdbcTemplate jdbc;
	@Autowired RiskService risks;
	@Autowired tools.jackson.databind.ObjectMapper json;
	@Autowired org.springframework.transaction.PlatformTransactionManager transactions;
	@Autowired javax.sql.DataSource dataSource;
	@Autowired io.micrometer.core.instrument.MeterRegistry metrics;
	UUID attempt,actor;
	@BeforeEach void create() {
		jdbc.execute("TRUNCATE ai_analysis,investigation_history,investigation,risk_evaluation,risk_attempt,risk_wallet_lock,risk_rule_set,integration_event,operation_view,history_import");
		attempt=UUID.randomUUID();actor=UUID.randomUUID();risks.evaluate(new RiskRequest(attempt,RiskRequest.CommandType.WITHDRAWAL,actor,UUID.randomUUID(),null,null,"RUB","10000000","0",0,Instant.now().minusSeconds(172800)));
	}
	@Test void disabledGenerationSavesHonestImmutableExplanationAndStableRequestKey() {
		UUID key=UUID.randomUUID();var first=jobs.enqueue(attempt,actor,key);assertThat(first).containsEntry("status","FALLBACK").containsEntry("reason","DISABLED");
		assertThat(first.get("report").toString()).contains("RULES_FALLBACK","HIGH_AMOUNT","100000,00");
		assertThat(jobs.enqueue(attempt,actor,key).get("id")).isEqualTo(first.get("id"));
		assertThatThrownBy(()->jobs.enqueue(UUID.randomUUID(),actor,key)).isInstanceOf(com.kamerzing.payment_intelligence.common.web.ApiException.class);
		assertThatThrownBy(()->jdbc.update("DELETE FROM ai_analysis")).isInstanceOf(org.springframework.dao.DataAccessException.class);
		assertThatThrownBy(()->jdbc.update("UPDATE ai_analysis SET report='{}'")).isInstanceOf(org.springframework.dao.DataAccessException.class);
	}
	@Test void concurrentRetriesPersistOneReportAndDoNotChangeRiskOrInvestigations() throws Exception {
		UUID key=UUID.randomUUID();try(var pool=Executors.newFixedThreadPool(4)) {
			var results=new ArrayList<Future<Object>>();for(int i=0;i<4;i++)results.add(pool.submit(()->jobs.enqueue(attempt,actor,key).get("id")));
			var ids=new HashSet<Object>();for(var result:results)ids.add(result.get(15,TimeUnit.SECONDS));assertThat(ids).hasSize(1);
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM risk_attempt",Long.class)).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT count(*) FROM investigation",Long.class)).isEqualTo(1);
		assertThat(jobs.history(attempt).toString()).contains("FALLBACK");
	}
	@Test void interruptedWorkerTerminatesWithSavedRulesExplanation() {
		var snapshot=facts.snapshot(attempt);UUID id=UUID.randomUUID();var json=tools.jackson.databind.json.JsonMapper.builder().build();
		jdbc.update("INSERT INTO ai_analysis(id,attempt_id,actor_id,request_key,status,facts,report,metadata,lease_until) VALUES(?,?,?,?,'RUNNING',?,?, '{}',clock_timestamp()-interval '1 second')",id,attempt,actor,UUID.randomUUID(),json.writeValueAsString(snapshot),json.writeValueAsString(AiReport.fallback(snapshot)));
		assertThat(jobs.claim()).isEmpty();assertThat(jobs.item(id)).containsEntry("status","FALLBACK").containsEntry("reason","INTERRUPTED");
	}
	@Test void queueCapacityAndOneActiveAttemptAreEnforcedUnderDatabaseLock() {
		var settings=new AiSettings(true,"http://127.0.0.1:1","a".repeat(64),"0.33.3",1,1,"http://127.0.0.1:1","");
		var enabled=new AiRepository(jdbc,json,facts,settings);var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
		UUID key=UUID.randomUUID();var first=tx.execute(s->enabled.enqueue(attempt,actor,key));
		assertThat(first).containsEntry("status","QUEUED");assertThat(tx.execute(s->enabled.enqueue(attempt,actor,key)).get("id")).isEqualTo(first.get("id"));
		assertThatThrownBy(()->tx.execute(s->enabled.enqueue(attempt,actor,UUID.randomUUID()))).isInstanceOfSatisfying(com.kamerzing.payment_intelligence.common.web.ApiException.class,e->assertThat(e.code()).isEqualTo("AI_ALREADY_RUNNING"));
		assertThatThrownBy(()->tx.execute(s->enabled.enqueue(UUID.randomUUID(),actor,UUID.randomUUID()))).isInstanceOfSatisfying(com.kamerzing.payment_intelligence.common.web.ApiException.class,e->assertThat(e.code()).isEqualTo("AI_QUEUE_FULL"));
	}
	@Test void databaseSessionLockAllowsOnlyOneWorkerAndModelRunsOutsideTransaction() throws Exception {
		var settings=new AiSettings(true,"http://127.0.0.1:1","a".repeat(64),"0.33.3",1,1,"http://127.0.0.1:1","");
		var enabled=new AiRepository(jdbc,json,facts,settings);var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
		tx.execute(s->enabled.enqueue(attempt,actor,UUID.randomUUID()));
		var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new java.util.concurrent.atomic.AtomicInteger();
		var authorization=org.mockito.Mockito.mock(AnalystAuthorization.class);
		try(var model=new OllamaClient(settings,json){@Override public Generated generate(AiFacts.Snapshot snapshot,List<AiFacts.Fact> selected){
			assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();calls.incrementAndGet();entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){throw new RuntimeException(e);}return new Generated(AiReport.fallback(snapshot),Map.of("test",true));
		}};var pool=Executors.newFixedThreadPool(2)) {
			var one=new AiWorker(settings,jobs,new AiTools(authorization),model,dataSource,metrics);var two=new AiWorker(settings,jobs,new AiTools(authorization),model,dataSource,metrics);
			var first=pool.submit(one::tick);assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();pool.submit(two::tick).get(5,TimeUnit.SECONDS);release.countDown();first.get(5,TimeUnit.SECONDS);
			assertThat(calls).hasValue(1);assertThat(jdbc.queryForObject("SELECT status FROM ai_analysis",String.class)).isEqualTo("SUCCEEDED");
		}
	}
}
