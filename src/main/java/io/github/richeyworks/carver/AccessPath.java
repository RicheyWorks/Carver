package io.github.richeyworks.carver;

/** How a query's driving key list is produced. */
public enum AccessPath {
    /** Walk every record via the primary index (the fallback; costed at store size). */
    FULL_SCAN,
    /** One closed-range walk over the primary index (costed exactly via {@code countRange}). */
    PRIMARY_RANGE,
    /** One composite (attribute, key) range walk over a declared secondary. */
    SECONDARY_RANGE,
    /** A candidate-pruned overlap walk over a declared interval index. */
    INTERVAL_OVERLAP,
    /** A candidate-pruned stabbing walk over a declared interval index. */
    INTERVAL_STAB
}
