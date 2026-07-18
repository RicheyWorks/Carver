# Carver

[![CI](https://github.com/RicheyWorks/Carver/actions/workflows/ci.yml/badge.svg)](https://github.com/RicheyWorks/Carver/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Java 17](https://img.shields.io/badge/Java-17-orange.svg)](https://adoptium.net/)

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
- **Typed intervals too.** `overlapping`/`stabbing` have typed overloads riding SmokeHouse's
  generic interval tier — epoch-millis `Long` spans plan and execute exactly like int spans.

## Measured, not asserted

`./gradlew jmh` runs `PlanQualityBenchmark`: the planner's end-to-end cost (`planned`) against
a hand-written oracle-best drive and a naive-worst drive per query shape, plus `planOnly` for
the planning latency itself. The claims are falsifiable: `planned` ≈ `oracleBest` on both
shapes, both ≪ `naiveWorst`, `planOnly` in the noise. `build` compiles the benchmarks, so the
rig can't rot.

## The ecosystem

| Engine | Role |
|---|---|
| [CSRBT](https://github.com/RicheyWorks/CSRBT) | the adaptive ordered index |
| [SuperBeefSort](https://github.com/RicheyWorks/SuperBeefSort) | the intake tract — profiles, sorts, feeds |
| [SmokeHouse](https://github.com/RicheyWorks/SmokeHouse) | the log-structured store — tail, watchers, replicas |
| **Carver** (this repo) | the read planner — decides how to read |
| [Renderer](https://github.com/RicheyWorks/Renderer) | the materialized-view engine over the tail |
| [Brine](https://github.com/RicheyWorks/Brine) | the adaptive cache with an evolved eviction policy |

Engines 7–11: [PitBoss](https://github.com/RicheyWorks/PitBoss) (fleet conductor) · [DryAge](https://github.com/RicheyWorks/DryAge) (time travel) · [Twine](https://github.com/RicheyWorks/Twine) (atomic batches) · [SmokeSignal](https://github.com/RicheyWorks/SmokeSignal) (the wire) · [Jerky](https://github.com/RicheyWorks/Jerky) (cold archives).
Engine 12: [WholeHog](https://github.com/RicheyWorks/WholeHog) — the integration organism: all of them, composed and asserted together.

## Build

```bash
# Requires ../SmokeHouse, ../SuperBeefSort, ../CSRBT cloned as siblings (nested composite build)
./gradlew build
```

Java 17+, Gradle 9.5.1 (bundled wrapper). Tests are seeded double-oracle (`TreeMap` reference)
in the house style. MIT license.
