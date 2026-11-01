package com.kamerzing.payment_intelligence.ai;

import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Model chooses evidence and advice; all factual text is rendered from the saved catalog. */
public final class AiReport {
	public static final Map<String,String> ADVICE=Map.of(
		"REVIEW_CONTEXT","Проверьте контекст попытки и причины правил перед выводом расследования.",
		"COMPARE_HISTORY","Сопоставьте попытку с доступной историей кошелька.",
		"REQUEST_INFORMATION","При необходимости запросите дополнительные сведения у участника через принятый процесс.");
	public static final Map<String,String> LIMITS=Map.of(
		"RULES_NOT_PROOF","Срабатывание правил не доказывает мошенничество.",
		"LIMITED_HISTORY","История ограничена снимком и пятью связанными операциями. Геолокация, устройство и намерения неизвестны.",
		"NO_AUTOMATIC_ACTION","Рекомендации не исполняются автоматически и не меняют историческое решение.");
	private AiReport() { }
	public static JsonNode schema(ObjectMapper json,AiFacts.Snapshot facts) {
		return json.valueToTree(Map.of("type","object","additionalProperties",false,"required",List.of("evidence","recommendations","limitations"),"properties",Map.of(
			"evidence",array(facts.facts().stream().map(AiFacts.Fact::id).toList(),facts.facts().size()),
			"recommendations",array(new TreeSet<>(ADVICE.keySet()),3),"limitations",array(new TreeSet<>(LIMITS.keySet()),3))));
	}
	private static Map<String,Object> array(Collection<String> values,int max) { return Map.of("type","array","minItems",1,"maxItems",max,"uniqueItems",true,"items",Map.of("type","string","enum",values)); }
	public static Map<String,Object> validate(JsonNode output,AiFacts.Snapshot facts) {
		var fields=new HashSet<String>();output.propertyNames().forEach(fields::add);
		if(!output.isObject() || !fields.equals(Set.of("evidence","recommendations","limitations"))) throw new IllegalArgumentException("Invalid report schema.");
		var allowed=new LinkedHashMap<String,AiFacts.Fact>();for(var f:facts.facts()) allowed.put(f.id(),f);
		var evidence=selection(output.path("evidence"),allowed.keySet(),allowed.size());
		for(var f:facts.facts()) if(!f.tool().equals("read_operations") && !evidence.contains(f.id())) throw new IllegalArgumentException("Report omitted required evidence.");
		var advice=selection(output.path("recommendations"),ADVICE.keySet(),3);var limits=selection(output.path("limitations"),LIMITS.keySet(),3);
		if(!limits.equals(new LinkedHashSet<>(LIMITS.keySet()))) throw new IllegalArgumentException("Report omitted limitations.");
		return render("LOCAL_MODEL",facts,evidence,advice);
	}
	private static LinkedHashSet<String> selection(JsonNode node,Set<String> allowed,int max) {
		if(!node.isArray() || node.isEmpty() || node.size()>max) throw new IllegalArgumentException("Invalid selection.");
		var result=new LinkedHashSet<String>();for(var item:node) if(!item.isString() || !allowed.contains(item.asText()) || !result.add(item.asText())) throw new IllegalArgumentException("Unknown or duplicate evidence.");return result;
	}
	public static Map<String,Object> fallback(AiFacts.Snapshot facts) { return render("RULES_FALLBACK",facts,facts.facts().stream().filter(f->!f.tool().equals("read_operations")).map(AiFacts.Fact::id).toList(),List.of("REVIEW_CONTEXT")); }
	private static Map<String,Object> render(String source,AiFacts.Snapshot facts,Collection<String> evidence,Collection<String> advice) {
		return Map.of("source",source,"summary","DENY".equals(facts.decision())?"Попытка отклонена правилами риска. Вывод о мошенничестве требует расследования.":"Попытка разрешена правилами риска. Это не подтверждает проведение платежа.",
			"evidence",facts.facts().stream().filter(f->evidence.contains(f.id())).toList(),"recommendations",advice.stream().map(code->Map.of("code",code,"text",ADVICE.get(code))).toList(),
			"limitations",new TreeMap<>(LIMITS).values(),"automatic_actions",false);
	}
}
