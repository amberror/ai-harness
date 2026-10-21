package com.example.ai_controll.eval.assertion;

/**
 * The outcome of one check.
 *
 * @param passed     whether it held
 * @param detail     what was actually observed, so a failure explains itself without opening the run
 * @param reproducible false for a judge, so a fluctuating pass rate cannot be silently blamed on the
 *                     model when the instrument itself is nondeterministic
 */
public record AssertionVerdict(String description, AssertionKind kind, boolean passed, String detail,
		boolean reproducible) {

	public static AssertionVerdict pass(AssertionSpec spec, String detail) {
		return new AssertionVerdict(spec.describe(), spec.kind(), true, detail,
				spec.kind().isDeterministic());
	}

	public static AssertionVerdict fail(AssertionSpec spec, String detail) {
		return new AssertionVerdict(spec.describe(), spec.kind(), false, detail,
				spec.kind().isDeterministic());
	}
}
