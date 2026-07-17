package io.github.richeyworks.carver;

import java.util.List;

/**
 * The planner's chosen cut, honesty on the record: the driving access path, the cardinality
 * estimate it was chosen on (exact for {@link AccessPath#PRIMARY_RANGE} and
 * {@link AccessPath#FULL_SCAN} — CSRBT order statistics are the planner's histogram — a prior
 * refined by {@link PlanStats} observation for index paths), and the residual steps applied
 * after the drive.
 *
 * @param path          the driving access path
 * @param index         the driving index name, or {@code null} for primary-side paths
 * @param estimatedRows the row estimate the path was chosen on
 * @param intersections non-driving predicates, applied as key-set intersections after the drive
 * @param residualFilter whether a record-level filter runs last (forces a value fetch per key)
 */
public record Plan(AccessPath path, String index, long estimatedRows,
                   List<String> intersections, boolean residualFilter) {

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("drive ").append(path);
        if (index != null) {
            sb.append('(').append(index).append(')');
        }
        sb.append(" est=").append(estimatedRows);
        for (String step : intersections) {
            sb.append(" ∩ ").append(step);
        }
        if (residualFilter) {
            sb.append(" -> filter");
        }
        return sb.toString();
    }
}
