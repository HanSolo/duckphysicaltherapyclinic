package eu.hansolo.duckphysicaltherapyclinic.agent;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;


/**
 * What this clinic's agent says and does, in order. The conversation page of DuckInsurance merges it with the logs of the other two applications.
 * In memory only. kind is one of REQUEST, ANSWER, THOUGHT, POLICY, ACTION, ERROR.
 */
@ApplicationScoped
public class AgentLog {
    public static final String AGENT = "Therapy Agent";

    public record Entry(long seq, long at, String referralId, String from, String to, String kind, String text) {}

    private static final int MAX_ENTRIES = 500;

    private final List<Entry> entries = new CopyOnWriteArrayList<>();
    private final AtomicLong  seq     = new AtomicLong();


    public void add(final String referralId, final String from, final String to, final String kind, final String text) {
        entries.add(new Entry(seq.incrementAndGet(), Instant.now().toEpochMilli(), referralId == null ? "" : referralId, from, to == null ? "" : to, kind, text));
        while (entries.size() > MAX_ENTRIES) { entries.remove(0); }
    }

    /** A line spoken by this clinic's agent. */
    public void say(final String referralId, final String to, final String kind, final String text) { add(referralId, AGENT, to, kind, text); }

    public List<Entry> list() { return List.copyOf(entries); }

    public void clear() { entries.clear(); }
}
