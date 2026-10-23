package com.example.concurrentbalanceservice.infrastructure.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {
	@Bean
	OpenAPI balanceServiceOpenAPI() {
		return new OpenAPI().info(new Info()
				.title("Concurrent Balance Service API")
				.version("1.0.0")
				.description("Asynchronous account credits, debits, transfers, and committed balance reads."));
	}
}
