package com.kamerzing.payment_intelligence.integration;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="CoreBearer")
public class EventController {
	private final EventService service;
	private final io.micrometer.core.instrument.MeterRegistry metrics;
	public EventController(EventService service, io.micrometer.core.instrument.MeterRegistry metrics) { this.service = service; this.metrics = metrics; }
	@PostMapping("/internal/v1/events")
	public EventService.Receipt accept(@RequestBody CoreEvent event) {
		String result = "ERROR";
		try { var receipt = service.accept(event); result = receipt.status(); return receipt; }
		finally { metrics.counter("pip.integration.events", "result", result).increment(); }
	}
}
