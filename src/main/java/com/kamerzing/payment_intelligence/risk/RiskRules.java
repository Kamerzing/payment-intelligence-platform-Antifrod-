package com.kamerzing.payment_intelligence.risk;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@ConfigurationProperties("pip.risk.rules")
public record RiskRules(String version, long highAmountMinor, int highAmountPoints,
		int velocityLimit, Duration velocityWindow, int velocityPoints,
		int recipientLimit, Duration recipientWindow, int recipientPoints,
		Duration newAccountAge, long newAccountAmountMinor, int newAccountPoints,
		int denyScore, Duration allowTtl) {
	public RiskRules {
		if (version == null || !version.matches("[A-Za-z0-9._-]{1,64}") || highAmountMinor <= 0
				|| newAccountAmountMinor <= 0 || velocityLimit < 1 || recipientLimit < 1
				|| denyScore < 1 || denyScore > 100 || !points(highAmountPoints) || !points(velocityPoints)
				|| !points(recipientPoints) || !points(newAccountPoints) || !duration(velocityWindow)
				|| !duration(recipientWindow) || !duration(newAccountAge) || !duration(allowTtl))
			throw new IllegalArgumentException("Invalid versioned risk configuration.");
	}
	private static boolean points(int points) { return points >= 0 && points <= 100; }
	private static boolean duration(Duration duration) { return duration != null && !duration.isNegative() && !duration.isZero(); }
	public Result evaluate(long amount, long attempts, long recipients, Duration age) {
		var rules = new ArrayList<String>();
		int score = 0;
		if (amount >= highAmountMinor) { score += highAmountPoints; rules.add("HIGH_AMOUNT"); }
		if (attempts >= velocityLimit) { score += velocityPoints; rules.add("HIGH_VELOCITY"); }
		if (recipients >= recipientLimit) { score += recipientPoints; rules.add("RECIPIENT_FANOUT"); }
		if (age.compareTo(newAccountAge) < 0 && amount >= newAccountAmountMinor) { score += newAccountPoints; rules.add("NEW_ACCOUNT_LARGE_AMOUNT"); }
		score = Math.min(100, score);
		return new Result(score >= denyScore ? "DENY" : "ALLOW", score, List.copyOf(rules));
	}
	public record Result(String decision, int score, List<String> rules) { }

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(RiskRules.class)
	static class Registration { }
}
