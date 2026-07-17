# Carver

The fourth engine of the ecosystem: a **cost-based read planner** over a
[SmokeHouse](../SmokeHouse) `IndexedStore`. SmokeHouse preserves, [CSRBT](../CSRBT) orders,
[SuperBeefSort](../SuperBeefSort) feeds — **Carver decides how to read.**

Until now every `IndexedStore` caller hand-picked its access path (primary walk vs. secondary
vs. interval vs. scan). Carver takes a declarative query, costs the candidate paths, drives the
cheapest, intersects the rest, and records the actual row counts so the next plan is better
informed — SuperBeefSort's profile → select → execute → observe loop, generalized from
"which sort" to "which access path".

```java
Carver<Long, String> carver = Carver.over(indexedStore);   // one per store; it learns

List<Long> keys = carver.query()
        .keysBetween(40L, 260L)          // primary-key range (costed exactly via countRange)
        .whereBetween("attr", 1, 5)      // secondary-index range
        .overlapping("span", 2000, 7000) // interval-index overlap
        .filter((k, v) -> interesting(v))// residual record predicate
        .limit(100)
        .keys();

Plan plan = carver.query().where("attr", 3).explain();
// drive SECONDARY_RANGE(attr) est=18
```

## Design notes

- **Composition, not modification.** Built strictly over `IndexedStore`'s public surface
  (`byAttribute`, `stab`, `overlapping`, `primary().range/countRange/size`). No new lock, no
  store internals.
- **CSRBT order statistics are the histogram.** `countRange` costs a primary range *exactly* —
  no separate statistics structure to build or go stale.
- **The observe loop.** Index-path estimates start from a prior and are refined by EWMA over
  observed actuals (`PlanStats`); reuse one `Carver` so evidence accumulates.
- **Honest consistency.** Each underlying walk is single-writer-consistent as always; a
  multi-predicate query is per-walk consistent, not transactional across walks.

## Build

```bash
# Requires ../SmokeHouse, ../SuperBeefSort, ../CSRBT cloned as siblings (nested composite build)
./gradlew build
```

Java 17+, Gradle 9.5.1 (bundled wrapper). Tests are seeded double-oracle (`TreeMap` reference)
in the house style. MIT license.
