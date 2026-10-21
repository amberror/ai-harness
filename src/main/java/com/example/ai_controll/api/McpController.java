package com.example.ai_controll.api;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.ai_controll.mcp.McpClientManager;
import com.example.ai_controll.mcp.McpServerEntity;
import com.example.ai_controll.mcp.McpServerRepository;
import com.example.ai_controll.mcp.McpToolSyncService;
import com.example.ai_controll.mcp.McpTransport;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import tools.jackson.databind.ObjectMapper;

/**
 * Managing MCP servers: configure, connect, discover, import.
 *
 * <p>Discovery and import are separate steps on purpose. Seeing what a server offers before deciding
 * which of its tools to expose matters, because putting twenty tools in front of a small model
 * measurably hurts its ability to choose among them.
 */
@RestController
@RequestMapping("/api/mcp/servers")
public class McpController {

	private final McpServerRepository servers;

	private final McpClientManager clientManager;

	private final McpToolSyncService syncService;

	private final ObjectMapper objectMapper;

	public McpController(McpServerRepository servers, McpClientManager clientManager,
			McpToolSyncService syncService, ObjectMapper objectMapper) {
		this.servers = servers;
		this.clientManager = clientManager;
		this.syncService = syncService;
		this.objectMapper = objectMapper;
	}

	@GetMapping
	public List<ServerView> list() {
		return servers.findAllByOrderByLabelAsc().stream().map(this::toView).toList();
	}

	@GetMapping("/{id}")
	public ResponseEntity<ServerView> get(@PathVariable UUID id) {
		return servers.findById(id).map(this::toView).map(ResponseEntity::ok)
				.orElseGet(() -> ResponseEntity.notFound().build());
	}

	@PostMapping
	public ResponseEntity<ServerView> create(@Valid @RequestBody ServerRequest request) {
		if (servers.existsByLabel(request.label())) {
			return ResponseEntity.badRequest().build();
		}
		McpServerEntity entity = new McpServerEntity(UUID.randomUUID(), request.label(),
				request.transport(), Instant.now());
		apply(entity, request);
		return ResponseEntity.ok(toView(servers.save(entity)));
	}

	@PostMapping("/{id}")
	public ResponseEntity<ServerView> update(@PathVariable UUID id,
			@Valid @RequestBody ServerRequest request) {
		return servers.findById(id).map(entity -> {
			apply(entity, request);
			// The cached client still speaks to the old command or URL, so the edit would otherwise
			// appear to have no effect.
			clientManager.disconnect(id);
			return ResponseEntity.ok(toView(servers.save(entity)));
		}).orElseGet(() -> ResponseEntity.notFound().build());
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@PathVariable UUID id) {
		clientManager.disconnect(id);
		servers.deleteById(id);
		return ResponseEntity.noContent().build();
	}

	/**
	 * Connects and lists what the server offers, importing nothing.
	 *
	 * <p>The step to run first: it answers "can the harness reach this server at all" separately from
	 * "which of its tools do I want", and those fail for entirely different reasons.
	 */
	@PostMapping("/{id}/discover")
	public ResponseEntity<DiscoveryResult> discover(@PathVariable UUID id) {
		return servers.findById(id).map(server -> {
			try {
				List<DiscoveredTool> tools = clientManager.listTools(server).stream()
						.map(tool -> new DiscoveredTool(tool.name(), tool.description(),
								objectMapper.valueToTree(tool.inputSchema())))
						.toList();
				server.recordStatus("CONNECTED", null);
				servers.save(server);
				return ResponseEntity.ok(new DiscoveryResult(true, tools, null));
			}
			catch (RuntimeException ex) {
				server.recordStatus("ERROR", ex.getMessage());
				servers.save(server);
				return ResponseEntity.ok(new DiscoveryResult(false, List.of(), ex.getMessage()));
			}
		}).orElseGet(() -> ResponseEntity.notFound().build());
	}

	/** Imports the server's tools into the registry, optionally restricted to an allow-list. */
	@PostMapping("/{id}/sync")
	public ResponseEntity<McpToolSyncService.SyncResult> sync(@PathVariable UUID id,
			@RequestBody(required = false) SyncRequest request) {
		Set<String> allowed = (request == null || request.allowedTools() == null) ? Set.of()
				: Set.copyOf(request.allowedTools());
		return servers.findById(id).map(server -> ResponseEntity.ok(syncService.sync(server, allowed)))
				.orElseGet(() -> ResponseEntity.notFound().build());
	}

	private void apply(McpServerEntity entity, ServerRequest request) {
		entity.setDescription(request.description());
		entity.setTransport(request.transport());
		entity.setCommand(request.command());
		entity.setArgsJson(request.args() == null ? null
				: objectMapper.writeValueAsString(request.args()));
		entity.setEnvJson(request.env() == null ? null : objectMapper.writeValueAsString(request.env()));
		entity.setUrl(request.url());
		entity.setHeadersJson(request.headers() == null ? null
				: objectMapper.writeValueAsString(request.headers()));
		entity.setEnabled(request.enabled() == null || request.enabled());
	}

	private ServerView toView(McpServerEntity entity) {
		return new ServerView(entity.getId(), entity.getLabel(), entity.getDescription(),
				entity.getTransport(), entity.getCommand(), entity.getUrl(), entity.isEnabled(),
				entity.getStatus(), entity.getLastError(), entity.getLastSyncedAt(),
				clientManager.isConnected(entity.getId()));
	}

	public record ServerView(UUID id, String label, String description, McpTransport transport,
			String command, String url, boolean enabled, String status, String lastError,
			Instant lastSyncedAt, boolean connected) {
	}

	/**
	 * @param command stdio only; must be on {@code harness.mcp.stdio.allowed-commands} to launch
	 * @param url     HTTP transports only
	 */
	public record ServerRequest(@NotBlank String label, String description,
			McpTransport transport, String command, List<String> args,
			java.util.Map<String, String> env, String url, java.util.Map<String, String> headers,
			Boolean enabled) {
	}

	/** @param allowedTools upstream names to import; empty or absent means all of them */
	public record SyncRequest(List<String> allowedTools) {
	}

	public record DiscoveredTool(String name, String description,
			tools.jackson.databind.JsonNode inputSchema) {
	}

	public record DiscoveryResult(boolean succeeded, List<DiscoveredTool> tools, String error) {
	}
}
