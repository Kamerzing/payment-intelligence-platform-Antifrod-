package com.kamerzing.payment_intelligence.ai;

import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class AiTools {
	private final AnalystAuthorization authorization;
	public AiTools(AnalystAuthorization authorization) { this.authorization=authorization; }
	public List<AiFacts.Fact> read(String tool,UUID actor,UUID attempt,AiFacts.Snapshot snapshot) {
		if(!Set.of("read_attempt","read_rules","read_operations").contains(tool) || !attempt.equals(snapshot.attempt_id())) throw new AiFailure("INVALID_TOOL_SCOPE");
		authorization.check(actor);
		return snapshot.facts().stream().filter(f->f.tool().equals(tool)).toList();
	}
}
