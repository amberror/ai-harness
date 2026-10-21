package com.example.ai_controll.tool.exec;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.example.ai_controll.tool.exec.builtin.BuiltinTool;
import com.example.ai_controll.tool.registry.ExecutorType;

/** Dispatches to the compiled {@link BuiltinTool} registered under the tool's name. */
@Component
public class BuiltinToolExecutor implements ToolExecutor {

	private final Map<String, BuiltinTool> byName;

	public BuiltinToolExecutor(List<BuiltinTool> tools) {
		this.byName = tools.stream()
				.collect(Collectors.toUnmodifiableMap(BuiltinTool::name, Function.identity()));
	}

	@Override
	public ExecutorType type() {
		return ExecutorType.BUILTIN;
	}

	@Override
	public ToolOutcome execute(ToolInvocation invocation) {
		BuiltinTool tool = byName.get(invocation.toolName());
		if (tool == null) {
			// The registry holds a BUILTIN definition with no matching bean: the tool was renamed or
			// its class removed while the stored definition survived.
			return ToolOutcome.failure("builtin_missing",
					"No built-in implementation is registered for '%s'".formatted(invocation.toolName()), 0L);
		}

		long startedAt = System.nanoTime();
		try {
			ToolOutcome outcome = tool.run(invocation, invocation.arguments());
			long elapsed = (System.nanoTime() - startedAt) / 1_000_000L;
			// Built-ins report zero duration; the executor is what actually knows how long it took.
			return new ToolOutcome(outcome.output(), outcome.succeeded(), outcome.errorKind(), elapsed,
					outcome.truncated());
		}
		catch (RuntimeException ex) {
			// A bug in a built-in must not end the run: the model is told and can try something else.
			return ToolOutcome.failure("tool_threw",
					ex.getClass().getSimpleName() + ": " + ex.getMessage(),
					(System.nanoTime() - startedAt) / 1_000_000L);
		}
	}

	/** Every built-in the application knows about, for seeding the registry on first start. */
	public List<BuiltinTool> all() {
		return List.copyOf(byName.values());
	}
}
