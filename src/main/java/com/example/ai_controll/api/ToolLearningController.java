package com.example.ai_controll.api;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.ai_controll.tool.learning.ToolCoach;
import com.example.ai_controll.tool.learning.ToolUsageAnalyzer;
import com.example.ai_controll.tool.learning.ToolUsageReport;

import tools.jackson.databind.node.ObjectNode;

/**
 * What the journal knows about how the model copes with each tool, and what to do about it.
 *
 * <p>The reads are free and change nothing. The writes save a new tool version and are deliberately
 * separate calls: a harness that retuned its own tools while measuring them would be an instrument
 * adjusting itself mid-reading, and the version history exists so that a wording change can be
 * blamed for a change in pass rate afterwards.
 */
@RestController
@RequestMapping("/api/tools")
public class ToolLearningController {

	private final ToolUsageAnalyzer analyzer;

	private final ToolCoach coach;

	public ToolLearningController(ToolUsageAnalyzer analyzer, ToolCoach coach) {
		this.analyzer = analyzer;
		this.coach = coach;
	}

	/** Every tool the journal has seen used, the one the model fights with most listed first. */
	@GetMapping("/learning")
	public List<ToolUsageReport> reports() {
		return analyzer.reportAll();
	}

	@GetMapping("/{name}/learning")
	public ToolUsageReport report(@PathVariable String name) {
		return analyzer.report(name);
	}

	/**
	 * The schema as it would be saved with examples added, without saving it.
	 *
	 * <p>Worth looking at before applying: the examples are real calls the model made, so they carry
	 * whatever values it happened to use, and an example teaching the wrong value is worse than none.
	 */
	@GetMapping("/{name}/learning/examples")
	public ResponseEntity<ObjectNode> proposeExamples(@PathVariable String name) {
		return coach.proposeExamples(name).map(ResponseEntity::ok)
				.orElseGet(() -> ResponseEntity.noContent().build());
	}

	@PostMapping("/{name}/learning/examples")
	public Map<String, Object> applyExamples(@PathVariable String name) {
		var saved = coach.applyExamples(name);
		return Map.of("tool", name, "version", saved.getVersion(),
				"note", "Run a batch against the previous version to see whether it helped.");
	}

	/**
	 * Drafts a replacement description from the observed failures. Returns it; does not apply it.
	 *
	 * <p>Slow — it is a model call — and it goes through the same single-generation gate as a run, so
	 * it queues behind anything already using the model.
	 */
	@PostMapping("/{name}/learning/description")
	public ToolCoach.Draft draftDescription(@PathVariable String name) {
		return coach.draftDescription(name);
	}

	/**
	 * Saves a description as a new version.
	 *
	 * <p>Takes the text rather than re-drafting, so that what gets saved is what someone read — an
	 * endpoint that drafted and applied in one step would be applying something nobody had seen.
	 */
	@PostMapping("/{name}/learning/description/apply")
	public Map<String, Object> applyDescription(@PathVariable String name,
			@RequestBody ApplyDescription request) {
		var saved = coach.applyDescription(name, request.description());
		return Map.of("tool", name, "version", saved.getVersion(),
				"note", "Not yet measured. Compare a batch against version " + (saved.getVersion() - 1)
						+ " before believing it is better.");
	}

	public record ApplyDescription(String description) {
	}
}
