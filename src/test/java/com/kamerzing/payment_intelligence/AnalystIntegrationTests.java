package com.kamerzing.payment_intelligence;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import com.kamerzing.payment_intelligence.investigations.*;
import com.kamerzing.payment_intelligence.integration.*;
import com.kamerzing.payment_intelligence.risk.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

@Tag("integration")
@SpringBootTest(properties={"pip.integration.core-token=test-core-credential-32-characters","pip.integration.analyst-token=test-analyst-credential-32-characters"})
@AutoConfigureMockMvc @ActiveProfiles("test-integration") @Import(PostgresFoundationTests.Database.class)
class AnalystIntegrationTests {
	@Autowired JdbcTemplate jdbc;@Autowired RiskService risks;@Autowired AnalystService analyst;@Autowired EventService events;
	@Autowired MockMvc mvc;@Autowired ObjectMapper json;
	UUID wallet,attempt,actor;
	static final String AUTH="Bearer test-analyst-credential-32-characters";
	@BeforeEach void setup(){jdbc.execute("TRUNCATE ai_analysis,investigation_history,investigation,risk_evaluation,risk_attempt,risk_wallet_lock,risk_rule_set,integration_event,operation_view,history_import");wallet=UUID.randomUUID();attempt=UUID.randomUUID();actor=UUID.randomUUID();}
	RiskRequest command(){return new RiskRequest(attempt,RiskRequest.CommandType.WITHDRAWAL,actor,wallet,null,null,"RUB","10000000","100000",100,Instant.parse("2026-10-01T00:00:00Z"));}
	UUID caseId(){return jdbc.queryForObject("SELECT id FROM investigation WHERE attempt_id=?",UUID.class,attempt);}
	@Test void terminalDenyCreatesExactlyOneCaseAndHistoryAndSurvivesEventRebuild() {
		var decision=risks.evaluate(command());risks.evaluate(command());
		// The user creation timestamp is immutable in actual core requests.
		assertThat(jdbc.queryForObject("SELECT count(*) FROM investigation",Long.class)).isEqualTo(1);
		var event=new CoreEvent(UUID.randomUUID(),"RISK_REJECTED",1,"payment-core","1","1",Instant.now(),attempt,decision.decision_id(),null,command());
		assertThat(events.accept(event).status()).isEqualTo("ACCEPTED");assertThat(events.accept(event).status()).isEqualTo("DUPLICATE");events.rebuild();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM operation_view",Long.class)).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM investigation_history",Long.class)).isEqualTo(1);
		assertThat(analyst.attempt(attempt).get("outcomes").toString()).contains("RISK_REJECTED");
		var original=command();var changed=new RiskRequest(original.attempt_id(),original.command_type(),original.initiator_id(),original.source_wallet_id(),null,null,"RUB","10000001","100000",100,original.user_created_at());
		var conflict=new CoreEvent(UUID.randomUUID(),"RISK_REJECTED",1,"payment-core","2","1",Instant.now(),attempt,decision.decision_id(),null,changed);
		assertThatThrownBy(()->events.accept(conflict)).isInstanceOf(com.kamerzing.payment_intelligence.common.web.ApiException.class);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM integration_event",Long.class)).isEqualTo(1);
	}
	@Test void closesReopensAndPreservesImmutableAuditAndRiskDecision() {
		risks.evaluate(command());UUID id=caseId();
		analyst.update(id,actor,new AnalystRepository.Change(1,"IN_REVIEW",null,"Проверяю источник средств"));
		analyst.update(id,actor,new AnalystRepository.Change(2,"CLOSED","SUSPICIOUS","Требуется дополнительная проверка"));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM investigation_history WHERE actor_id=?",Long.class,actor)).isEqualTo(2);
		assertThatThrownBy(()->analyst.update(id,actor,new AnalystRepository.Change(2,"OPEN",null,"Устаревшая форма"))).isInstanceOf(com.kamerzing.payment_intelligence.common.web.ApiException.class);
		analyst.update(id,actor,new AnalystRepository.Change(3,"IN_REVIEW",null,"Получены новые сведения"));
		assertThat(jdbc.queryForObject("SELECT decision FROM risk_evaluation",String.class)).isEqualTo("DENY");
		assertThatThrownBy(()->jdbc.update("DELETE FROM investigation_history")).isInstanceOf(org.springframework.dao.DataAccessException.class);
		assertThat(jdbc.queryForObject("SELECT conclusion FROM investigation_history WHERE version=3",String.class)).isEqualTo("SUSPICIOUS");
	}
	@Test void concurrentAnalystsCannotLoseChanges() throws Exception {
		risks.evaluate(command());UUID id=caseId();var barrier=new CyclicBarrier(2);
		try(var pool=Executors.newFixedThreadPool(2)) {
			var jobs=new ArrayList<Future<Boolean>>();for(int i=0;i<2;i++) jobs.add(pool.submit(()->{barrier.await(5,TimeUnit.SECONDS);try{analyst.update(id,UUID.randomUUID(),new AnalystRepository.Change(1,"IN_REVIEW",null,"Проверка"));return true;}catch(com.kamerzing.payment_intelligence.common.web.ApiException e){return false;}}));
			assertThat(List.of(jobs.get(0).get(10,TimeUnit.SECONDS),jobs.get(1).get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
		}assertThat(jdbc.queryForObject("SELECT count(*) FROM investigation_history",Long.class)).isEqualTo(2);
	}
	@Test void filtersPaginationDetailsAndMissingDataHaveExplicitResults() {
		risks.evaluate(command());
		var f=new AnalystRepository.Filter(attempt,wallet,"DENY","WITHDRAWAL",Instant.now().minusSeconds(20),Instant.now().plusSeconds(20),20,0);
		assertThat(analyst.attempts(f).get("total")).isEqualTo(1L);
		assertThat(analyst.attempts(new AnalystRepository.Filter(null,null,"ALLOW",null,null,null,20,0)).get("total")).isEqualTo(0L);
		assertThat(analyst.cases("OPEN",20,0).get("total")).isEqualTo(1L);assertThat(analyst.dashboard().get("denied")).isEqualTo(1L);
		assertThatThrownBy(()->analyst.attempt(UUID.randomUUID())).isInstanceOf(com.kamerzing.payment_intelligence.common.web.ApiException.class);
		assertThatThrownBy(()->analyst.update(caseId(),actor,new AnalystRepository.Change(1,"CLOSED",null,"Вывод отсутствует"))).isInstanceOf(com.kamerzing.payment_intelligence.common.web.ApiException.class);
	}
	@Test void browserSessionCoreTokenAndForgedActorCannotOpenAnalystApi() throws Exception {
		mvc.perform(get("/internal/v1/analyst/dashboard").with(user("fake").roles("ADMIN")).header("X-Analyst-Id",actor)).andExpect(status().isUnauthorized());
		mvc.perform(get("/internal/v1/analyst/dashboard").header("Authorization","Bearer test-core-credential-32-characters").header("X-Analyst-Id",actor)).andExpect(status().isUnauthorized());
		mvc.perform(get("/internal/v1/analyst/dashboard").header("Authorization",AUTH)).andExpect(status().isUnauthorized());
		mvc.perform(get("/internal/v1/analyst/dashboard").header("Authorization",AUTH).header("X-Analyst-Id",actor)).andExpect(status().isOk());
		risks.evaluate(command());
		mvc.perform(post("/internal/v1/analyst/investigations/"+caseId()).header("Authorization",AUTH).header("X-Analyst-Id",actor)
			.contentType("application/json").content("{\"version\":1,\"status\":\"IN_REVIEW\",\"conclusion\":null,\"comment\":\"Проверка\",\"actor_id\":\""+UUID.randomUUID()+"\"}"))
			.andExpect(status().isBadRequest());
	}
}
