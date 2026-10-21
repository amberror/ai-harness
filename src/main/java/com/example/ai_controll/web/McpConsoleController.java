package com.example.ai_controll.web;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.example.ai_controll.config.HarnessProperties;
import com.example.ai_controll.mcp.McpClientManager;
import com.example.ai_controll.mcp.McpServerEntity;
import com.example.ai_controll.mcp.McpServerRepository;
import com.example.ai_controll.mcp.McpToolSyncService;
import com.example.ai_controll.mcp.McpTransport;

import io.modelcontextprotocol.spec.McpSchema;

/** Console pages for configuring MCP servers and importing their tools. */
@Controller
public class McpConsoleController {

	private final McpServerRepository servers;

	private final McpClientManager clientManager;

	private final McpToolSyncService syncService;

	private final HarnessProperties properties;

	public McpConsoleController(McpServerRepository servers, McpClientManager clientManager,
			McpToolSyncService syncService, HarnessProperties properties) {
		this.servers = servers;
		this.clientManager = clientManager;
		this.syncService = syncService;
		this.properties = properties;
	}

	@GetMapping("/mcp")
	public String list(Model model) {
		model.addAttribute("baseUrl", properties.lmStudio().baseUrl());
		model.addAttribute("servers", servers.findAllByOrderByLabelAsc());
		model.addAttribute("transports", McpTransport.values());
		model.addAttribute("allowedCommands", properties.mcp().stdio().allowedCommands());
		return "mcp";
	}

	@GetMapping("/mcp/{id}")
	public String detail(@PathVariable UUID id, Model model) {
		model.addAttribute("baseUrl", properties.lmStudio().baseUrl());
		return servers.findById(id).map(server -> {
			model.addAttribute("server", server);
			model.addAttribute("connected", clientManager.isConnected(id));
			try {
				// Discovery is shown before import so you can see what a server offers and choose,
				// rather than importing twenty tools and only then discovering the model cannot cope.
				List<McpSchema.Tool> discovered = clientManager.listTools(server);
				model.addAttribute("discovered", discovered);
			}
			catch (RuntimeException ex) {
				model.addAttribute("discoveryError", ex.getMessage());
				model.addAttribute("discovered", List.of());
			}
			return "mcp-detail";
		}).orElse("redirect:/mcp");
	}

	@PostMapping("/mcp")
	public String create(@RequestParam String label,
			@RequestParam McpTransport transport,
			@RequestParam(required = false) String command,
			@RequestParam(required = false) String argsJson,
			@RequestParam(required = false) String url,
			@RequestParam(required = false) String description,
			RedirectAttributes redirect) {
		if (servers.existsByLabel(label)) {
			redirect.addFlashAttribute("error", "A server labelled '%s' already exists".formatted(label));
			return "redirect:/mcp";
		}
		McpServerEntity server = new McpServerEntity(UUID.randomUUID(), label, transport, Instant.now());
		server.setDescription(description);
		server.setCommand(command);
		server.setArgsJson((argsJson == null || argsJson.isBlank()) ? null : argsJson);
		server.setUrl(url);
		servers.save(server);
		redirect.addFlashAttribute("notice", "Added MCP server '%s'".formatted(label));
		return "redirect:/mcp/" + server.getId();
	}

	@PostMapping("/mcp/{id}/sync")
	public String sync(@PathVariable UUID id,
			@RequestParam(required = false) List<String> tools,
			RedirectAttributes redirect) {
		servers.findById(id).ifPresent(server -> {
			McpToolSyncService.SyncResult result = syncService.sync(server,
					tools == null ? Set.of() : Set.copyOf(tools));
			if (result.succeeded()) {
				redirect.addFlashAttribute("notice",
						"Imported %d, unchanged %d, skipped %d".formatted(result.imported().size(),
								result.unchanged().size(), result.skipped().size()));
			}
			else {
				redirect.addFlashAttribute("error", result.error());
			}
		});
		return "redirect:/mcp/" + id;
	}

	@PostMapping("/mcp/{id}/enabled")
	public String setEnabled(@PathVariable UUID id, @RequestParam boolean value,
			RedirectAttributes redirect) {
		servers.findById(id).ifPresent(server -> {
			server.setEnabled(value);
			servers.save(server);
			if (!value) {
				// A disabled server should not keep a live connection, least of all a child process.
				clientManager.disconnect(id);
			}
		});
		return "redirect:/mcp";
	}

	@PostMapping("/mcp/{id}/delete")
	public String delete(@PathVariable UUID id, RedirectAttributes redirect) {
		clientManager.disconnect(id);
		servers.deleteById(id);
		redirect.addFlashAttribute("notice",
				"Removed the server. Tools imported from it remain in the registry and will report "
						+ "that their server is gone if called.");
		return "redirect:/mcp";
	}
}
