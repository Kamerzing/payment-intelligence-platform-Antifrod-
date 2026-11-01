package com.kamerzing.payment_intelligence.ai;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import com.kamerzing.payment_intelligence.common.LimitedBody;

/** Every tool read rechecks current ADMIN in Core via its authenticated API. */
@Component
public class AnalystAuthorization implements AutoCloseable {
	private final AiSettings settings;
	private final ObjectMapper json;
	private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).followRedirects(HttpClient.Redirect.NEVER).build();
	public AnalystAuthorization(AiSettings settings,ObjectMapper json) { this.settings=settings;this.json=json; }
	public void check(UUID actor) {
		if(settings.analystToken().length()<32) throw new AiFailure("AUTHORIZATION_UNAVAILABLE");
		var request=HttpRequest.newBuilder(URI.create(settings.coreUrl().replaceAll("/$","")+"/internal/v1/analysts/"+actor+"/authorization"))
			.timeout(Duration.ofSeconds(1)).header("Authorization","Bearer "+settings.analystToken()).GET().build();
		var future=client.sendAsync(request,info->new LimitedBody(1024));
		try {
			var response=future.get(1,TimeUnit.SECONDS);
			if(response.statusCode()!=200) throw new AiFailure("AUTHORIZATION_UNAVAILABLE");
			var body=json.readTree(response.body());
			if(!body.path("authorized").isBoolean() || !body.path("authorized").asBoolean()) throw new AiFailure("ACCESS_REVOKED");
		} catch(AiFailure failure) { throw failure; } catch(Exception failure) { future.cancel(true);if(failure instanceof InterruptedException) Thread.currentThread().interrupt();throw new AiFailure("AUTHORIZATION_UNAVAILABLE"); }
	}
	@Override public void close() { client.close(); }
}
