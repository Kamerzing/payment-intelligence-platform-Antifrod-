package com.kamerzing.payment_intelligence;

import com.sun.net.httpserver.HttpServer;
import com.kamerzing.payment_intelligence.ai.*;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OllamaClientTests {
	@Test void verifiesLocalIdentityStructuredFactsAndRejectsFabricationOutageAndOversize() throws Exception {
		var json=JsonMapper.builder().build();var mode=new AtomicReference<>("valid");String digest="a".repeat(64);UUID attempt=UUID.randomUUID();
		var snapshot=new AiFacts.Snapshot(attempt,"DENY",70,List.of(new AiFacts.Fact("attempt:"+attempt,"read_attempt","Факт."),new AiFacts.Fact("decision:1","read_attempt","Отказ.")));
		var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
		server.createContext("/api/",exchange->{
			Object body=switch(exchange.getRequestURI().getPath()) {
				case "/api/version"->Map.of("version","0.33.3");
				case "/api/tags"->Map.of("models",List.of(Map.of("name",AiSettings.MODEL,"digest",mode.get().equals("identity")?"b".repeat(64):digest)));
				case "/api/show"->Map.of("license","Apache License Version 2.0","details",Map.of("quantization_level","Q4_K_M","family","qwen3"));
				default->{var request=json.readTree(exchange.getRequestBody().readAllBytes());assertThat(request.path("stream").asBoolean()).isFalse();assertThat(request.path("keep_alive").asInt()).isZero();assertThat(request.path("options").path("num_ctx").asInt()).isEqualTo(4096);
					yield Map.of("model",AiSettings.MODEL,"done",true,"done_reason","stop","message",Map.of("content",mode.get().equals("oversize")?"x".repeat(40000):json.writeValueAsString(Map.of("evidence",mode.get().equals("invented")?List.of("operation:invented"):snapshot.facts().stream().map(AiFacts.Fact::id).toList(),"recommendations",List.of("COMPARE_HISTORY"),"limitations",List.of("RULES_NOT_PROOF","LIMITED_HISTORY","NO_AUTOMATIC_ACTION")))));
				}
			};
			byte[] bytes=json.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(mode.get().equals("outage")?503:200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
		});server.start();
		try(var client=new OllamaClient(new AiSettings(true,"http://127.0.0.1:"+server.getAddress().getPort(),digest,"0.33.3",1,8,"http://127.0.0.1:8080",""),json)) {
			assertThat(client.generate(snapshot,snapshot.facts()).report()).containsEntry("source","LOCAL_MODEL");
			for(String failure:List.of("identity","invented","outage","oversize")) { mode.set(failure);assertThatThrownBy(()->client.generate(snapshot,snapshot.facts())).isInstanceOf(AiFailure.class); }
		} finally { server.stop(0); }
	}
	@Test void toolAccessChecksCurrentActorForEveryInvocationAndRejectsWrongScope() throws Exception {
		var authorized=new java.util.concurrent.atomic.AtomicBoolean(true);var calls=new java.util.concurrent.atomic.AtomicInteger();var json=JsonMapper.builder().build();
		var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/internal/v1/analysts/",e->{calls.incrementAndGet();assertThat(e.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer "+"x".repeat(32));byte[] b=("{\"authorized\":"+authorized.get()+"}").getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,b.length);e.getResponseBody().write(b);e.close();});server.start();
		try(var auth=new AnalystAuthorization(new AiSettings(false,"http://127.0.0.1:11435","","",1,8,"http://127.0.0.1:"+server.getAddress().getPort(),"x".repeat(32)),json)) {
			var tools=new AiTools(auth);UUID attempt=UUID.randomUUID(),actor=UUID.randomUUID();var snapshot=new AiFacts.Snapshot(attempt,"ALLOW",0,List.of());
			tools.read("read_attempt",actor,attempt,snapshot);authorized.set(false);assertThatThrownBy(()->tools.read("read_rules",actor,attempt,snapshot)).isInstanceOf(AiFailure.class);assertThat(calls).hasValue(2);
			assertThatThrownBy(()->tools.read("shell",actor,attempt,snapshot)).isInstanceOf(AiFailure.class);assertThatThrownBy(()->tools.read("read_attempt",actor,UUID.randomUUID(),snapshot)).isInstanceOf(AiFailure.class);assertThat(calls).hasValue(2);
		}finally{server.stop(0);}
	}
}
