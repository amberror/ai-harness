package com.example.ai_controll.tool.exec.builtin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reducing a page to what a model can afford to read.
 *
 * <p>The property that matters most is not elegance but <em>determinism</em>: the same page must
 * always reduce the same way, or an evaluation asserting on the result is measuring the extractor
 * rather than the model.
 */
class HtmlTextExtractorTest {

	private static final String PAGE = """
			<!DOCTYPE html>
			<html><head>
			<title>  Widgets &amp; Gadgets  </title>
			<meta name="description" content="Everything about widgets.">
			<style>body { color: #fff; }</style>
			<script>var tracking = "<p>not text</p>";</script>
			</head>
			<body>
			<nav><a href="/">Home</a></nav>
			<h1>Widgets</h1>
			<p>A widget costs &pound;5 &mdash; that is 20&#37; less than last year.</p>
			<p>Second paragraph.</p>
			<ul><li>One</li><li>Two</li></ul>
			<noscript>Enable JavaScript</noscript>
			</body></html>""";

	@Test
	@DisplayName("script and style contents never reach the model")
	void dropsCodeElements() {
		String text = HtmlTextExtractor.toText(PAGE);

		// Not merely stripped of tags: the contents go too. A script body is the single largest
		// source of noise on a real page, and it reads as prose once the angle brackets are gone.
		assertThat(text).doesNotContain("tracking").doesNotContain("color:")
				.doesNotContain("not text").doesNotContain("Enable JavaScript");
	}

	@Test
	@DisplayName("entities are decoded, named and numeric alike")
	void decodesEntities() {
		String text = HtmlTextExtractor.toText(PAGE);

		assertThat(text).contains("£5").contains("—").contains("20%");
	}

	@Test
	@DisplayName("block structure survives as line breaks")
	void keepsParagraphs() {
		String text = HtmlTextExtractor.toText(PAGE);

		// Without this the page arrives as one wall of text, which a model cannot segment and a
		// person cannot read.
		assertThat(text).contains("Widgets\n");
		assertThat(text.lines().anyMatch(line -> line.equals("Second paragraph."))).isTrue();
		assertThat(text).doesNotContain("\n\n\n");
	}

	@Test
	@DisplayName("the title and description come back separately")
	void extractsMetadata() {
		// Often the whole answer to "what is this page about", for the price of twenty tokens.
		assertThat(HtmlTextExtractor.title(PAGE)).contains("Widgets & Gadgets");
		assertThat(HtmlTextExtractor.description(PAGE)).contains("Everything about widgets.");
	}

	@Test
	@DisplayName("a page with no title or description says so rather than inventing one")
	void handlesMissingMetadata() {
		assertThat(HtmlTextExtractor.title("<html><body>hi</body></html>")).isEmpty();
		assertThat(HtmlTextExtractor.description("<html><body>hi</body></html>")).isEmpty();
		assertThat(HtmlTextExtractor.title("<html><head><title>   </title></head></html>")).isEmpty();
	}

	@Test
	@DisplayName("only HTML content types are extracted from")
	void recognisesHtml() {
		assertThat(HtmlTextExtractor.isHtml("text/html; charset=utf-8")).isTrue();
		assertThat(HtmlTextExtractor.isHtml("application/xhtml+xml")).isTrue();
		// JSON must pass through untouched, or json_extract has nothing to read.
		assertThat(HtmlTextExtractor.isHtml("application/json")).isFalse();
		assertThat(HtmlTextExtractor.isHtml("text/plain")).isFalse();
		assertThat(HtmlTextExtractor.isHtml(null)).isFalse();
	}

	@Test
	@DisplayName("comments and doctype are removed")
	void dropsCommentsAndDoctype() {
		String text = HtmlTextExtractor.toText(
				"<!DOCTYPE html><!-- internal note --><p>Visible</p>");

		assertThat(text).isEqualTo("Visible");
	}

	@Test
	@DisplayName("a malformed entity is left alone rather than guessed at")
	void leavesBrokenEntities() {
		assertThat(HtmlTextExtractor.toText("<p>5 &#xZZZZ; 6</p>")).contains("&#xZZZZ;");
	}

	@Test
	@DisplayName("the reduction is large, which is the entire point")
	void reducesSubstantially() {
		StringBuilder bloated = new StringBuilder("<html><head><title>T</title>");
		bloated.append("<script>").append("var x = 1; ".repeat(400)).append("</script>");
		bloated.append("<style>").append(".a { color: red; } ".repeat(400)).append("</style>");
		bloated.append("</head><body><p>The one sentence that matters.</p></body></html>");

		String text = HtmlTextExtractor.toText(bloated.toString());

		assertThat(text).isEqualTo("The one sentence that matters.");
		assertThat(text.length()).isLessThan(bloated.length() / 100);
	}

	@Test
	@DisplayName("a network failure names the host and says what to do about it")
	void describesNetworkFailures() {
		// From a real run: the model chose exactly the right API and was told "ConnectException: null",
		// which says nothing about whether to retry, try elsewhere, or stop.
		assertThat(HttpFetchTool.describe(new java.net.ConnectException(),
				"https://worldtimeapi.org/api/timezone/America/Toronto"))
				.contains("worldtimeapi.org")
				.contains("a different source is more likely to work")
				.doesNotContain("null");

		assertThat(HttpFetchTool.describe(new java.net.UnknownHostException("nope"),
				"https://not-a-real-domain.invalid/x"))
				.contains("no host named 'not-a-real-domain.invalid'");

		assertThat(HttpFetchTool.describe(
				new javax.net.ssl.SSLHandshakeException("Remote host terminated the handshake"),
				"https://example.com/x"))
				.contains("example.com").contains("retrying will probably not help");
	}

	@Test
	@DisplayName("empty and tagless input do not blow up")
	void handlesEdgeCases() {
		assertThat(HtmlTextExtractor.toText("")).isEmpty();
		assertThat(HtmlTextExtractor.toText(null)).isEmpty();
		assertThat(HtmlTextExtractor.toText("just text")).isEqualTo("just text");
	}
}
