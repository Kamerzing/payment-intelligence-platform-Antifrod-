package com.kamerzing.payment_intelligence.config;

import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@SecurityScheme(name="CoreBearer",type=SecuritySchemeType.HTTP,scheme="bearer",
		description="Отдельный секрет Payment Core. Сессии браузера не принимаются.")
public class InternalOpenApiConfiguration { }
