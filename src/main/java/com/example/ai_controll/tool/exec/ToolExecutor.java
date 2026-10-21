package com.example.ai_controll.tool.exec;

import com.example.ai_controll.tool.registry.ExecutorType;

/**
 * Runs a tool of one executor type.
 *
 * <p>Implementations must not throw for ordinary failure. A timeout, a refused connection, a
 * non-200 response and a bad expression are all outcomes the model should see and be able to react
 * to; only a genuine harness bug should escape.
 */
public interface ToolExecutor {

	/** The executor type this handles. */
	ExecutorType type();

	ToolOutcome execute(ToolInvocation invocation);
}
