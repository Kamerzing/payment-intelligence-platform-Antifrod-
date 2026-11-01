package com.kamerzing.payment_intelligence.investigations;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class AnalystService {
	private final AnalystRepository repository;
	public AnalystService(AnalystRepository repository) { this.repository=repository; }
	public Map<String,Object> dashboard() { return repository.dashboard(); }
	public Map<String,Object> attempts(AnalystRepository.Filter f) { return repository.attempts(f); }
	public Map<String,Object> attempt(UUID id) { return repository.attempt(id); }
	public Map<String,Object> cases(String status,int limit,int offset) { return repository.cases(status,limit,offset); }
	public Map<String,Object> investigation(UUID id) { return repository.investigation(id); }
	@Transactional public Map<String,Object> update(UUID id,UUID actor,AnalystRepository.Change change) { repository.update(id,actor,change); return repository.investigation(id); }
}
