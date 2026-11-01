package com.kamerzing.payment_intelligence.ai;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import com.kamerzing.payment_intelligence.common.web.ApiException;

@Repository
public class AiRepository {
	public record Job(UUID id,UUID attempt,UUID actor,AiFacts.Snapshot snapshot) { }
	private final JdbcTemplate jdbc;
	private final ObjectMapper json;
	private final AiFacts facts;
	private final AiSettings settings;
	public AiRepository(JdbcTemplate jdbc,ObjectMapper json,AiFacts facts,AiSettings settings) { this.jdbc=jdbc;this.json=json;this.facts=facts;this.settings=settings; }
	@Transactional
	public Map<String,Object> enqueue(UUID attempt,UUID actor,UUID key) {
		if(key==null) throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_REQUEST","Укажите ключ запроса анализа.");
		jdbc.execute("SELECT pg_advisory_xact_lock(708102607)");
		var saved=jdbc.query("SELECT id,attempt_id FROM ai_analysis WHERE actor_id=? AND request_key=?",(rs,row)->Map.of("id",rs.getObject(1,UUID.class),"attempt",rs.getObject(2,UUID.class)),actor,key);
		if(!saved.isEmpty()) { if(!attempt.equals(saved.getFirst().get("attempt"))) throw new ApiException(HttpStatus.CONFLICT,"AI_KEY_CONFLICT","Этот ключ относится к другой попытке.");return item((UUID)saved.getFirst().get("id")); }
		var active=jdbc.query("SELECT id FROM ai_analysis WHERE attempt_id=? AND status IN ('QUEUED','RUNNING')",(rs,row)->rs.getObject(1,UUID.class),attempt);
		if(!active.isEmpty()) throw new ApiException(HttpStatus.CONFLICT,"AI_ALREADY_RUNNING","Анализ этой попытки уже выполняется. Обновите отчёты.");
		if(settings.enabled() && jdbc.queryForObject("SELECT count(*) FROM ai_analysis WHERE status IN ('QUEUED','RUNNING')",Integer.class)>=settings.queueCapacity()) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"AI_QUEUE_FULL","Очередь ИИ заполнена. Повторите позже с тем же ключом.");
		var snapshot=facts.snapshot(attempt);UUID id=UUID.randomUUID();
		jdbc.update("INSERT INTO ai_analysis(id,attempt_id,actor_id,request_key,status,reason,facts,report,metadata,finished_at) VALUES (?,?,?,?,?,?,?,?,?,CASE WHEN ? THEN clock_timestamp() ELSE null END)",
			id,attempt,actor,key,settings.enabled()?"QUEUED":"FALLBACK",settings.enabled()?null:"DISABLED",json.writeValueAsString(snapshot),json.writeValueAsString(AiReport.fallback(snapshot)),json.writeValueAsString(Map.of("prompt_version",AiSettings.PROMPT_VERSION,"schema_version",1)),!settings.enabled());
		return item(id);
	}
	public Map<String,Object> history(UUID attempt) { facts.snapshot(attempt);return Map.of("items",jdbc.query("SELECT id FROM ai_analysis WHERE attempt_id=? ORDER BY created_at DESC,id DESC LIMIT 10",(rs,row)->item(rs.getObject(1,UUID.class)),attempt),"enabled",settings.enabled()); }
	public Map<String,Object> item(UUID id) {
		return jdbc.queryForObject("SELECT * FROM ai_analysis WHERE id=?",(rs,row)->{
			var result=new LinkedHashMap<String,Object>();result.put("id",id);result.put("attempt_id",rs.getObject("attempt_id",UUID.class));result.put("actor_id",rs.getObject("actor_id",UUID.class));result.put("status",rs.getString("status"));result.put("reason",rs.getString("reason"));result.put("report",json.readTree(rs.getString("report")));result.put("metadata",json.readTree(rs.getString("metadata")));result.put("created_at",rs.getTimestamp("created_at").toInstant());result.put("finished_at",rs.getTimestamp("finished_at")==null?null:rs.getTimestamp("finished_at").toInstant());return result;
		},id);
	}
	@Transactional
	public Optional<Job> claim() {
		// A crashed worker cannot leave a job permanently RUNNING; its saved rules explanation survives.
		jdbc.update("UPDATE ai_analysis SET status='FALLBACK',reason='INTERRUPTED',finished_at=clock_timestamp(),lease_until=null WHERE status='RUNNING' AND lease_until<clock_timestamp()");
		var jobs=jdbc.query("UPDATE ai_analysis SET status='RUNNING',started_at=clock_timestamp(),lease_until=clock_timestamp()+interval '5 minutes' WHERE id=(SELECT id FROM ai_analysis WHERE status='QUEUED' ORDER BY created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED) RETURNING id,attempt_id,actor_id,facts",(rs,row)->new Job(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),json.readValue(rs.getString(4),AiFacts.Snapshot.class)));
		return jobs.stream().findFirst();
	}
	public void disablePending() {
		jdbc.update("UPDATE ai_analysis SET status='FALLBACK',reason='DISABLED',finished_at=clock_timestamp(),lease_until=null WHERE status='QUEUED' OR (status='RUNNING' AND lease_until<clock_timestamp())");
	}
	public void finish(Job job,String status,String reason,Object report,Object metadata) {
		jdbc.update("UPDATE ai_analysis SET status=?,reason=?,report=?,metadata=?,finished_at=clock_timestamp(),lease_until=null WHERE id=? AND status='RUNNING'",status,reason,json.writeValueAsString(report),json.writeValueAsString(metadata),job.id());
	}
}
