package com.example.ai_controll.conversation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The ordered conversation the harness owns.
 *
 * <p>Owning the history is the whole reason the harness drives the stateless OpenAI-compatible
 * endpoint rather than LM Studio's stateful native one: compaction, forking, replay and A/B
 * comparison all require being able to decide exactly what the model sees on every single step.
 *
 * <p>Each message is stored together with the loop step it was added during, because forking needs
 * to cut the conversation at a step boundary. They are kept in one list of pairs rather than two
 * parallel lists: an earlier version used parallel lists and a constructor that appended to only one
 * of them, which desynchronised them permanently and made every transcript fail to persist. The
 * failure was caught and logged rather than thrown, so it was invisible until a fork came back empty.
 * One list cannot fall out of step with itself.
 */
public class Transcript {

	private final List<Entry> entries = new ArrayList<>();

	private int currentStep;

	public Transcript() {
	}

	public Transcript(String systemPrompt) {
		if (systemPrompt != null && !systemPrompt.isBlank()) {
			add(Message.system(systemPrompt));
		}
	}

	public static Transcript of(String systemPrompt) {
		return new Transcript(systemPrompt);
	}

	public Transcript add(Message message) {
		this.entries.add(new Entry(message, this.currentStep));
		return this;
	}

	/** Restores a message at a known step, used when rebuilding from storage. */
	public Transcript addAt(Message message, int stepNo) {
		this.entries.add(new Entry(message, stepNo));
		return this;
	}

	public Transcript addAll(List<Message> toAdd) {
		toAdd.forEach(this::add);
		return this;
	}

	/** Sets the step that subsequent messages will be stamped with. */
	public void currentStep(int stepNo) {
		this.currentStep = stepNo;
	}

	/** Immutable snapshot; callers must not be able to mutate the transcript behind its back. */
	public List<Message> messages() {
		return this.entries.stream().map(Entry::message).toList();
	}

	/** The messages with the step each belongs to, for persistence and forking. */
	public List<Entry> entries() {
		return List.copyOf(this.entries);
	}

	public int size() {
		return this.entries.size();
	}

	public boolean isEmpty() {
		return this.entries.isEmpty();
	}

	public Optional<Message> last() {
		return this.entries.isEmpty() ? Optional.empty()
				: Optional.of(this.entries.get(this.entries.size() - 1).message());
	}

	/** The system prompt, if one was set. */
	public Optional<Message> systemMessage() {
		return this.entries.stream().map(Entry::message)
				.filter(message -> message.role() == Role.SYSTEM).findFirst();
	}

	/** Replaces the whole body, keeping the object identity. Used by compaction. */
	public void replaceAll(List<Message> replacement) {
		this.entries.clear();
		replacement.forEach(this::add);
	}

	/** A detached copy, for forking a run without disturbing the original. */
	public Transcript copy() {
		Transcript copy = new Transcript();
		copy.entries.addAll(this.entries);
		copy.currentStep = this.currentStep;
		return copy;
	}

	/** A message and the loop step it was added during. */
	public record Entry(Message message, int stepNo) {
	}
}
