package io.github.richeyworks.carver;

import io.github.richeyworks.smokehouse.IndexedStore;

import java.util.ArrayList;
import java.util.List;

/**
 * Cost-based path choice from public surfaces only. {@link AccessPath#PRIMARY_RANGE} and
 * {@link AccessPath#FULL_SCAN} are costed <em>exactly</em> ({@code countRange} / {@code size}
 * — CSRBT's order statistics doing the job a histogram does in a classical planner, with no
 * extra structure to maintain). Index paths start from an optimistic prior and are refined by
 * {@link PlanStats} observation — SuperBeefSort's profile → select → execute → observe loop,
 * generalized from "which sort" to "which access path". The cheapest estimate drives; every
 * other predicate becomes a key-set intersection.
 */
final class Planner<K, V> {

    /** Prior for an equality / stab step, as a divisor of store size. */
    private static final int EQ_FRACTION = 16;
    /** Prior for a range / overlap step. */
    private static final int RANGE_FRACTION = 4;

    private final IndexedStore<K, V> store;
    private final PlanStats stats;

    Planner(IndexedStore<K, V> store, PlanStats stats) {
        this.store = store;
        this.stats = stats;
    }

    Plan plan(Query<K, V> q) {
        long size = store.primary().size();
        List<Candidate> candidates = new ArrayList<>();

        for (Query.AttrPred p : q.attrs) {
            boolean eq = p.lo().equals(p.hi());
            long prior = Math.max(1, size / (eq ? EQ_FRACTION : RANGE_FRACTION));
            candidates.add(new Candidate(AccessPath.SECONDARY_RANGE, p.index(),
                    stats.estimate(stepKey(AccessPath.SECONDARY_RANGE, p.index()), prior)));
        }
        for (Query.SpanPred p : q.spans) {
            AccessPath path = p.stab() ? AccessPath.INTERVAL_STAB : AccessPath.INTERVAL_OVERLAP;
            long prior = Math.max(1, size / (p.stab() ? EQ_FRACTION : RANGE_FRACTION));
            candidates.add(new Candidate(path, p.index(),
                    stats.estimate(stepKey(path, p.index()), prior)));
        }
        if (q.hasPrimaryRange()) {
            candidates.add(new Candidate(AccessPath.PRIMARY_RANGE, null,
                    store.primary().countRange(q.primaryLo, q.primaryHi)));
        }

        if (candidates.isEmpty()) {
            return new Plan(AccessPath.FULL_SCAN, null, size, List.of(), q.filter != null);
        }

        Candidate best = candidates.get(0);
        for (Candidate c : candidates) {
            if (c.estimate() < best.estimate()) {
                best = c;
            }
        }
        List<String> rest = new ArrayList<>();
        boolean skipped = false;
        for (Candidate c : candidates) {
            if (!skipped && c == best) {
                skipped = true;
                continue;
            }
            rest.add(c.path() + (c.index() == null ? "" : "(" + c.index() + ")"));
        }
        return new Plan(best.path(), best.index(), best.estimate(),
                List.copyOf(rest), q.filter != null);
    }

    static String stepKey(AccessPath path, String index) {
        return path + ":" + (index == null ? "" : index);
    }

    private record Candidate(AccessPath path, String index, long estimate) { }
}
