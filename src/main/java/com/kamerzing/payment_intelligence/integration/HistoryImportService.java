package com.kamerzing.payment_intelligence.integration;

import java.util.List;
import java.util.UUID;
import com.kamerzing.payment_intelligence.common.MinorUnits;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HistoryImportService {
	private final JdbcTemplate jdbc;
	private final EventService events;
	private final String source;
	public HistoryImportService(JdbcTemplate jdbc, EventService events, @Value("${pip.integration.source-id}") String source) {
		this.jdbc = jdbc; this.events = events; this.source = source;
	}
	@Transactional
	public Checkpoint begin(UUID id, String cursor) {
		long position = MinorUnits.parse(cursor, false);
		jdbc.update("INSERT INTO history_import(source_id,export_id,cursor) VALUES (?,?,?) ON CONFLICT DO NOTHING", source, id, position);
		return checkpoint(id);
	}
	public Checkpoint checkpoint(UUID id) {
		return jdbc.queryForObject("SELECT * FROM history_import WHERE source_id=? AND export_id=?", (rs, row) ->
				new Checkpoint(id, Long.toString(rs.getLong("cursor")), rs.getObject("after_operation_id", UUID.class), rs.getBoolean("complete")), source, id);
	}
	@Transactional
	public Checkpoint page(UUID id, UUID expectedAfter, ExportPage page) {
		var current = jdbc.queryForObject("SELECT * FROM history_import WHERE source_id=? AND export_id=? FOR UPDATE", (rs, row) ->
				new Checkpoint(id, Long.toString(rs.getLong("cursor")), rs.getObject("after_operation_id", UUID.class), rs.getBoolean("complete")), source, id);
		if (!java.util.Objects.equals(current.after_operation_id(), expectedAfter) || current.complete()) return current;
		if (!id.equals(page.export_id()) || !source.equals(page.source_id()) || !current.cursor().equals(page.cursor())
				|| page.items() == null || page.items().size() > 100 || (!page.complete() && page.items().isEmpty())) throw MinorUnits.invalid();
		UUID last = expectedAfter;
		for (var event : page.items()) {
			UUID operation = event.entityId();
			if (operation == null || (last != null && compareUuid(operation, last) <= 0)
					|| MinorUnits.parse(event.source_position(), true) > Long.parseLong(current.cursor())) throw MinorUnits.invalid();
			events.accept(event);
			last = operation;
		}
		if (!java.util.Objects.equals(last, page.next_after_operation_id())) throw MinorUnits.invalid();
		jdbc.update("UPDATE history_import SET after_operation_id=?,complete=? WHERE source_id=? AND export_id=?",
				last, page.complete(), source, id);
		return checkpoint(id);
	}
	private static int compareUuid(UUID left, UUID right) { return left.toString().compareTo(right.toString()); }
	public record Checkpoint(UUID export_id, String cursor, UUID after_operation_id, boolean complete) { }
	public record ExportPage(UUID export_id, String source_id, String cursor, List<CoreEvent> items, UUID next_after_operation_id, boolean complete) { }
}
