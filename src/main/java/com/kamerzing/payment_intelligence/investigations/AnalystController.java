package com.kamerzing.payment_intelligence.investigations;

import java.time.Instant;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1/analyst")
public class AnalystController {
	private final AnalystService service;
	public AnalystController(AnalystService service) { this.service=service; }
	@GetMapping("/dashboard") public Map<String,Object> dashboard() { return service.dashboard(); }
	@GetMapping("/attempts") public Map<String,Object> attempts(@RequestParam(required=false) UUID query,@RequestParam(required=false) UUID wallet,
		@RequestParam(required=false) String decision,@RequestParam(required=false) String command_type,@RequestParam(required=false) Instant from,@RequestParam(required=false) Instant to,
		@RequestParam(defaultValue="20") int limit,@RequestParam(defaultValue="0") int offset) {
		return service.attempts(new AnalystRepository.Filter(query,wallet,decision,command_type,from,to,limit,offset));
	}
	@GetMapping("/attempts/{id}") public Map<String,Object> attempt(@PathVariable UUID id) { return service.attempt(id); }
	@GetMapping("/investigations") public Map<String,Object> cases(@RequestParam(required=false) String status,@RequestParam(defaultValue="20") int limit,@RequestParam(defaultValue="0") int offset) { return service.cases(status,limit,offset); }
	@GetMapping("/investigations/{id}") public Map<String,Object> investigation(@PathVariable UUID id) { return service.investigation(id); }
	@PostMapping("/investigations/{id}") public Map<String,Object> update(@PathVariable UUID id,@RequestHeader("X-Analyst-Id") UUID actor,@RequestBody AnalystRepository.Change change) { return service.update(id,actor,change); }
}
