package io.github.richeyworks.carver;

import io.github.richeyworks.smokehouse.IndexedStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Carver — the fourth engine of the ecosystem: a cost-based read planner over a SmokeHouse
 * {@link IndexedStore}. SmokeHouse preserves, CSRBT orders, SuperBeefSort feeds; Carver
 * decides <b>how to read</b>. Given a declarative {@link Query}, it costs the candidate
 * access paths (primary range walk, secondary walk, interval walk, full scan), drives the
 * cheapest, intersects the rest, applies any residual filter, and records the actual row
 * count so the next plan is better informed ({@link PlanStats}).
 *
 * <p><b>Composition, not modification</b> — the same stance {@code IndexedStore} takes over
 * {@code SmokeHouse}: Carver is built strictly over public surfaces ({@code byAttribute},
 * {@code stab}, {@code overlapping}, {@code primary().range}, {@code countRange},
 * {@code size}) and holds no lock of its own; each underlying walk synchronizes exactly as
 * it always did. A query is therefore <em>per-walk</em> consistent, not transactional across
 * walks — writes racing a multi-predicate query can produce a cut no single instant exhibited.
 * Same honesty as the siblings: documented, not hidden.</p>
 *
 * <p>Reuse one {@code Carver} per store so the observe loop accumulates evidence — the same
 * contract as SuperBeefSort's bandit selector. No background threads; caller-cadenced like
 * every control loop in the ring.</p>
 */
public final class Carver<K, V> {

    private final IndexedStore<K, V> store;
    private final PlanStats stats = new PlanStats();
    private final Planner<K, V> planner;

    private Carver(IndexedStore<K, V> store) {
        this.store = store;
        this.planner = new Planner<>(store, stats);
    }

    /** A carver over {@code store}. One per store; reuse it so estimates refine. */
    public static <K, V> Carver<K, V> over(IndexedStore<K, V> store) {
        return new Carver<>(Objects.requireNonNull(store, "store"));
    }

    /** Start a new declarative cut. */
    public Query<K, V> query() {
        return new Query<>(this);
    }

    /** The observed actuals refining this carver's estimates. */
    public PlanStats stats() {
        return stats;
    }

    Plan plan(Query<K, V> q) {
        return planner.plan(q);
    }

    List<K> run(Query<K, V> q) throws IOException {
        Plan plan = planner.plan(q);
        List<K> driving = drive(plan, q);
        stats.observe(Planner.stepKey(plan.path(), plan.index()), driving.size());

        LinkedHashSet<K> survivors = new LinkedHashSet<>(driving);

        // Intersect every non-driving predicate. Public-API key-set intersection keeps the
        // executor honest without needing the store's extractors or comparator; the planner's
        // job is to make the driving set small so these walks touch little.
        boolean attrSkipped = false;
        for (Query.AttrPred p : q.attrs) {
            if (!attrSkipped && plan.path() == AccessPath.SECONDARY_RANGE
                    && p.index().equals(plan.index())) {
                attrSkipped = true;
                continue;
            }
            if (survivors.isEmpty()) {
                break;
            }
            survivors.retainAll(store.byAttribute(p.index(), p.lo(), p.hi()));
        }
        boolean spanSkipped = false;
        for (Query.SpanPred p : q.spans) {
            AccessPath path = p.stab() ? AccessPath.INTERVAL_STAB : AccessPath.INTERVAL_OVERLAP;
            if (!spanSkipped && plan.path() == path && p.index().equals(plan.index())) {
                spanSkipped = true;
                continue;
            }
            if (survivors.isEmpty()) {
                break;
            }
            survivors.retainAll(spanWalk(p));
        }
        if (q.hasPrimaryRange() && plan.path() != AccessPath.PRIMARY_RANGE
                && !survivors.isEmpty()) {
            survivors.retainAll(collectRange(q.primaryLo, q.primaryHi));
        }

        List<K> out = new ArrayList<>(Math.min(survivors.size(), 1024));
        for (K k : survivors) {
            if (out.size() >= q.limit) {
                break;
            }
            if (q.filter != null) {
                V v = store.get(k);
                if (v == null || !q.filter.test(k, v)) {
                    continue;
                }
            }
            out.add(k);
        }
        return out;
    }

    void fetch(Query<K, V> q, BiConsumer<K, V> consumer) throws IOException {
        for (K k : run(q)) {
            V v = store.get(k);
            if (v != null) {
                consumer.accept(k, v);
            }
        }
    }

    private List<K> drive(Plan plan, Query<K, V> q) throws IOException {
        switch (plan.path()) {
            case PRIMARY_RANGE:
                return collectRange(q.primaryLo, q.primaryHi);
            case SECONDARY_RANGE:
                for (Query.AttrPred p : q.attrs) {
                    if (p.index().equals(plan.index())) {
                        return store.byAttribute(p.index(), p.lo(), p.hi());
                    }
                }
                throw new IllegalStateException("plan names secondary '" + plan.index()
                        + "' but no such predicate exists");
            case INTERVAL_OVERLAP:
            case INTERVAL_STAB:
                boolean stab = plan.path() == AccessPath.INTERVAL_STAB;
                for (Query.SpanPred p : q.spans) {
                    if (p.stab() == stab && p.index().equals(plan.index())) {
                        return spanWalk(p);
                    }
                }
                throw new IllegalStateException("plan names interval '" + plan.index()
                        + "' but no such predicate exists");
            case FULL_SCAN:
            default:
                if (store.primary().size() == 0) {
                    return List.of();
                }
                return collectRange(store.primary().firstKey(), store.primary().lastKey());
        }
    }

    /**
     * One interval walk, routed to the store overload the predicate targets: {@code int}
     * predicates unbox explicitly so overload resolution picks the {@code int} surface;
     * typed predicates flow through the generic surface (the store's comparator does the
     * type checking).
     */
    private List<K> spanWalk(Query.SpanPred p) {
        if (p.typed()) {
            return p.stab() ? store.stab(p.index(), p.lo())
                            : store.overlapping(p.index(), p.lo(), p.hi());
        }
        int lo = ((Integer) p.lo()).intValue();
        int hi = ((Integer) p.hi()).intValue();
        return p.stab() ? store.stab(p.index(), lo)
                        : store.overlapping(p.index(), lo, hi);
    }

    private List<K> collectRange(K lo, K hi) throws IOException {
        List<K> keys = new ArrayList<>();
        store.primary().range(lo, hi, (k, v) -> keys.add(k));
        return keys;
    }
}
