package io.github.richeyworks.carver;

import io.github.richeyworks.smokehouse.IndexedStore;
import io.github.richeyworks.smokehouse.SmokeHouseOptions;
import io.github.richeyworks.superbeefsort.external.SpillSerializer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Double-oracle tests in the house style: every query result is checked against a brute-force
 * scan of a {@code TreeMap} reference. Values look like {@code "aaa:sssss:eeeee"} — attribute
 * {@code aaa}, span {@code [sssss, eeeee]} — so one string carries every indexed facet.
 * Seeded and deterministic throughout.
 */
class CarverTest {

    private static final String ATTR = "attr";
    private static final String SPAN = "span";
    private static final String WHEN = "when";                        // typed (Long) twin of SPAN
    /** Base far above Integer.MAX_VALUE so any int truncation in the typed path fails loudly. */
    private static final long EPOCH = 1_720_000_000_000L;
    private static final long SEED = 42;
    private static final int N = 300;

    private static String value(int attr, int start, int end) {
        return String.format("%03d:%05d:%05d", attr, start, end);
    }

    private static int attrOf(String v)  { return Integer.parseInt(v.substring(0, 3)); }
    private static int startOf(String v) { return Integer.parseInt(v.substring(4, 9)); }
    private static int endOf(String v)   { return Integer.parseInt(v.substring(10, 15)); }

    private static SmokeHouseOptions<Long, String> opts() {
        return SmokeHouseOptions.of(SpillSerializer.forLongs(), SpillSerializer.forStrings())
                .indexTier(SmokeHouseOptions.IndexTier.STATIC);   // deterministic for oracle runs
    }

    private static IndexedStore<Long, String> open(Path dir) throws IOException {
        return IndexedStore.open(dir, opts())
                .secondary(ATTR, Comparator.<Integer>naturalOrder(), CarverTest::attrOf)
                .interval(SPAN, CarverTest::startOf, CarverTest::endOf)
                .interval(WHEN, Comparator.<Long>naturalOrder(),
                        v -> EPOCH + startOf(v), v -> EPOCH + endOf(v))
                .build();
    }

    /** Seeded put/overwrite/delete mix, mirrored into the oracle. */
    private static TreeMap<Long, String> populate(IndexedStore<Long, String> store)
            throws IOException {
        TreeMap<Long, String> oracle = new TreeMap<>();
        Random rnd = new Random(SEED);
        for (int i = 0; i < N; i++) {
            long key = rnd.nextInt(N);                            // collisions = overwrites
            int start = rnd.nextInt(10_000);
            String v = value(rnd.nextInt(8), start, start + rnd.nextInt(500));
            store.put(key, v);
            oracle.put(key, v);
        }
        for (int i = 0; i < N / 10; i++) {                        // exercise index retraction
            long key = rnd.nextInt(N);
            store.delete(key);
            oracle.remove(key);
        }
        return oracle;
    }

    private static List<Long> sorted(List<Long> keys) {
        List<Long> copy = new ArrayList<>(keys);
        copy.sort(null);
        return copy;
    }

    @Test
    void attributeEqualityMatchesOracle(@TempDir Path dir) throws IOException {
        try (IndexedStore<Long, String> store = open(dir)) {
            TreeMap<Long, String> oracle = populate(store);
            Carver<Long, String> carver = Carver.over(store);
            for (int a = 0; a < 8; a++) {
                List<Long> expected = new ArrayList<>();
                for (Map.Entry<Long, String> e : oracle.entrySet()) {
                    if (attrOf(e.getValue()) == a) {
                        expected.add(e.getKey());
                    }
                }
                List<Long> actual = carver.query().where(ATTR, a).keys();
                assertEquals(expected, sorted(actual), "attr=" + a);
            }
        }
    }

    @Test
    void conjunctionAcrossAllThreeIndexKindsMatchesOracle(@TempDir Path dir) throws IOException {
        try (IndexedStore<Long, String> store = open(dir)) {
            TreeMap<Long, String> oracle = populate(store);
            Carver<Long, String> carver = Carver.over(store);
            long kLo = 40, kHi = 260;
            int aLo = 1, aHi = 5, sLo = 2_000, sHi = 7_000;

            List<Long> expected = new ArrayList<>();
            for (Map.Entry<Long, String> e : oracle.subMap(kLo, true, kHi, true).entrySet()) {
                String v = e.getValue();
                int a = attrOf(v);
                boolean overlaps = startOf(v) <= sHi && endOf(v) >= sLo;
                if (a >= aLo && a <= aHi && overlaps) {
                    expected.add(e.getKey());
                }
            }
            List<Long> actual = carver.query()
                    .keysBetween(kLo, kHi)
                    .whereBetween(ATTR, aLo, aHi)
                    .overlapping(SPAN, sLo, sHi)
                    .keys();
            assertEquals(expected, sorted(actual));
        }
    }

