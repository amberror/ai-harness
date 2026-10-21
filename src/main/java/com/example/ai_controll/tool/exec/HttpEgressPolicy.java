package com.example.ai_controll.tool.exec;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.ai_controll.config.HarnessProperties;

/**
 * Decides which URLs a tool is allowed to reach.
 *
 * <p>This is a real boundary, not a formality. A tool definition is runtime-editable data and the
 * model supplies the arguments, so a URL-shaped argument is an arbitrary-request primitive: the model
 * chooses where the harness connects. On a developer machine that reaches the local network, cloud
 * metadata endpoints and anything else listening on loopback.
 *
 * <p>The protection is now in two independent parts, because they are worth very different amounts:
 *
 * <ul>
 *   <li><strong>The address check</strong> — private, loopback and link-local addresses are refused.
 *       This is the one that stops the damaging cases, and it applies in {@link EgressMode#ALLOWLIST}
 *       and {@link EgressMode#PUBLIC} alike. It costs no configuration at all.</li>
 *   <li><strong>The host allowlist</strong> — only in {@code ALLOWLIST} mode. It additionally keeps
 *       the harness off the public internet, which is worth having when the model is reading text you
 *       did not write, and is otherwise a line of configuration per site.</li>
 * </ul>
 *
 * <p>The mode governs only the public-internet question. Reaching a private address stays behind its
 * own switch in every mode, and naming a host does not exempt it -- an allowlist entry can be a
 * wildcard, and {@code *.example.com} is not consent to reach whatever {@code internal.example.com}
 * resolves to.
 *
 * <p><strong>Known gap.</strong> This resolves the host to decide, and the HTTP client resolves it
 * again to connect. A hostile resolver can answer differently the second time — classic DNS
 * rebinding — and closing that properly means pinning the checked address into the connection, which
 * the JDK client does not make easy. Every redirect hop is re-checked, which closes the far more
 * common version of the same trick.
 *
 * <p>A refusal is a normal outcome reported back to the model, not a crash.
 */
@Component
public class HttpEgressPolicy {

	private static final Logger log = LoggerFactory.getLogger(HttpEgressPolicy.class);

	private final HarnessProperties.HttpTools config;

	public HttpEgressPolicy(HarnessProperties properties) {
		this.config = properties.tools().http();
	}

	/**
	 * Checks one URL.
	 *
	 * @return a decision carrying the reason when refused, so the journal and the model both get one
	 */
	public Decision check(String url) {
		URI uri;
		try {
			uri = URI.create(url);
		}
		catch (IllegalArgumentException ex) {
			return Decision.refuse("'%s' is not a valid URL".formatted(url));
		}

		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		if (!"http".equals(scheme) && !"https".equals(scheme)) {
			return Decision.refuse("Only http and https are allowed; '%s' is not".formatted(scheme));
		}

		String rawHost = uri.getHost();
		if (rawHost == null || rawHost.isBlank()) {
			return Decision.refuse("The URL has no host");
		}
		String host = rawHost.toLowerCase(Locale.ROOT);

		EgressMode mode = config.mode();
		if (mode == EgressMode.OPEN) {
			// Nothing left to check. The mode is named for exactly this.
			return Decision.permit();
		}

		// Applies in every mode but OPEN, and before the allowlist: a blocked host stays blocked even
		// if someone also listed it, because the denial is the more deliberate of the two statements.
		if (config.blockedHosts().stream().anyMatch(pattern -> matches(pattern, host))) {
			return Decision.refuse("Host '%s' is on the blocked list".formatted(host));
		}

		if (mode == EgressMode.ALLOWLIST) {
			if (config.allowedHosts().isEmpty()) {
				return Decision.refuse(
						"No hosts are allowed for HTTP tools. Add one to harness.tools.http.allowed-hosts, "
								+ "or set harness.tools.http.mode to PUBLIC to allow any public host.");
			}
			boolean listed = config.allowedHosts().stream().anyMatch(pattern -> matches(pattern, host));
			if (!listed) {
				return Decision.refuse("Host '%s' is not in the allowlist. Allowed: %s"
						.formatted(host, config.allowedHosts()));
			}
		}

		// The check that actually matters, and the one that costs nothing to keep. Naming a host does
		// not exempt it: an allowlist entry can be a wildcard, and `*.example.com` is not consent to
		// reach whatever `internal.example.com` happens to resolve to. Reaching the local network is
		// its own switch, deliberately.
		if (!config.allowPrivateAddresses() && resolvesToPrivateAddress(host)) {
			return Decision.refuse(("Host '%s' resolves to a private or loopback address. Set "
					+ "harness.tools.http.allow-private-addresses to permit it.").formatted(host));
		}

		return Decision.permit();
	}

	/** Supports an exact host or a single leading wildcard, e.g. {@code *.example.com}. */
	private static boolean matches(String pattern, String host) {
		String candidate = pattern == null ? "" : pattern.toLowerCase(Locale.ROOT).strip();
		if (candidate.isEmpty()) {
			return false;
		}
		if (candidate.startsWith("*.")) {
			String suffix = candidate.substring(1); // keeps the dot, so *.example.com != notexample.com
			return host.endsWith(suffix);
		}
		return candidate.equals(host);
	}

	private boolean resolvesToPrivateAddress(String host) {
		try {
			for (InetAddress address : InetAddress.getAllByName(host)) {
				if (address.isLoopbackAddress() || address.isSiteLocalAddress()
						|| address.isLinkLocalAddress() || address.isAnyLocalAddress()
						|| isUniqueLocal(address)) {
					return true;
				}
			}
			return false;
		}
		catch (UnknownHostException ex) {
			// Unresolvable is not private; let the request fail honestly at connection time so the
			// model is told "host not found" rather than a misleading policy refusal.
			log.debug("Could not resolve '{}' while checking egress policy", host);
			return false;
		}
	}

	/**
	 * IPv6 unique-local addresses (fc00::/7), which {@code isSiteLocalAddress} does not cover — it
	 * answers for the deprecated fec0::/10 range instead. Without this, the IPv6 equivalent of a
	 * 10.x address walks straight through.
	 */
	private static boolean isUniqueLocal(InetAddress address) {
		byte[] bytes = address.getAddress();
		return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
	}

	/**
	 * @param allowed whether the request may proceed
	 * @param reason  why not, when it may not
	 */
	public record Decision(boolean allowed, String reason) {

		/** Named 'permit' rather than 'allowed' so it does not collide with the accessor. */
		static Decision permit() {
			return new Decision(true, null);
		}

		static Decision refuse(String reason) {
			return new Decision(false, reason);
		}
	}
}
