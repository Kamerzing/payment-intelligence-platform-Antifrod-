package com.kamerzing.payment_intelligence.ai;

import java.net.URI;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@ConfigurationProperties("pip.ai")
public record AiSettings(boolean enabled,String url,String digest,String runtimeVersion,int timeoutSeconds,int queueCapacity,String coreUrl,String analystToken) {
	public AiSettings {
		loopback(url);loopback(coreUrl);
		if(timeoutSeconds<1 || timeoutSeconds>180 || queueCapacity<1 || queueCapacity>8) throw new IllegalArgumentException("Invalid AI limits.");
		if(enabled && (digest==null || !digest.matches("[a-f0-9]{64}") || runtimeVersion==null || !runtimeVersion.matches("[0-9]+\\.[0-9]+\\.[0-9]+"))) throw new IllegalArgumentException("Pin local model digest and runtime before enabling AI.");
	}
	public static void loopback(String value) {
		URI uri=URI.create(value);
		if(!"http".equals(uri.getScheme()) || !Set.of("127.0.0.1","localhost","[::1]").contains(uri.getHost()) || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) throw new IllegalArgumentException("AI services must use loopback.");
	}
	public static final String MODEL="qwen3:4b-instruct";
	public static final String PROMPT_VERSION="facts-catalog-v1";
	@Configuration(proxyBeanMethods=false)
	@EnableConfigurationProperties(AiSettings.class)
	@EnableScheduling
	static class Registration { }
}
