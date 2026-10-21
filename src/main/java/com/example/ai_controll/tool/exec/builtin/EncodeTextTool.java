package com.example.ai_controll.tool.exec.builtin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.example.ai_controll.tool.exec.ToolInvocation;
import com.example.ai_controll.tool.exec.ToolOutcome;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Encodes, decodes or hashes a piece of text.
 *
 * <p>The most exactly checkable tool in the set. A hash has precisely one right answer, no
 * convention to argue about and no rounding, so a scenario asserting on it is testing whether the
 * model passed the string through <em>unaltered</em> -- which is a real failure mode. A quantised
 * model asked to hash a string will sometimes normalise its whitespace or drop a trailing character
 * on the way into the tool call, and against a free-text tool that damage is invisible.
 */
@Component
public class EncodeTextTool implements BuiltinTool {

	private final ObjectMapper objectMapper;

	public EncodeTextTool(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	public String name() {
		return "encode_text";
	}

	@Override
	public String defaultDescription() {
		return "Encodes, decodes or hashes text. Algorithms: base64, base64_decode, hex, hex_decode, "
				+ "url_encode, url_decode, sha256, sha512, md5.";
	}

	@Override
	public String defaultSchema() {
		return """
				{"type":"object",
				 "properties":{
				   "text":{"type":"string","description":"The text to transform"},
				   "algorithm":{"type":"string","description":"What to do with it",
				     "enum":["base64","base64_decode","hex","hex_decode","url_encode","url_decode",
				             "sha256","sha512","md5"]}},
				 "required":["text","algorithm"],
				 "additionalProperties":false}""";
	}

	@Override
	public ToolOutcome run(ToolInvocation invocation, JsonNode arguments) {
		Optional<String> text = Args.text(arguments, "text");
		if (text.isEmpty()) {
			return ToolOutcome.failure("invalid_arguments", Args.missing("text", "a string"), 0L);
		}
		Optional<String> algorithm = Args.keyword(arguments, "algorithm");
		if (algorithm.isEmpty()) {
			return ToolOutcome.failure("invalid_arguments",
					Args.missing("algorithm", "one of base64, base64_decode, hex, hex_decode, "
							+ "url_encode, url_decode, sha256, sha512, md5"),
					0L);
		}

		String value = text.get();
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);

		String encoded;
		try {
			encoded = switch (algorithm.get()) {
				case "base64" -> Base64.getEncoder().encodeToString(bytes);
				case "base64_decode" -> new String(Base64.getDecoder().decode(value.strip()),
						StandardCharsets.UTF_8);
				case "hex" -> HexFormat.of().formatHex(bytes);
				case "hex_decode" -> new String(HexFormat.of().parseHex(value.strip()),
						StandardCharsets.UTF_8);
				case "url_encode" -> java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
				case "url_decode" -> java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
				case "sha256" -> digest("SHA-256", bytes);
				case "sha512" -> digest("SHA-512", bytes);
				case "md5" -> digest("MD5", bytes);
				default -> null;
			};
		}
		catch (IllegalArgumentException ex) {
			// Malformed input for a decode: the model's problem to fix, not the run's to die of.
			return ToolOutcome.failure("decode_failed",
					"Could not decode the text as %s: %s".formatted(algorithm.get(), ex.getMessage()), 0L);
		}

		if (encoded == null) {
			return ToolOutcome.failure("unknown_algorithm",
					"Unknown algorithm '%s'. Use base64, base64_decode, hex, hex_decode, url_encode, "
							+ "url_decode, sha256, sha512 or md5.".formatted(algorithm.get()),
					0L);
		}

		ObjectNode result = objectMapper.createObjectNode();
		result.put("algorithm", algorithm.get());
		// The exact input length, so a scenario can catch the model silently trimming the string on
		// the way in -- the failure this tool is best placed to expose.
		result.put("inputLength", value.length());
		result.put("result", encoded);
		return ToolOutcome.success(result.toString(), 0L);
	}

	private static String digest(String algorithm, byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
		}
		catch (NoSuchAlgorithmException ex) {
			// Every algorithm named here is required by the platform, so this cannot happen.
			throw new IllegalStateException(algorithm + " is unavailable", ex);
		}
	}
}
