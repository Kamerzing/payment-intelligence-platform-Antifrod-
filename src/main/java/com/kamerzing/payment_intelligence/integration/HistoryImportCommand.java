package com.kamerzing.payment_intelligence.integration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import com.kamerzing.payment_intelligence.PaymentIntelligencePlatformApplication;
import tools.jackson.databind.ObjectMapper;

public final class HistoryImportCommand {
	private HistoryImportCommand() { }
	public static void run(String[] args) throws Exception {
		try (var context = new SpringApplicationBuilder(PaymentIntelligencePlatformApplication.class).web(WebApplicationType.NONE).run(args);
				var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build()) {
			if (java.util.Arrays.asList(args).contains("--rebuild-view")) {
				context.getBean(EventService.class).rebuild();
				System.out.println("Operation view rebuilt from stored events."); return;
			}
			var environment = context.getEnvironment();
			String base = environment.getProperty("PIP_CORE_URL", "http://127.0.0.1:8080");
			URI uri = URI.create(base);
			if (!"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost()) || uri.getUserInfo() != null
					|| uri.getRawQuery() != null || uri.getFragment() != null || !uri.getPath().isEmpty()) throw new IllegalArgumentException("Use a loopback core URL without a path.");
			String token = environment.getRequiredProperty("PIP_EXPORT_TOKEN");
			if (token.length() < 32) throw new IllegalArgumentException("Configure a separate export credential.");
			var json = context.getBean(ObjectMapper.class);
			var importer = context.getBean(HistoryImportService.class);
			UUID exportId = null;
			for (String arg : args) if (arg.startsWith("--export-id=")) exportId = UUID.fromString(arg.substring(12));
			if (exportId == null) {
				var manifest = json.readTree(fetch(client, base + "/internal/v1/exports", token, true));
				exportId = UUID.fromString(manifest.path("export_id").asText());
				importer.begin(exportId, manifest.path("cursor").asText());
				System.out.println("Created export " + exportId + "; use --export-id=" + exportId + " to resume.");
			}
			var checkpoint = importer.checkpoint(exportId);
			while (!checkpoint.complete()) {
				String after = checkpoint.after_operation_id() == null ? "" : "&after_operation_id=" + checkpoint.after_operation_id();
				var page = json.readValue(fetch(client, base + "/internal/v1/exports/" + exportId + "?limit=100" + after, token, false), HistoryImportService.ExportPage.class);
				checkpoint = importer.page(exportId, checkpoint.after_operation_id(), page);
			}
			System.out.println("History import complete; outbox delivery continues independently.");
		}
	}
	private static String fetch(HttpClient client, String url, String token, boolean post) throws Exception {
		var builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + token);
		var pending = client.sendAsync(post ? builder.POST(HttpRequest.BodyPublishers.noBody()).build() : builder.GET().build(),
				info -> new com.kamerzing.payment_intelligence.common.LimitedBody(1048576));
		try {
			var response = pending.get(30, java.util.concurrent.TimeUnit.SECONDS);
			if (response.statusCode() != 200) throw new IllegalStateException("Core export failed with HTTP " + response.statusCode());
			return new String(response.body(), StandardCharsets.UTF_8);
		} catch (Exception exception) { pending.cancel(true); throw exception; }
	}
}
