package com.kamerzing.payment_intelligence.integration;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import com.kamerzing.payment_intelligence.common.MinorUnits;
import com.kamerzing.payment_intelligence.common.StrictStringDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

public record CoreEvent(UUID event_id, String event_type, Integer schema_version, String source_id,
		@JsonDeserialize(using = StrictStringDeserializer.class) String source_position,
		@JsonDeserialize(using = StrictStringDeserializer.class) String source_version,
		Instant occurred_at, UUID attempt_id, UUID decision_id, Operation operation,
		@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
		com.kamerzing.payment_intelligence.risk.RiskRequest rejected_command) {
	public CoreEvent(UUID event_id,String event_type,Integer schema_version,String source_id,String source_position,String source_version,
			Instant occurred_at,UUID attempt_id,UUID decision_id,Operation operation) {
		this(event_id,event_type,schema_version,source_id,source_position,source_version,occurred_at,attempt_id,decision_id,operation,null);
	}
	public UUID entityId() { return operation==null?attempt_id:operation.operation_id(); }
	private static final Set<String> TYPES = Set.of("DEPOSIT", "TRANSFER", "WITHDRAWAL", "REVERSAL",
			"HOLD_CREATE", "HOLD_CANCEL", "HOLD_EXPIRE", "CAPTURE_TRANSFER", "CAPTURE_WITHDRAWAL");
	public record Operation(UUID operation_id, UUID actor_id, String type, String state, String currency,
			UUID source_wallet_id, UUID target_wallet_id,
			@JsonDeserialize(using = StrictStringDeserializer.class) String amount_minor,
			@JsonDeserialize(using = StrictStringDeserializer.class) String fee_minor,
		@JsonDeserialize(using = com.kamerzing.payment_intelligence.common.StrictIntegerDeserializer.class) Integer fee_basis_points,
		UUID original_operation_id, Instant created_at, Instant posted_at) { }
	public void validate(String expectedSource) {
		if("RISK_REJECTED".equals(event_type)) {
			if(event_id==null || !Integer.valueOf(1).equals(schema_version) || !expectedSource.equals(source_id) || occurred_at==null
				|| attempt_id==null || decision_id==null || operation!=null || rejected_command==null || !"1".equals(source_version) || !attempt_id.equals(rejected_command.attempt_id())) throw MinorUnits.invalid();
			rejected_command.validate(); MinorUnits.parse(source_position,true); MinorUnits.parse(source_version,true); return;
		}
		if(rejected_command!=null || ((attempt_id==null)!=(decision_id==null))) throw MinorUnits.invalid();
		if (event_id == null || !"OPERATION_POSTED".equals(event_type) || !Integer.valueOf(1).equals(schema_version)
				|| !expectedSource.equals(source_id) || occurred_at == null || operation == null
				|| operation.operation_id() == null || operation.actor_id() == null || operation.type() == null || !TYPES.contains(operation.type())
				|| !"POSTED".equals(operation.state()) || !"RUB".equals(operation.currency())
				|| operation.created_at() == null || operation.posted_at() == null
				|| operation.posted_at().isBefore(operation.created_at())
				|| !occurred_at.equals(operation.posted_at()) || operation.fee_basis_points() == null
				|| operation.fee_basis_points() < 0 || operation.fee_basis_points() > 10000
				|| (operation.source_wallet_id() == null && operation.target_wallet_id() == null)
				|| (operation.source_wallet_id() != null && operation.source_wallet_id().equals(operation.target_wallet_id()))
				|| "REVERSAL".equals(operation.type()) != (operation.original_operation_id() != null)) throw MinorUnits.invalid();
		MinorUnits.parse(source_position, true); MinorUnits.parse(source_version, true);
		MinorUnits.total(MinorUnits.parse(operation.amount_minor(), true), MinorUnits.parse(operation.fee_minor(), false));
	}
}
