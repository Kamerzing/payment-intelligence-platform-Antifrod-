package com.kamerzing.payment_intelligence.risk;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="CoreBearer")
public class RiskController {
	private final RiskService service;
	private final io.micrometer.core.instrument.MeterRegistry metrics;
	public RiskController(RiskService service, io.micrometer.core.instrument.MeterRegistry metrics) { this.service = service; this.metrics = metrics; }
	@PostMapping("/internal/v1/risk/evaluations")
	public RiskResponse evaluate(@Valid @RequestBody RiskRequest request) {
		var sample = io.micrometer.core.instrument.Timer.start(metrics);
		String result = "ERROR";
		try { var response = service.evaluate(request); result = response.decision(); return response; }
		finally {
			metrics.counter("pip.risk.evaluations", "result", result).increment();
			sample.stop(metrics.timer("pip.risk.duration", "result", result));
		}
	}
}
