package com.kamerzing.payment_intelligence.ai;

import java.util.*;
import javax.sql.DataSource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import io.micrometer.core.instrument.MeterRegistry;

@Component
public class AiWorker {
	private final AiSettings settings;
	private final AiRepository jobs;
	private final AiTools tools;
	private final OllamaClient model;
	private final DataSource dataSource;
	private final MeterRegistry metrics;
	public AiWorker(AiSettings settings,AiRepository jobs,AiTools tools,OllamaClient model,DataSource dataSource,MeterRegistry metrics) { this.settings=settings;this.jobs=jobs;this.tools=tools;this.model=model;this.dataSource=dataSource;this.metrics=metrics; }
	@Scheduled(fixedDelayString="${pip.ai.poll-ms:1000}")
	public void tick() {
		// Session lock spans generation without holding a database transaction or a risk wallet lock.
		try(var connection=dataSource.getConnection();var statement=connection.createStatement()) {
			try(var lock=statement.executeQuery("SELECT pg_try_advisory_lock(708102608)")) { lock.next();if(!lock.getBoolean(1)) return; }
			try { if(settings.enabled()) jobs.claim().ifPresent(this::run);else jobs.disablePending(); } finally { statement.execute("SELECT pg_advisory_unlock(708102608)"); }
		} catch(Exception unavailable) { /* DB outage: durable queue remains; bounded next tick retries. */ }
	}
	void run(AiRepository.Job job) {
		long start=System.nanoTime();String result="fallback";var trace=new ArrayList<String>();
		try {
			var facts=new ArrayList<AiFacts.Fact>();
			for(String tool:List.of("read_attempt","read_rules","read_operations")) { facts.addAll(tools.read(tool,job.actor(),job.attempt(),job.snapshot()));trace.add(tool); }
			var generated=model.generate(job.snapshot(),facts);
			// Do not publish after a role revocation that happened during generation.
			tools.read("read_attempt",job.actor(),job.attempt(),job.snapshot());
			var metadata=new LinkedHashMap<>(generated.metadata());metadata.put("tools",trace);
			jobs.finish(job,"SUCCEEDED",null,generated.report(),metadata);result="succeeded";
		} catch(Exception failure) {
			String reason=failure instanceof AiFailure?failure.getMessage():"INVALID_RESPONSE";
			jobs.finish(job,"FALLBACK",reason,AiReport.fallback(job.snapshot()),Map.of("model",AiSettings.MODEL,"digest",settings.digest(),"runtime_version",settings.runtimeVersion(),"prompt_version",AiSettings.PROMPT_VERSION,"tools",trace));
		} finally { metrics.timer("pip.ai.duration","result",result).record(System.nanoTime()-start,java.util.concurrent.TimeUnit.NANOSECONDS); }
	}
}
