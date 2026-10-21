package com.example.ai_controll.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Execution resources for runs.
 */
@Configuration(proxyBeanMethods = false)
public class ExecutorConfig {

	/**
	 * Runs execute off the request thread, one virtual thread each.
	 *
	 * <p>A run is almost entirely spent blocked on the model or on a tool, so virtual threads suit it
	 * exactly: there is no pool size to tune and no queue to starve, and a long run costs a stack
	 * rather than a platform thread. The bean is destroyed with the context, which shuts it down.
	 */
	@Bean(destroyMethod = "shutdown")
	public ExecutorService runExecutor() {
		return Executors.newThreadPerTaskExecutor(
				Thread.ofVirtual().name("harness-run-", 0).factory());
	}
}
