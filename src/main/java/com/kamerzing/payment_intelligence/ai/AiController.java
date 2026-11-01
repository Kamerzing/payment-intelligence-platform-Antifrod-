package com.kamerzing.payment_intelligence.ai;

import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1/analyst/attempts/{attempt}/ai-analyses")
public class AiController {
	private final AiRepository jobs;
	public AiController(AiRepository jobs) { this.jobs=jobs; }
	public record Request(UUID request_key) { }
	@GetMapping public Map<String,Object> history(@PathVariable UUID attempt) { return jobs.history(attempt); }
	@PostMapping public Map<String,Object> enqueue(@PathVariable UUID attempt,@RequestHeader("X-Analyst-Id") UUID actor,@RequestBody Request request) { return jobs.enqueue(attempt,actor,request.request_key()); }
}
