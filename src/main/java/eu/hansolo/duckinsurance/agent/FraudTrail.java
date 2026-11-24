package eu.hansolo.duckinsurance.agent;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


/**
 * What the fraud agent found and checked for each claim, for the expandable details in the claim list. Kept in memory only, like the agent log: after a restart a claim shows its stored reason
 * without the details, and pressing Check fills them again. The claim table is not changed, so the branch merges back without a schema change.
 */
@ApplicationScoped
public class FraudTrail {
    /** One check the model asked for and what the code found. */
    public record Finding(String name, String label, String found) {
    }

    /** The trail of one claim check: the signals the code found, the checks the model asked for, and how many model calls it took. */
    public record Trail(List<PatternSignals.Signal> signals, List<Finding> findings, int modelCalls, String note) {
        public boolean isEmpty() {
            return signals.isEmpty() && findings.isEmpty() && note == null;
        }
    }

    private final Map<Long, Trail> trails = new ConcurrentHashMap<>();


    public void put(long claimId, Trail trail) {
        trails.put(claimId, trail);
    }

    public Trail get(long claimId) {
        return trails.get(claimId);
    }

    public void clear(long claimId) {
        trails.remove(claimId);
    }
}
