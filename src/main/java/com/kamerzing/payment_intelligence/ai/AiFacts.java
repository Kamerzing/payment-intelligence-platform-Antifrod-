package com.kamerzing.payment_intelligence.ai;

import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.kamerzing.payment_intelligence.investigations.AnalystService;

/** Typed, bounded facts only. Comments and arbitrary user text never enter the prompt. */
@Component
public class AiFacts {
	public record Fact(String id,String tool,String text) { }
	public record Snapshot(UUID attempt_id,String decision,int score,List<Fact> facts) { }
	private final AnalystService analyst;
	private final ObjectMapper json;
	public AiFacts(AnalystService analyst,ObjectMapper json) { this.analyst=analyst;this.json=json; }
	public Snapshot snapshot(UUID attempt) {
		JsonNode detail=json.valueToTree(analyst.attempt(attempt));
		var decision=detail.path("evaluations").get(0).path("decision");
		// A final denial wins over an earlier permit, regardless of timestamp ordering.
		for(var e:detail.path("evaluations")) if("DENY".equals(e.path("decision").path("decision").asText())) { decision=e.path("decision");break; }
		var command=detail.path("command");var facts=new ArrayList<Fact>();
		facts.add(new Fact("attempt:"+attempt,"read_attempt","Попытка "+attempt+". Сумма: "+rubles(command.path("amount_minor").asText())+". Валюта RUB."));
		String result=decision.path("decision").asText();int score=decision.path("score").asInt();
		facts.add(new Fact("decision:"+decision.path("decision_id").asText(),"read_attempt",("DENY".equals(result)?"Правила отклонили попытку":"Правила разрешили попытку")+". Оценка "+score+" из 100; это не вероятность мошенничества."));
		JsonNode savedFacts=null;for(var e:detail.path("evaluations")) if(e.path("decision").path("decision_id").equals(decision.path("decision_id"))) savedFacts=e.path("facts");
		for(var rule:decision.path("triggered_rules")) {
			String context=switch(rule.asText()) {
				case "HIGH_VELOCITY"->" Уникальных расходных попыток в сохранённом окне: "+savedFacts.path("unique_attempts").asLong()+".";
				case "RECIPIENT_FANOUT"->" Разных получателей в сохранённом окне: "+savedFacts.path("distinct_recipients").asLong()+".";
				case "NEW_ACCOUNT_LARGE_AMOUNT"->" Возраст в момент оценки: около "+age(savedFacts.path("account_age").asText())+".";
				default->" Сумма попытки: "+rubles(command.path("amount_minor").asText())+".";
			};
			facts.add(new Fact("rule:"+rule.asText()+":"+decision.path("rule_set_version").asText(),"read_rules",ruleText(rule.asText())+context));
		}
		int count=0;for(var operation:detail.path("related_operations")) {
			if(count++>=5) break;var op=operation.path("operation");
			facts.add(new Fact("operation:"+op.path("operation_id").asText(),"read_operations","Связанная проведённая операция "+op.path("operation_id").asText()+". Сумма: "+rubles(op.path("amount_minor").asText())+". Связь по кошельку не доказывает причинную связь с попыткой."));
		}
		return new Snapshot(attempt,result,score,List.copyOf(facts));
	}
	static String rubles(String value) { long minor=Long.parseLong(value);return (minor/100)+","+String.format(java.util.Locale.ROOT,"%02d",minor%100)+" ₽"; }
	private static String age(String value) { long seconds=java.time.Duration.parse(value).toSeconds();if(seconds==0)return "1 с";return (seconds/86400>0?(seconds/86400)+" дн ":"")+(seconds%86400/3600>0?(seconds%86400/3600)+" ч ":"")+(seconds%3600/60>0?(seconds%3600/60)+" мин ":"")+(seconds%60)+" с"; }
	private static String ruleText(String rule) { return switch(rule) {
		case "HIGH_AMOUNT"->"Сработало правило крупной суммы HIGH_AMOUNT.";
		case "HIGH_VELOCITY"->"Сработало правило частых расходных попыток HIGH_VELOCITY.";
		case "RECIPIENT_FANOUT"->"Сработало правило нескольких получателей RECIPIENT_FANOUT.";
		case "NEW_ACCOUNT_LARGE_AMOUNT"->"Сработало правило крупной суммы нового пользователя NEW_ACCOUNT_LARGE_AMOUNT.";
		default->throw new IllegalArgumentException("Unknown saved rule.");
	}; }
}
