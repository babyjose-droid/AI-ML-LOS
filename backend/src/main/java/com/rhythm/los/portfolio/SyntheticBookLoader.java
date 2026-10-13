package com.rhythm.los.portfolio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Loads the synthetic loan book once, when demo data is on and no book exists yet. */
@Component
public class SyntheticBookLoader {
    private static final Logger log = LoggerFactory.getLogger(SyntheticBookLoader.class);
    private final HistoricalLoanRepository repo;
    private final boolean enabled;
    private final int size;

    public SyntheticBookLoader(HistoricalLoanRepository repo, @Value("${rhythm.synthetic-book.enabled:${rhythm.demo-data:false}}") boolean enabled,
                               @Value("${rhythm.synthetic-book.size:2000}") int size) {
        this.repo = repo;
        this.enabled = enabled;
        this.size = size;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(0)
    public void load() {
        if (!enabled || repo.count() > 0) return;
        repo.saveAll(SyntheticBook.entities(size, 20261013L));
        log.info("Loaded {} synthetic historical loans for policy simulation and model training", size);
    }
}
