package eu.hansolo.duckinsurance.audit;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/** In-memory ring buffer shown on the dashboard. */
@ApplicationScoped
public class AuditLog {

    private static final int MAX_ENTRIES = 50;

    private final ConcurrentLinkedDeque<AuditEntry> entries = new ConcurrentLinkedDeque<>();


    public void record(String actor, String message) {
        entries.addFirst(new AuditEntry(actor, message));
        while (entries.size() > MAX_ENTRIES) entries.pollLast();
    }

    public List<AuditEntry> latest(int limit) {
        return new ArrayList<>(entries).stream().limit(limit).toList();
    }
}
