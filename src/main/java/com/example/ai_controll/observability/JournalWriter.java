package com.example.ai_controll.observability;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.ai_controll.orchestrator.store.RunEventEntity;
import com.example.ai_controll.orchestrator.store.RunEventRepository;

/**
 * Commits a single journal entry in its own transaction.
 *
 * <p>This exists as a separate bean rather than a method on {@link EventJournal} because Spring's
 * transaction advice is applied by a proxy: a {@code @Transactional} method called from another
 * method of the same object bypasses the proxy entirely and the annotation silently does nothing.
 * Splitting it out is what actually makes {@code REQUIRES_NEW} take effect.
 *
 * <p>The independent transaction matters for two reasons. A live viewer should watch a run progress
 * rather than receive everything at the end; and a run that blows up must still leave behind the
 * evidence of how far it got, which it would not if its entries were rolled back with it.
 */
@Component
class JournalWriter {

	private final RunEventRepository repository;

	JournalWriter(RunEventRepository repository) {
		this.repository = repository;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	void write(RunEventEntity entity) {
		repository.save(entity);
	}
}
