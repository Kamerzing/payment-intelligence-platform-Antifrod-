package com.kamerzing.payment_intelligence.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class TimeConfiguration {
	@Bean Clock riskClock() { return Clock.systemUTC(); }
}
