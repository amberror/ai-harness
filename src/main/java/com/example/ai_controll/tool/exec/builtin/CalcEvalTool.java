package com.example.ai_controll.tool.exec.builtin;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

import org.springframework.stereotype.Component;

import com.example.ai_controll.tool.exec.ToolInvocation;
import com.example.ai_controll.tool.exec.ToolOutcome;

import tools.jackson.databind.JsonNode;

/**
 * Evaluates an arithmetic expression.
 *
 * <p>Useful in the harness because its answers are checkable: a scenario can assert not only that
 * the model called the tool but that it used the result, which is a much stronger claim than "the
 * run finished".
 *
 * <p>The evaluator is a small hand-written recursive-descent parser rather than a scripting engine.
 * A script engine would accept an expression string from a language model and execute it, which is
 * arbitrary code execution wearing a calculator costume; this understands numbers, four operators,
 * parentheses and unary minus, and refuses everything else.
 */
@Component
public class CalcEvalTool implements BuiltinTool {

	@Override
	public String name() {
		return "calc_eval";
	}

	@Override
	public String defaultDescription() {
		return "Evaluates an arithmetic expression and returns the result. "
				+ "Supports + - * / %, parentheses and decimals, for example '(17 * 23) + 4.5'.";
	}

	@Override
	public String defaultSchema() {
		return """
				{"type":"object",
				 "properties":{"expression":{"type":"string",
				   "description":"The arithmetic expression to evaluate, e.g. 17 * 23"}},
				 "required":["expression"],
				 "additionalProperties":false}""";
	}

	@Override
	public ToolOutcome run(ToolInvocation invocation, JsonNode arguments) {
		JsonNode expression = arguments.get("expression");
		if (expression == null || !expression.isString()) {
			return ToolOutcome.failure("invalid_arguments", "'expression' must be a string", 0L);
		}
		String text = expression.stringValue();
		try {
			BigDecimal value = new Parser(text).evaluate();
			return ToolOutcome.success(
					"{\"expression\":\"%s\",\"result\":%s}".formatted(
							text.replace("\\", "\\\\").replace("\"", "\\\""), value.toPlainString()),
					0L);
		}
		catch (ArithmeticException ex) {
			return ToolOutcome.failure("arithmetic_error", ex.getMessage(), 0L);
		}
		catch (IllegalArgumentException ex) {
			// Reported as a failure the model can act on, not thrown out of the loop.
			return ToolOutcome.failure("invalid_expression", ex.getMessage(), 0L);
		}
	}

	/**
	 * Recursive-descent evaluator for {@code expr := term (('+'|'-') term)*}.
	 *
	 * <p>Deliberately tiny and total: anything it does not recognise raises an error naming the
	 * offending position, rather than being coerced into something that happens to evaluate.
	 */
	private static final class Parser {

		private static final MathContext CONTEXT = new MathContext(20, RoundingMode.HALF_UP);

		private final String input;

		private int position;

		Parser(String input) {
			this.input = input == null ? "" : input;
		}

		BigDecimal evaluate() {
			if (input.isBlank()) {
				throw new IllegalArgumentException("The expression is empty");
			}
			BigDecimal value = expression();
			skipSpaces();
			if (position < input.length()) {
				throw new IllegalArgumentException(
						"Unexpected '%s' at position %d".formatted(input.charAt(position), position));
			}
			return value.stripTrailingZeros();
		}

		private BigDecimal expression() {
			BigDecimal value = term();
			while (true) {
				skipSpaces();
				if (consume('+')) {
					value = value.add(term(), CONTEXT);
				}
				else if (consume('-')) {
					value = value.subtract(term(), CONTEXT);
				}
				else {
					return value;
				}
			}
		}

		private BigDecimal term() {
			BigDecimal value = factor();
			while (true) {
				skipSpaces();
				if (consume('*')) {
					value = value.multiply(factor(), CONTEXT);
				}
				else if (consume('/')) {
					BigDecimal divisor = factor();
					if (divisor.signum() == 0) {
						throw new ArithmeticException("Division by zero");
					}
					value = value.divide(divisor, CONTEXT);
				}
				else if (consume('%')) {
					BigDecimal divisor = factor();
					if (divisor.signum() == 0) {
						throw new ArithmeticException("Division by zero");
					}
					value = value.remainder(divisor, CONTEXT);
				}
				else {
					return value;
				}
			}
		}

		private BigDecimal factor() {
			skipSpaces();
			if (consume('-')) {
				return factor().negate();
			}
			if (consume('+')) {
				return factor();
			}
			if (consume('(')) {
				BigDecimal value = expression();
				skipSpaces();
				if (!consume(')')) {
					throw new IllegalArgumentException("Missing closing parenthesis");
				}
				return value;
			}
			return number();
		}

		private BigDecimal number() {
			skipSpaces();
			int start = position;
			while (position < input.length()
					&& (Character.isDigit(input.charAt(position)) || input.charAt(position) == '.')) {
				position++;
			}
			if (start == position) {
				String found = position < input.length()
						? "'" + input.charAt(position) + "'" : "end of expression";
				throw new IllegalArgumentException(
						"Expected a number at position %d but found %s".formatted(position, found));
			}
			try {
				return new BigDecimal(input.substring(start, position));
			}
			catch (NumberFormatException ex) {
				throw new IllegalArgumentException(
						"'%s' is not a valid number".formatted(input.substring(start, position)));
			}
		}

		private boolean consume(char expected) {
			skipSpaces();
			if (position < input.length() && input.charAt(position) == expected) {
				position++;
				return true;
			}
			return false;
		}

		private void skipSpaces() {
			while (position < input.length() && Character.isWhitespace(input.charAt(position))) {
				position++;
			}
		}
	}
}
