package com.kamerzing.payment_intelligence.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;

@Configuration(proxyBeanMethods=false)
public class AnalystAssetsConfiguration implements WebMvcConfigurer {
	@Override public void addResourceHandlers(ResourceHandlerRegistry registry) {
		registry.addResourceHandler("/intelligence/assets/**").addResourceLocations("classpath:/static/assets/");
	}
}
