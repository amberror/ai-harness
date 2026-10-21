package com.example.ai_controll.tool.exec.builtin;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * Parsing the date formats a language model actually emits.
 *
 * <p>Shared by the two date tools because being liberal here is a deliberate decision that should be
 * made once. Asked for an ISO date, a small model will produce {@code 2026-03-01},
 * {@code 2026-03-01T09:30}, {@code 2026-03-01 09:30:00} or a full offset date-time, and rejecting
 * three of those measures its formatting habits rather than its arithmetic. Anything genuinely
 * unparseable is still refused, with the accepted shapes named so the model can correct itself.
 */
final class DateToolkit {

	/** Tried in order; the first that parses wins. */
	private static final List<java.util.function.Function<String, OffsetDateTime>> PARSERS = List.of(
			OffsetDateTime::parse,
			text -> LocalDateTime.parse(text).atOffset(ZoneOffset.UTC),
			text -> LocalDateTime.parse(text.replace(' ', 'T')).atOffset(ZoneOffset.UTC),
			text -> LocalDate.parse(text).atStartOfDay().atOffset(ZoneOffset.UTC));

	private DateToolkit() {
	}

	static Optional<OffsetDateTime> parse(String text) {
		if (text == null || text.isBlank()) {
			return Optional.empty();
		}
		String trimmed = text.strip();
		for (var parser : PARSERS) {
			try {
				return Optional.of(parser.apply(trimmed));
			}
			catch (RuntimeException ignored) {
				// Try the next shape. DateTimeParseException is a RuntimeException, and so is the
				// IndexOutOfBounds a malformed offset can produce inside the parser.
			}
		}
		return Optional.empty();
	}

	static String formatsHint() {
		return "Accepted formats: 2026-03-01, 2026-03-01T09:30, 2026-03-01 09:30:00, "
				+ "2026-03-01T09:30:00Z";
	}

	/** Whether the value was a plain date with no time part, so the answer can be reported the same way. */
	static boolean isDateOnly(String text) {
		return text != null && text.strip().length() == 10;
	}

	/** Seconds in one of the supported units, or empty for an unknown one. */
	static Optional<Duration> unitDuration(String unit) {
		return Optional.ofNullable(switch (unit) {
			case "seconds", "second" -> Duration.ofSeconds(1);
			case "minutes", "minute" -> Duration.ofMinutes(1);
			case "hours", "hour" -> Duration.ofHours(1);
			case "days", "day" -> Duration.ofDays(1);
			case "weeks", "week" -> Duration.ofDays(7);
			default -> null;
		});
	}

	static final String TIME_UNITS = "\"seconds\",\"minutes\",\"hours\",\"days\",\"weeks\"";
}
