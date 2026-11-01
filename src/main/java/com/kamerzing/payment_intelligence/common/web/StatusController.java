package com.kamerzing.payment_intelligence.common.web;

import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StatusController {
	private final ObjectProvider<BuildProperties> build;
	private final boolean aiEnabled;
	public StatusController(ObjectProvider<BuildProperties> build,@org.springframework.beans.factory.annotation.Value("${pip.ai.enabled:false}") boolean aiEnabled) { this.build = build;this.aiEnabled=aiEnabled; }

	@GetMapping("/api/v1/status")
	public PlatformStatus status() {
		var properties = build.getIfAvailable();
		return new PlatformStatus("payment-intelligence-platform",
				properties == null ? "development" : properties.getVersion(), "UP", Instant.now(),
				Map.of("payment_core", "DELIVERY_NOT_VERIFIED", "risk", "RULES_IMPLEMENTED",
						"investigations", "ADMIN_GATEWAY_REQUIRED", "ai", aiEnabled?"LOCAL_MODEL_NOT_VERIFIED":"DISABLED"));
	}

	public record PlatformStatus(String service, String version, String status, Instant timestamp,
			Map<String, String> capabilities) { }
}
