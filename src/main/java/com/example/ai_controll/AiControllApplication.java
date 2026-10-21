package com.example.ai_controll;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scheduling is enabled for one job: pruning old runs. It stays inert unless a retention period is
 * configured, which it is not by default.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class AiControllApplication {

	public static void main(String[] args) {
		SpringApplication.run(AiControllApplication.class, args);
	}

}
