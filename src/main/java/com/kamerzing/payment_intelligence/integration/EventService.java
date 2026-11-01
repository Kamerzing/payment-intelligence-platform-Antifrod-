package com.kamerzing.payment_intelligence.integration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class EventService {
	private final EventRepository repository;
	private final ObjectMapper json;
	private final String source;
	public EventService(EventRepository repository, ObjectMapper json, @Value("${pip.integration.source-id}") String source) {
		this.repository = repository; this.json = json; this.source = source;
	}
	@Transactional
	public Receipt accept(CoreEvent event) {
		event.validate(source);
		repository.sourceLock(source, false);
		boolean inserted = repository.append(event, json.writeValueAsString(event));
		return new Receipt(event.event_id(), inserted ? "ACCEPTED" : "DUPLICATE");
	}
	@Transactional
	public void rebuild() { repository.sourceLock(source, true); repository.rebuild(source); }
	public record Receipt(java.util.UUID event_id, String status) { }
}
