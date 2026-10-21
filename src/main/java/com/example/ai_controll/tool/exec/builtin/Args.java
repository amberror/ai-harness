package com.example.ai_controll.tool.exec.builtin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import tools.jackson.databind.JsonNode;

/**
 * Typed reads of a tool's arguments.
 *
 * <p>Arguments reach an executor already validated against the tool's schema, so this looks like
 * belt and braces. It is not. A tool's schema is <em>runtime-editable data</em>: someone can widen
 * {@code {"type":"number"}} to {@code {}} in the console to see what the model does with a looser
 * contract, and that experiment must not turn a bad argument into a stack trace inside a built-in.
 * Every read here returns an absence rather than throwing, and each tool turns an absence into a
 * failure the model can act on.
 */
final class Args {

	private Args() {
	}

	static Optional<String> text(JsonNode arguments, String field) {
		JsonNode value = arguments == null ? null : arguments.get(field);
		if (value == null || value.isNull()) {
			return Optional.empty();
		}
		// A number where a string was asked for is still readable, and refusing it would fail the
		// model for a formatting habit rather than for a mistake.
		return Optional.of(value.isString() ? value.stringValue() : value.asString());
	}

	static Optional<Double> number(JsonNode arguments, String field) {
		JsonNode value = arguments == null ? null : arguments.get(field);
		if (value == null || value.isNull()) {
			return Optional.empty();
		}
		if (value.isNumber()) {
			return Optional.of(value.asDouble());
		}
		// A quoted number is the single most common schema slip from a quantised model.
		try {
			return value.isString() ? Optional.of(Double.parseDouble(value.stringValue().strip()))
					: Optional.empty();
		}
		catch (NumberFormatException ex) {
			return Optional.empty();
		}
	}

	static Optional<Integer> integer(JsonNode arguments, String field) {
		return number(arguments, field).map(value -> (int) Math.round(value));
	}

	/** A lower-cased keyword, for the enum-shaped arguments. */
	static Optional<String> keyword(JsonNode arguments, String field) {
		return text(arguments, field).map(value -> value.strip().toLowerCase(Locale.ROOT));
	}

	/**
	 * An array of numbers, accepting a single number as an array of one.
	 *
	 * <p>Asked for a list, a small model quite often sends the bare value when there is only one, and
	 * treating that as malformed measures its JSON habits rather than its reasoning.
	 */
	static Optional<List<Double>> numbers(JsonNode arguments, String field) {
		JsonNode value = arguments == null ? null : arguments.get(field);
		if (value == null || value.isNull()) {
			return Optional.empty();
		}
		if (!value.isArray()) {
			return number(arguments, field).map(List::of);
		}
		List<Double> values = new ArrayList<>();
		for (JsonNode element : value) {
			if (element.isNumber()) {
				values.add(element.asDouble());
				continue;
			}
			if (element.isString()) {
				try {
					values.add(Double.parseDouble(element.stringValue().strip()));
					continue;
				}
				catch (NumberFormatException ignored) {
					// Falls through to the rejection below.
				}
			}
			return Optional.empty();
		}
		return Optional.of(values);
	}

	/** The message sent back when a required argument is missing or unusable. */
	static String missing(String field, String expected) {
		return "'%s' is required and must be %s".formatted(field, expected);
	}
}
