package com.kamerzing.payment_intelligence.risk;

import java.time.Instant;
import java.util.UUID;
import com.kamerzing.payment_intelligence.common.MinorUnits;
import com.kamerzing.payment_intelligence.common.StrictStringDeserializer;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.annotation.JsonDeserialize;

public record RiskRequest(@NotNull UUID attempt_id, @NotNull CommandType command_type,
		@NotNull UUID initiator_id, @NotNull UUID source_wallet_id, UUID target_wallet_id, UUID hold_id,
		@NotNull String currency,
		@NotNull @JsonDeserialize(using = StrictStringDeserializer.class) String amount_minor,
		@NotNull @JsonDeserialize(using = StrictStringDeserializer.class) String fee_minor,
		@NotNull @JsonDeserialize(using = com.kamerzing.payment_intelligence.common.StrictIntegerDeserializer.class) Integer fee_basis_points,
		@NotNull Instant user_created_at) {
	public enum CommandType { TRANSFER, WITHDRAWAL, CAPTURE_TRANSFER, CAPTURE_WITHDRAWAL }
	public boolean transfer() { return command_type == CommandType.TRANSFER || command_type == CommandType.CAPTURE_TRANSFER; }
	public void validate() {
		if (attempt_id == null || command_type == null || initiator_id == null || source_wallet_id == null
				|| user_created_at == null || !"RUB".equals(currency) || fee_basis_points == null
				|| fee_basis_points < 0 || fee_basis_points > 10000
				|| transfer() != (target_wallet_id != null) || source_wallet_id.equals(target_wallet_id)
				|| command_type.name().startsWith("CAPTURE_") != (hold_id != null)) throw MinorUnits.invalid();
		MinorUnits.total(MinorUnits.parse(amount_minor, true), MinorUnits.parse(fee_minor, false));
	}
}
