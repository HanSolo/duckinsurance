package eu.hansolo.duckinsurance.agent;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;


/**
 * What the agents of this application say and do, in the order it happened. The conversation page merges this log with the logs of the two clinics.
 * Kept in memory only (like the demo data it is lost on a restart). kind is one of REQUEST, ANSWER, THOUGHT, POLICY, ACTION, ERROR.
 */
@ApplicationScoped
public class AgentLog {
    public static final String AGENT = "Insurance Agent";

    /** One line of the conversation. */
    public record Entry(long seq, long at, String referralId, String from, String to, String kind, String text) {
    }

    private static final int MAX_ENTRIES = 500;

    private final List<Entry> entries = new CopyOnWriteArrayList<>();
    private final AtomicLong  seq     = new AtomicLong();


    public void add(String referralId, String from, String to, String kind, String text) {
        entries.add(new Entry(seq.incrementAndGet(), Instant.now().toEpochMilli(), referralId == null ? "" : referralId, from, to == null ? "" : to, kind, text));
        while (entries.size() > MAX_ENTRIES) entries.remove(0);
    }

    /** A line spoken by this application's agent. */
    public void say(String referralId, String to, String kind, String text) {
        add(referralId, AGENT, to, kind, text);
    }

    public void clear() {
        entries.clear();
    }

    public JsonArray toJson() {
        JsonArrayBuilder array = Json.createArrayBuilder();
        for (Entry e : entries) array.add(Json.createObjectBuilder().add("seq", e.seq()).add("at", e.at()).add("referralId", e.referralId()).add("from", e.from()).add("to", e.to()).add("kind", e.kind()).add("text", e.text()));
        return array.build();
    }
}
