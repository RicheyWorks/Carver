package benchmarks;

import io.github.richeyworks.carver.Carver;
import io.github.richeyworks.carver.Plan;
import io.github.richeyworks.smokehouse.IndexedStore;
import io.github.richeyworks.smokehouse.SmokeHouseOptions;
import io.github.richeyworks.superbeefsort.external.SpillSerializer;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * The measure phase's central question, made falsifiable: <b>does the planner's chosen path
 * track the hand-written best path?</b> Two shapes, chosen so the best drive flips between
 * them:
 *
 * <ul>
 *   <li>{@code NARROW_PRIMARY} — a 100-key primary range plus a broad attribute band; the
 *       oracle-best drive is the primary range walk ({@code countRange} costs it exactly, so
 *       the planner should never miss this one).</li>
 *   <li>{@code NARROW_ATTR} — a single-attribute equality (~1/256 selectivity), no primary
 *       range; the oracle-best drive is the secondary walk, and the naive alternative is a
 *       full scan + filter. The planner starts from a prior here, so this row also measures
 *       what the observe loop buys (the carver is warmed in setup, like the reused-selector
 *       contract).</li>
 * </ul>
 *
 * {@code planned} vs {@code oracleBest} is the planner's overhead + misplan cost;
 * {@code naiveWorst} is what's at stake; {@code planOnly} prices the planning itself.
 * Store is on-disk (real SmokeHouse I/O), seeded, STATIC index tier for determinism.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Fork(1)
public class PlanQualityBenchmark {

    private static final String ATTR = "attr";

    @Param({"NARROW_PRIMARY", "NARROW_ATTR"})
    public String shape;

    @Param({"50000"})
    public int n;

    private Path dir;
    private IndexedStore<Long, String> store;
    private Carver<Long, String> carver;

    private long kLo;
    private long kHi;
    private int aLo;
    private int aHi;
    private boolean usePrimaryRange;

    private static int attrOf(String v) {
        return Integer.parseInt(v.substring(0, 3));
    }

    @Setup(Level.Trial)
    public void setUp() throws IOException {
        dir = Files.createTempDirectory("carver-jmh");
        store = IndexedStore.open(dir,
                        SmokeHouseOptions.of(SpillSerializer.forLongs(), SpillSerializer.forStrings())
                                .indexTier(SmokeHouseOptions.IndexTier.STATIC))
                .secondary(ATTR, Comparator.<Integer>naturalOrder(), PlanQualityBenchmark::attrOf)
                .build();
        Random rnd = new Random(42);
        for (long k = 0; k < n; k++) {
            store.put(k, String.format("%03d:r%d", rnd.nextInt(256), k));
        }
        if ("NARROW_PRIMARY".equals(shape)) {
            usePrimaryRange = true;
            kLo = 0;
            kHi = 99;                 // 100 of n keys
            aLo = 0;
            aHi = 127;                // half the attribute space — deliberately unselective
        } else {
            usePrimaryRange = false;
            aLo = 7;
            aHi = 7;                  // ~n/256 keys
        }
        carver = Carver.over(store);
        for (int i = 0; i < 8; i++) {
            planned();                // warm the observe loop — the reused-carver contract
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() throws IOException {
        store.close();
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    // ── The rows ─────────────────────────────────────────────────────────────────

    /** The planner's choice, end to end (plan + drive + intersect). */
    @Benchmark
    public List<Long> planned() throws IOException {
        return usePrimaryRange
                ? carver.query().keysBetween(kLo, kHi).whereBetween(ATTR, aLo, aHi).keys()
                : carver.query().where(ATTR, aLo).keys();
    }

    /** Hand-written best drive for the shape — what a perfect planner would cost. */
    @Benchmark
    public List<Long> oracleBest() throws IOException {
        if (usePrimaryRange) {
            List<Long> keys = new ArrayList<>();
            store.primary().range(kLo, kHi, (k, v) -> keys.add(k));
            LinkedHashSet<Long> s = new LinkedHashSet<>(keys);
            s.retainAll(store.byAttribute(ATTR, aLo, aHi));
            return new ArrayList<>(s);
        }
        return store.byAttribute(ATTR, aLo, aHi);
    }

    /** Hand-written worst reasonable drive — the cost of picking wrong. */
    @Benchmark
    public List<Long> naiveWorst() throws IOException {
        if (usePrimaryRange) {
            // Drive the broad secondary, then pay a second walk to apply the range.
            LinkedHashSet<Long> s = new LinkedHashSet<>(store.byAttribute(ATTR, aLo, aHi));
            List<Long> range = new ArrayList<>();
            store.primary().range(kLo, kHi, (k, v) -> range.add(k));
            s.retainAll(range);
            return new ArrayList<>(s);
        }
        // Full scan + filter, ignoring the secondary entirely.
        List<Long> keys = new ArrayList<>();
        store.primary().range(store.primary().firstKey(), store.primary().lastKey(),
                (k, v) -> {
                    if (attrOf(v) == aLo) {
                        keys.add(k);
                    }
                });
        return keys;
    }

    /** The price of planning alone (no execution) — should be small against any walk. */
    @Benchmark
    public Plan planOnly() {
        return usePrimaryRange
                ? carver.query().keysBetween(kLo, kHi).whereBetween(ATTR, aLo, aHi).explain()
                : carver.query().where(ATTR, aLo).explain();
    }
}