    @Test
    void stabbingWithResidualFilterMatchesOracle(@TempDir Path dir) throws IOException {
        try (IndexedStore<Long, String> store = open(dir)) {
            TreeMap<Long, String> oracle = populate(store);
            Carver<Long, String> carver = Carver.over(store);
            int point = 5_000;

            List<Long> expected = new ArrayList<>();
            for (Map.Entry<Long, String> e : oracle.entrySet()) {
                String v = e.getValue();
                if (startOf(v) <= point && endOf(v) >= point && attrOf(v) % 2 == 0) {
                    expected.add(e.getKey());
                }
            }
            List<Long> actual = carver.query()
                    .stabbing(SPAN, point)
                    .filter((k, v) -> attrOf(v) % 2 == 0)
                    .keys();
            assertEquals(expected, sorted(actual));
        }
    }

    @Test
    void bareFilterFallsBackToFullScan(@TempDir Path dir) throws IOException {
        try (IndexedStore<Long, String> store = open(dir)) {
            TreeMap<Long, String> oracle = populate(store);
            Carver<Long, String> carver = Carver.over(store);

            Query<Long, String> q = carver.query().filter((k, v) -> attrOf(v) == 3);
            assertEquals(AccessPath.FULL_SCAN, q.explain().path());
            assertEquals(oracle.size(), q.explain().estimatedRows());

            List<Long> expected = new ArrayList<>();
            for (Map.Entry<Long, String> e : oracle.entrySet()) {
                if (attrOf(e.getValue()) == 3) {
                    expected.add(e.getKey());
                }
            }
            assertEquals(expected, sorted(q.keys()));
        }
    }

    @Test
    void primaryRangeIsCostedExactlyAndDrivesWhenCheapest(@TempDir Path dir) throws IOException {
        try (IndexedStore<Long, String> store = open(dir)) {
            populate(store);
            Carver<Long, String> carver = Carver.over(store);
            // A one-key range is always cheaper than any index prior.
            Plan plan = carver.query()
                    .keysBetween(10L, 10L)
                    .whereBetween(ATTR, 0, 7)
                    .explain();
            assertEquals(AccessPath.PRIMARY_RANGE, plan.path());
            assertEquals(store.primary().countRange(10L, 10L), plan.estimatedRows());
            assertEquals(1, plan.intersections().size());
        }
    }

    @Test
    void observeLoopRefinesEstimates(@TempDir Path dir) throws IOException {
        try (IndexedStore<Long, String> store = open(dir)) {
            TreeMap<Long, String> oracle = populate(store);
            Carver<Long, String> carver = Carver.over(store);

            long prior = carver.query().where(ATTR, 3).explain().estimatedRows();
            List<Long> first = carver.query().where(ATTR, 3).keys();
            long refined = carver.query().where(ATTR, 3).explain().estimatedRows();

            assertTrue(carver.stats().snapshot().containsKey("SECONDARY_RANGE:" + ATTR),
                    "actual row count should be on the record");
            // The EWMA moved toward the observed actual (strictly, unless the prior was exact).
            long actual = first.size();
            assertTrue(Math.abs(refined - actual) <= Math.abs(prior - actual),
                    "estimate should not move away from the observed actual");
            assertTrue(oracle.size() > 0, "populate() must leave live records");
        }
    }

    @Test
    void typedIntervalPredicatesMatchOracle(@TempDir Path dir) throws IOException {
        try (IndexedStore<Long, String> store = open(dir)) {
            TreeMap<Long, String> oracle = populate(store);
            Carver<Long, String> carver = Carver.over(store);
            long qlo = EPOCH + 2_000, qhi = EPOCH + 7_000;

            List<Long> expected = new ArrayList<>();
            for (Map.Entry<Long, String> e : oracle.entrySet()) {
                String v = e.getValue();
                long s = EPOCH + startOf(v);
                long t = EPOCH + endOf(v);
                if (s <= qhi && t >= qlo && attrOf(v) <= 3) {
                    expected.add(e.getKey());
                }
            }
            List<Long> actual = carver.query()
                    .overlapping(WHEN, qlo, qhi)                      // typed (Long) span walk
                    .whereBetween(ATTR, 0, 3)
                    .keys();
            assertEquals(expected, sorted(actual));

            // The typed index is SPAN shifted by EPOCH, so typed stab must agree with int stab.
            assertEquals(sorted(carver.query().stabbing(SPAN, 5_000).keys()),
                         sorted(carver.query().stabbing(WHEN, EPOCH + 5_000L).keys()));
        }
    }

    @Test
    void limitIsRespected(@TempDir Path dir) throws IOException {
        try (IndexedStore<Long, String> store = open(dir)) {
            populate(store);
            Carver<Long, String> carver = Carver.over(store);
            List<Long> capped = carver.query().whereBetween(ATTR, 0, 7).limit(5).keys();
            assertTrue(capped.size() <= 5);
        }
    }

    @Test
    void recordsFetchesTheValuesTheKeysName(@TempDir Path dir) throws IOException {
        try (IndexedStore<Long, String> store = open(dir)) {
            TreeMap<Long, String> oracle = populate(store);
            Carver<Long, String> carver = Carver.over(store);
            List<Long> keys = new ArrayList<>();
            List<String> vals = new ArrayList<>();
            carver.query().where(ATTR, 2).records((k, v) -> {
                keys.add(k);
                vals.add(v);
            });
            for (int i = 0; i < keys.size(); i++) {
                assertEquals(oracle.get(keys.get(i)), vals.get(i));
            }
        }
    }
}
