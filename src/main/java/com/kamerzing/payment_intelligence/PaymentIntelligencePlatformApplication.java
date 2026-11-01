package com.kamerzing.payment_intelligence;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class PaymentIntelligencePlatformApplication {
	public static void main(String[] args) {
		if (java.util.Arrays.asList(args).contains("--import-history") || java.util.Arrays.asList(args).contains("--rebuild-view")) {
			try { com.kamerzing.payment_intelligence.integration.HistoryImportCommand.run(args); }
			catch (Exception exception) { throw new IllegalStateException("History maintenance failed: " + exception.getClass().getSimpleName()); }
			return;
		}
		SpringApplication.run(PaymentIntelligencePlatformApplication.class, args);
	}
}
