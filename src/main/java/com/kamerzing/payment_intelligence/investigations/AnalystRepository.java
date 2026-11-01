package com.kamerzing.payment_intelligence.investigations;

import java.time.Instant;
import java.util.*;
import com.kamerzing.payment_intelligence.common.MinorUnits;
import com.kamerzing.payment_intelligence.common.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.*;

@Repository
public class AnalystRepository {
	private final JdbcTemplate jdbc;
	private final NamedParameterJdbcTemplate named;
	private final ObjectMapper json;
	public AnalystRepository(JdbcTemplate jdbc,ObjectMapper json) { this.jdbc=jdbc; this.named=new NamedParameterJdbcTemplate(jdbc); this.json=json; }
	public record Filter(UUID query,UUID wallet,String decision,String command_type,Instant from,Instant to,int limit,int offset) {
		public void validate() {
			if(limit<1 || limit>100 || offset<0 || offset>10000 || (decision!=null && !Set.of("ALLOW","DENY").contains(decision))
				|| (command_type!=null && !Set.of("TRANSFER","WITHDRAWAL","CAPTURE_TRANSFER","CAPTURE_WITHDRAWAL").contains(command_type))
				|| (from!=null && to!=null && !from.isBefore(to))) throw MinorUnits.invalid();
		}
	}
	private static final String ATTEMPTS="""
		FROM risk_attempt a JOIN LATERAL (SELECT response,decision FROM risk_evaluation WHERE attempt_id=a.attempt_id
		ORDER BY (decision='DENY') DESC,evaluated_at DESC,decision_id DESC LIMIT 1) e ON true
		WHERE (:query::uuid IS NULL OR a.attempt_id=:query OR a.wallet_id=:query OR a.target_wallet_id=:query
		 OR (a.command::jsonb->>'initiator_id')=:query::text OR EXISTS(SELECT 1 FROM operation_view o
		 WHERE o.payload::jsonb->>'attempt_id'=a.attempt_id::text AND o.operation_id=:query))
		AND (:wallet::uuid IS NULL OR a.wallet_id=:wallet)
		AND (:decision::text IS NULL OR e.decision=:decision)
		AND (:command_type::text IS NULL OR a.command_type=:command_type)
		AND (:from::timestamptz IS NULL OR a.first_seen_at>=:from::timestamptz)
		AND (:to::timestamptz IS NULL OR a.first_seen_at<:to::timestamptz)
		""";
	public Map<String,Object> attempts(Filter f) {
		f.validate(); var p=new HashMap<String,Object>(); p.put("query",f.query());p.put("wallet",f.wallet());p.put("decision",f.decision());
		p.put("command_type",f.command_type());p.put("from",f.from()==null?null:f.from().toString());p.put("to",f.to()==null?null:f.to().toString());p.put("limit",f.limit());p.put("offset",f.offset());
		var rows=named.query("SELECT a.attempt_id,a.command,a.first_seen_at,e.response "+ATTEMPTS+" ORDER BY a.first_seen_at DESC,a.attempt_id LIMIT :limit OFFSET :offset",p,
			(rs,row)->Map.of("attempt_id",rs.getObject(1,UUID.class),"command",json.readTree(rs.getString(2)),"first_seen_at",rs.getTimestamp(3).toInstant(),"decision",json.readTree(rs.getString(4))));
		long total=named.queryForObject("SELECT count(*) "+ATTEMPTS,p,Long.class);
		return Map.of("items",rows,"total",total,"limit",f.limit(),"offset",f.offset());
	}
	public Map<String,Object> dashboard() {
		return Map.of("attempts",jdbc.queryForObject("SELECT count(*) FROM risk_attempt",Long.class),
			"denied",jdbc.queryForObject("SELECT count(*) FROM risk_evaluation WHERE decision='DENY'",Long.class),
			"operations",jdbc.queryForObject("SELECT count(*) FROM operation_view",Long.class),
			"open_investigations",jdbc.queryForObject("SELECT count(*) FROM investigation WHERE status<>'CLOSED'",Long.class),
			"last_event_at",jdbc.queryForObject("SELECT COALESCE(max(received_at)::text,'') FROM integration_event",String.class));
	}
	public Map<String,Object> attempt(UUID id) {
		var rows=jdbc.query("SELECT command,first_seen_at FROM risk_attempt WHERE attempt_id=?",(rs,row)->Map.<String,Object>of(
			"attempt_id",id,"command",json.readTree(rs.getString(1)),"first_seen_at",rs.getTimestamp(2).toInstant()),id);
		if(rows.isEmpty()) throw missing();
		var result=new LinkedHashMap<>(rows.getFirst());
		result.put("evaluations",jdbc.query("SELECT response,facts FROM risk_evaluation WHERE attempt_id=? ORDER BY evaluated_at DESC,decision_id DESC",
			(rs,row)->Map.of("decision",json.readTree(rs.getString(1)),"facts",json.readTree(rs.getString(2))),id));
		result.put("outcomes",jdbc.query("SELECT payload FROM integration_event WHERE attempt_id=? ORDER BY source_position",
			(rs,row)->json.readTree(rs.getString(1)),id));
		result.put("related_operations",jdbc.query("""
			SELECT o.payload FROM operation_view o JOIN risk_attempt a ON a.attempt_id=?
			WHERE o.payload::jsonb->'operation'->>'source_wallet_id'=a.wallet_id::text OR o.payload::jsonb->'operation'->>'target_wallet_id'=a.wallet_id::text
			ORDER BY (o.payload::jsonb->'operation'->>'posted_at')::timestamptz DESC,o.operation_id LIMIT 20
			""",(rs,row)->json.readTree(rs.getString(1)),id));
		result.put("investigations",jdbc.query("SELECT row_to_json(i)::text FROM investigation i WHERE attempt_id=?",(rs,row)->json.readTree(rs.getString(1)),id));
		return result;
	}
	public Map<String,Object> cases(String status,int limit,int offset) {
		if(limit<1 || limit>100 || offset<0 || offset>10000 || (status!=null && !Set.of("OPEN","IN_REVIEW","CLOSED").contains(status))) throw MinorUnits.invalid();
		return Map.of("items",jdbc.query("SELECT row_to_json(i)::text FROM investigation i WHERE (?::text IS NULL OR status=?) ORDER BY updated_at DESC,id LIMIT ? OFFSET ?",
			(rs,row)->json.readTree(rs.getString(1)),status,status,limit,offset),"total",jdbc.queryForObject("SELECT count(*) FROM investigation WHERE (?::text IS NULL OR status=?)",Long.class,status,status),"limit",limit,"offset",offset);
	}
	public Map<String,Object> investigation(UUID id) {
		var rows=jdbc.query("SELECT row_to_json(i)::text FROM investigation i WHERE id=?",(rs,row)->json.readTree(rs.getString(1)),id);
		if(rows.isEmpty()) throw missing();
		return Map.of("investigation",rows.getFirst(),"history",jdbc.query("SELECT row_to_json(h)::text FROM investigation_history h WHERE investigation_id=? ORDER BY version",(rs,row)->json.readTree(rs.getString(1)),id));
	}
	public void update(UUID id,UUID actor,Change change) {
		if(change==null || change.version()==null || change.version()<1 || change.status()==null || !Set.of("OPEN","IN_REVIEW","CLOSED").contains(change.status())
			|| ("CLOSED".equals(change.status())!=(change.conclusion()!=null)) || (change.conclusion()!=null && !Set.of("SUSPICIOUS","LEGITIMATE","INSUFFICIENT_DATA").contains(change.conclusion()))
			|| change.comment()==null || change.comment().length()>2000 || change.comment().isBlank()) throw MinorUnits.invalid();
		int count=jdbc.update("UPDATE investigation SET status=?,conclusion=?,version=version+1,updated_at=clock_timestamp() WHERE id=? AND version=?",
			change.status(),change.conclusion(),id,change.version());
		if(count!=1) {
			if(jdbc.queryForObject("SELECT count(*) FROM investigation WHERE id=?",Long.class,id)==0) throw missing();
			throw new ApiException(HttpStatus.CONFLICT,"INVESTIGATION_CHANGED","Обновите расследование: другой аналитик уже внёс изменения.");
		}
		jdbc.update("INSERT INTO investigation_history(investigation_id,actor_id,status,conclusion,comment,version) VALUES(?,?,?,?,?,?)",
			id,actor,change.status(),change.conclusion(),change.comment(),change.version()+1);
	}
	public record Change(@tools.jackson.databind.annotation.JsonDeserialize(using=com.kamerzing.payment_intelligence.common.StrictIntegerDeserializer.class) Integer version,String status,String conclusion,String comment) { }
	private static ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND","Запись не найдена."); }
}
