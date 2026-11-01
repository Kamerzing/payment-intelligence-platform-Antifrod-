package com.kamerzing.payment_intelligence;

import java.time.Duration;
import com.kamerzing.payment_intelligence.risk.RiskRules;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RiskRulesTests {
	static RiskRules rules(String version) {
		return new RiskRules(version,10000000,70,5,Duration.ofSeconds(60),80,3,Duration.ofMinutes(10),40,
				Duration.ofHours(24),2500000,30,70,Duration.ofSeconds(1));
	}
	@Test void exactAmountAndAgeBoundariesAndScoreCap() {
		var rules=rules("test-v1");
		assertThat(rules.evaluate(9999999,1,1,Duration.ofDays(2)).score()).isZero();
		assertThat(rules.evaluate(10000000,1,1,Duration.ofDays(2)).decision()).isEqualTo("DENY");
		assertThat(rules.evaluate(2499999,1,1,Duration.ZERO).score()).isZero();
		assertThat(rules.evaluate(2500000,1,1,Duration.ofHours(24).minusNanos(1)).score()).isEqualTo(30);
		assertThat(rules.evaluate(2500000,1,1,Duration.ofHours(24)).score()).isZero();
		assertThat(rules.evaluate(10000000,5,3,Duration.ZERO).score()).isEqualTo(100);
		assertThat(rules.evaluate(2500000,1,3,Duration.ZERO).score()).isEqualTo(70);
		assertThat(rules.evaluate(2500000,1,3,Duration.ZERO).decision()).isEqualTo("DENY");
	}
	@Test void fifthAttemptAndThirdRecipientAreIncluded() {
		var rules=rules("test-v1");
		assertThat(rules.evaluate(1,4,2,Duration.ofDays(2)).rules()).isEmpty();
		assertThat(rules.evaluate(1,5,2,Duration.ofDays(2)).rules()).containsExactly("HIGH_VELOCITY");
		assertThat(rules.evaluate(1,1,3,Duration.ofDays(2)).rules()).containsExactly("RECIPIENT_FANOUT");
	}
}
