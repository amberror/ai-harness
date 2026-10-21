package com.example.ai_controll.tool.exec;

/**
 * How much of the network an HTTP tool may reach.
 *
 * <p>The modes exist because a single allowlist was conflating two protections of very different
 * value. Keeping the harness off the <em>private</em> network — cloud metadata endpoints, services on
 * loopback, the LAN, this application's own database console — is the one that matters, and it does
 * not need a list of domains: it is an address check, and it applies in every mode below except the
 * one named for having none. Keeping the harness off the <em>public</em> internet is the part that
 * costs a line of configuration per site, and it buys something much narrower.
 *
 * <p>So the choice is now explicit rather than implied by an empty list, and the address check no
 * longer rides on the domain list.
 */
public enum EgressMode {

	/**
	 * Only hosts named in {@code allowed-hosts}. The default, and the right setting when the harness
	 * is driving something real or handling text you did not write.
	 */
	ALLOWLIST,

	/**
	 * Any host that resolves to a public address. Private, loopback and link-local addresses are
	 * still refused, so the commonest and most damaging misuse — the model being talked into fetching
	 * {@code 169.254.169.254} or a service on localhost — is still blocked.
	 *
	 * <p>What it gives up is narrower than it looks, but it is real: a page the model fetches is
	 * untrusted text, and a page that instructs the model to fetch
	 * {@code https://somewhere-else/?data=…} now has somewhere to send it. Worth choosing
	 * deliberately when the prompts and the pages are your own, and worth avoiding when they are not.
	 */
	PUBLIC,

	/**
	 * Everything, private addresses included. No protection whatsoever, for a throwaway sandbox where
	 * that is the point. Named so that nobody arrives here by accident.
	 */
	OPEN
}
