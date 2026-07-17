package io.github.richeyworks.carver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;

/**
 * One declarative cut over an {@link io.github.richeyworks.smokehouse.IndexedStore}: any mix
 * of a primary-key range, attribute predicates (over declared secondaries), interval
 * predicates (over declared interval indexes), a residual record filter, and a limit. Built
 * fluently, executed by {@link Carver} — {@link #keys()}, {@link #records}, or
 * {@link #explain()} for the plan without the work.
 *
 * <p>All index-predicate names must match indexes declared on the store; unknown names fail
 * loudly at execution, exactly as {@code IndexedStore} itself does.</p>
 */
public final class Query<K, V> {

    record AttrPred(String index, Object lo, Object hi) { }

    record SpanPred(String index, int lo, int hi, boolean stab) { }

    private final Carver<K, V> carver;

    K primaryLo;
    K primaryHi;                                  // both null = no primary range
    final List<AttrPred> attrs = new ArrayList<>();
    final List<SpanPred> spans = new ArrayList<>();
    BiPredicate<K, V> filter;
    int limit = Integer.MAX_VALUE;

    Query(Carver<K, V> carver) {
        this.carver = carver;
    }

    /** Closed primary-key range {@code [lo, hi]}. */
    public Query<K, V> keysBetween(K lo, K hi) {
        this.primaryLo = Objects.requireNonNull(lo, "lo");
        this.primaryHi = Objects.requireNonNull(hi, "hi");
        return this;
    }

    /** Attribute equality over the secondary named {@code index}. */
    public Query<K, V> where(String index, Object value) {
        return whereBetween(index, value, value);
    }

    /** Closed attribute range over the secondary named {@code index}. */
    public Query<K, V> whereBetween(String index, Object lo, Object hi) {
        attrs.add(new AttrPred(Objects.requireNonNull(index, "index"),
                Objects.requireNonNull(lo, "lo"), Objects.requireNonNull(hi, "hi")));
        return this;
    }

    /** Records whose {@code index} span overlaps {@code [lo, hi]} (closed). */
    public Query<K, V> overlapping(String index, int lo, int hi) {
        if (lo > hi) {
            throw new IllegalArgumentException("lo " + lo + " > hi " + hi);
        }
        spans.add(new SpanPred(Objects.requireNonNull(index, "index"), lo, hi, false));
        return this;
    }

    /** Records whose {@code index} span contains {@code point}. */
    public Query<K, V> stabbing(String index, int point) {
        spans.add(new SpanPred(Objects.requireNonNull(index, "index"), point, point, true));
        return this;
    }

    /**
     * Residual predicate over the full record, applied last; forces one value fetch per
     * surviving key, so prefer index predicates where one exists.
     */
    public Query<K, V> filter(BiPredicate<K, V> predicate) {
        this.filter = Objects.requireNonNull(predicate, "predicate");
        return this;
    }

    /** Keep at most {@code n} results (applied last, in driving-path order). */
    public Query<K, V> limit(int n) {
        if (n < 0) {
            throw new IllegalArgumentException("limit " + n + " < 0");
        }
        this.limit = n;
        return this;
    }

    boolean hasPrimaryRange() {
        return primaryLo != null;
    }

    /** The plan the executor would drive, without running it. */
    public Plan explain() {
        return carver.plan(this);
    }

    /** Execute; matching primary keys in driving-path order. */
    public List<K> keys() throws IOException {
        return carver.run(this);
    }

    /** Execute and hand each surviving record to {@code consumer}. */
    public void records(BiConsumer<K, V> consumer) throws IOException {
        carver.fetch(this, consumer);
    }
}
