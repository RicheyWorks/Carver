package io.github.richeyworks.carver;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The observe half of the loop: exponentially weighted moving averages of <em>actual</em>
 * driving-path row counts, keyed per (path, index) step, consulted by the {@link Planner}
 * the next time the same step is a candidate. Deliberately tiny — the siblings' discipline
 * is "measure, then cut"; this is the measuring. Reuse one {@link Carver} across queries so
 * it accumulates evidence (the same contract as SuperBeefSort's bandit selector).
 */
public final class PlanStats {

    /** EWMA weight for the newest observation. */
    private static final double ALPHA = 0.3;

    private final Map<String, Double> observed = new ConcurrentHashMap<>();

    PlanStats() { }

    /** The refined estimate for {@code stepKey}, or {@code prior} if never observed. */
    long estimate(String stepKey, long prior) {
        Double v = observed.get(stepKey);
        return v == null ? prior : Math.round(v);
    }

    void observe(String stepKey, long actualRows) {
        observed.merge(stepKey, (double) actualRows, (old, x) -> old + ALPHA * (x - old));
    }

    /** Immutable view of every observed step's current moving average. */
    public Map<String, Double> snapshot() {
        return Map.copyOf(observed);
    }
}
