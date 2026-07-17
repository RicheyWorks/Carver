# Carver — working notes for agents

## What it is
The ecosystem's fourth engine: a cost-based read planner over a SmokeHouse `IndexedStore`.
One class per idea: `Carver` (facade + executor), `Query` (declarative cut), `Planner`
(path choice), `Plan` (the chosen cut, printable), `PlanStats` (EWMA observe loop),
`AccessPath` (the path taxonomy). Strictly public-surface composition over `IndexedStore` —
never reach into store internals, never mutate `primary()` directly.

## Build & test
- Nested composite: requires `../SmokeHouse`, `../SuperBeefSort`, `../CSRBT` as siblings.
  `./gradlew build` runs everything (Gradle 9 wrapper; JVM 17+).
- Tests are seeded, deterministic, double-oracle (`TreeMap` reference in `CarverTest`) —
  the required style for any new planner or executor behavior. Index tier is pinned STATIC
  in test options so oracle runs stay deterministic.

## Git is host-side
Same as the siblings: agent sandboxes cannot write `.git`. Run all git commands from a host
terminal (PowerShell). Stale `.git/index.lock` fix: `Remove-Item .git\index.lock -Force`.

## Invariants (do not break)
- **Correctness never depends on the plan.** Any driving path + intersections must return the
  same key set; the plan only changes cost. Every behavior change needs an oracle test proving
  plan-independence (run the same query, assert against brute force).
- **Estimates are advisory, exactness is labeled.** `PRIMARY_RANGE`/`FULL_SCAN` estimates are
  exact (CSRBT `countRange`/`size`); index-path estimates are priors refined by `PlanStats`.
  Never present a prior as exact.
- **No background threads, no own lock.** Caller-cadenced like every control loop in the ring;
  per-walk consistency is documented, not hidden.
- **The observe loop is per-Carver.** One `Carver` per store, reused, like SuperBeefSort's
  bandit selector.

## Roadmap seams (measure before cutting — the ring's rule)
- The measure rig is live: `./gradlew jmh` runs `PlanQualityBenchmark` (planned vs
  hand-written oracle-best vs naive-worst per query shape, plus plan-only latency).
  `build`/`check` compiles the jmh source set; new planner behavior needs a benchmark row.
- A `countByAttribute` seam on `IndexedStore` would make secondary estimates exact — propose
  it upstream only if the JMH rows show the prior misleads the planner.
- Bandit-style plan choice (explore/exploit over near-tied candidates) can reuse
  SuperBeefSort's selector machinery when estimates are close.
