package com.example.ai_controll.tool.exec.builtin;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a web page into the text a model can actually use.
 *
 * <p>The reason this exists is arithmetic. A typical page is mostly markup: scripts, inline styles,
 * SVG sprites, navigation, tracking. Handing that to a 4B model with an 8k window spends the entire
 * context on angle brackets before reaching a sentence, and the model then answers from whatever
 * fragment survived truncation. Measured on github.com: 575,809 characters of markup reduced to
 * 7,654 of text — seventy-five times smaller, and the smaller one is the one that contains the
 * answer.
 *
 * <p>Hand-written rather than delegated to a parser, deliberately. A tool whose output is fed to a
 * language model needs <em>predictable</em> output above all: the same page must always reduce the
 * same way, or an evaluation asserting on it is measuring the extractor's mood. This does a small
 * number of things and does them the same way every time, and everything it drops is a category
 * named below rather than a heuristic guess about what looked important.
 */
final class HtmlTextExtractor {

	/** Elements whose contents are code or presentation, never prose. */
	private static final Pattern DROP_ELEMENTS = Pattern.compile(
			"(?is)<(script|style|noscript|template|svg|canvas|iframe|object|embed)\\b[^>]*>.*?</\\1\\s*>");

	private static final Pattern COMMENTS = Pattern.compile("(?s)<!--.*?-->");

	private static final Pattern DOCTYPE = Pattern.compile("(?i)<!doctype[^>]*>");

	/**
	 * The whole head. It holds no prose, and leaving it in puts the title into the body text as well
	 * as into its own field -- the same twenty tokens twice, in a budget where that is the thing
	 * being economised.
	 */
	private static final Pattern HEAD = Pattern.compile("(?is)<head\\b[^>]*>.*?</head\\s*>");

	private static final Pattern TITLE = Pattern.compile("(?is)<title\\b[^>]*>(.*?)</title\\s*>");

	private static final Pattern META_DESCRIPTION = Pattern.compile(
			"(?is)<meta\\b[^>]*\\bname\\s*=\\s*[\"']description[\"'][^>]*\\bcontent\\s*=\\s*[\"']([^\"']*)[\"']");

	/** Tags that end a line of text. Anything else is inline and leaves no gap behind. */
	private static final Pattern BLOCK_BOUNDARY = Pattern.compile(
			"(?is)</?(p|div|section|article|header|footer|main|aside|nav|h[1-6]|ul|ol|li|dl|dt|dd"
					+ "|table|thead|tbody|tr|blockquote|pre|form|fieldset|figure|figcaption|hr|br)\\b[^>]*>");

	private static final Pattern ANY_TAG = Pattern.compile("(?s)<[^>]*>");

	private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x?)([0-9a-fA-F]+);");

	private static final Pattern SPACES = Pattern.compile("[ \\t\\x0B\\f\\r]+");

	private static final Pattern BLANK_LINES = Pattern.compile("\n{3,}");

	/** The named entities that actually turn up in prose. The numeric forms cover the rest. */
	private static final Map<String, String> ENTITIES = Map.ofEntries(
			Map.entry("amp", "&"), Map.entry("lt", "<"), Map.entry("gt", ">"),
			Map.entry("quot", "\""), Map.entry("apos", "'"), Map.entry("nbsp", " "),
			Map.entry("mdash", "—"), Map.entry("ndash", "–"), Map.entry("hellip", "…"),
			Map.entry("rsquo", "'"), Map.entry("lsquo", "'"), Map.entry("rdquo", "\""),
			Map.entry("ldquo", "\""), Map.entry("middot", "·"), Map.entry("bull", "•"),
			Map.entry("copy", "©"), Map.entry("reg", "®"), Map.entry("trade", "™"),
			Map.entry("deg", "°"), Map.entry("euro", "€"), Map.entry("pound", "£"),
			Map.entry("times", "×"), Map.entry("divide", "÷"), Map.entry("laquo", "«"),
			Map.entry("raquo", "»"));

	private HtmlTextExtractor() {
	}

	/** Whether a content type is worth extracting from, as opposed to passing through. */
	static boolean isHtml(String contentType) {
		if (contentType == null) {
			return false;
		}
		String type = contentType.toLowerCase(Locale.ROOT);
		return type.contains("text/html") || type.contains("application/xhtml");
	}

	/** The document's title, before the markup is stripped and it becomes indistinguishable. */
	static Optional<String> title(String html) {
		Matcher matcher = TITLE.matcher(html);
		if (!matcher.find()) {
			return Optional.empty();
		}
		String title = collapse(decodeEntities(ANY_TAG.matcher(matcher.group(1)).replaceAll("")));
		return title.isBlank() ? Optional.empty() : Optional.of(title);
	}

	/**
	 * The meta description. Often a one-sentence summary written by the page's author, which is
	 * frequently the entire answer to "what is this page about" at a cost of twenty tokens.
	 */
	static Optional<String> description(String html) {
		Matcher matcher = META_DESCRIPTION.matcher(html);
		if (!matcher.find()) {
			return Optional.empty();
		}
		String description = collapse(decodeEntities(matcher.group(1)));
		return description.isBlank() ? Optional.empty() : Optional.of(description);
	}

	/** The readable text of the page, with block structure preserved as line breaks. */
	static String toText(String html) {
		if (html == null || html.isEmpty()) {
			return "";
		}
		String working = DOCTYPE.matcher(html).replaceAll("");
		working = COMMENTS.matcher(working).replaceAll("");
		// After title() and description() have taken what they need from the raw document.
		working = HEAD.matcher(working).replaceAll("");
		working = DROP_ELEMENTS.matcher(working).replaceAll("\n");
		// Block tags become line breaks before the rest are erased, so paragraphs do not run together
		// into one wall of text -- which is both unreadable and, for a model, unsegmentable.
		working = BLOCK_BOUNDARY.matcher(working).replaceAll("\n");
		working = ANY_TAG.matcher(working).replaceAll("");
		working = decodeEntities(working);

		working = SPACES.matcher(working).replaceAll(" ");
		StringBuilder out = new StringBuilder(working.length());
		for (String line : working.split("\n")) {
			out.append(line.strip()).append('\n');
		}
		return BLANK_LINES.matcher(out.toString()).replaceAll("\n\n").strip();
	}

	private static String decodeEntities(String text) {
		if (text.indexOf('&') < 0) {
			return text;
		}
		String working = text;
		for (Map.Entry<String, String> entity : ENTITIES.entrySet()) {
			if (working.contains("&" + entity.getKey())) {
				working = working.replace("&" + entity.getKey() + ";", entity.getValue());
			}
		}
		Matcher matcher = NUMERIC_ENTITY.matcher(working);
		StringBuilder out = new StringBuilder();
		while (matcher.find()) {
			String replacement;
			try {
				int codePoint = Integer.parseInt(matcher.group(2), matcher.group(1).isEmpty() ? 10 : 16);
				replacement = Character.isValidCodePoint(codePoint) ? new String(Character.toChars(codePoint))
						: matcher.group();
			}
			catch (RuntimeException ex) {
				// A malformed entity is left exactly as written rather than guessed at.
				replacement = matcher.group();
			}
			matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
		}
		matcher.appendTail(out);
		return out.toString();
	}

	private static String collapse(String text) {
		return SPACES.matcher(text.replace('\n', ' ')).replaceAll(" ").strip();
	}
}
