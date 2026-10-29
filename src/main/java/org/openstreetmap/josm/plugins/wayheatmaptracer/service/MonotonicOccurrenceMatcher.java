package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** The native Legacy reuse-first ordered source-fraction to preview-slot assignment. */
public final class MonotonicOccurrenceMatcher {
    private MonotonicOccurrenceMatcher() { }

    /**
     * Returns preview-slot index to source-occurrence index, maximizing reused identities,
     * then minimizing total fraction error with the unchanged Legacy decision tie order.
     * Equal fractions are permitted; protected-anchor interval labels prevent crossing.
     * Callers retaining an attempt ledger reserve {@link #workingBytes} before allocation.
     */
    public static Map<Integer, Integer> match(List<Double> source, List<Double> preview,
            List<Integer> sourceIntervals, List<Integer> previewIntervals, CancellationProbe cancellation) {
        Objects.requireNonNull(cancellation).checkpoint();
        validate(source, sourceIntervals); validate(preview, previewIntervals);
        Cell[][] table = new Cell[source.size() + 1][preview.size() + 1];
        table[source.size()][preview.size()] = new Cell(0, 0, Decision.DONE);
        for (int i = source.size(); i >= 0; i--) {
            cancellation.checkpoint();
            for (int j = preview.size(); j >= 0; j--) {
                cancellation.checkpoint();
                if (i == source.size() && j == preview.size()) continue;
                Cell best = null;
                if (i < source.size()) best = choose(best, advance(table[i + 1][j], Decision.SKIP_SOURCE, 0, 0));
                if (j < preview.size()) best = choose(best, advance(table[i][j + 1], Decision.SKIP_SLOT, 0, 0));
                if (i < source.size() && j < preview.size() && sourceIntervals.get(i).equals(previewIntervals.get(j)))
                    best = choose(best, advance(table[i + 1][j + 1], Decision.MATCH, 1,
                            Math.abs(source.get(i) - preview.get(j))));
                table[i][j] = best;
            }
        }
        var result = new LinkedHashMap<Integer, Integer>();
        int i = 0, j = 0;
        while (i < source.size() || j < preview.size()) {
            cancellation.checkpoint();
            var decision = table[i][j].decision();
            if (decision == Decision.MATCH) { result.put(j++, i++); }
            else if (decision == Decision.SKIP_SOURCE) i++;
            else if (decision == Decision.SKIP_SLOT) j++;
            else break;
        }
        return Collections.unmodifiableMap(result);
    }

    /** Conservative transient table/row/cell allocation estimate for attempt-owned callers. */
    public static long workingBytes(int sourceCount, int previewCount) {
        if (sourceCount < 0 || previewCount < 0) throw new IllegalArgumentException("Negative matching dimensions");
        return Math.addExact(256, Math.multiplyExact(128,
                Math.multiplyExact((long) sourceCount + 1, (long) previewCount + 1)));
    }

    private static void validate(List<Double> fractions, List<Integer> intervals) {
        if (fractions == null || intervals == null || fractions.size() != intervals.size())
            throw new IllegalArgumentException("Matching fractions and interval labels must agree");
        double previous = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < fractions.size(); i++) {
            var fraction = fractions.get(i);
            if (fraction == null || !Double.isFinite(fraction) || fraction < 0 || fraction > 1
                    || fraction < previous || intervals.get(i) == null || intervals.get(i) < 0)
                throw new IllegalArgumentException("Matching fractions must be finite, ordered and normalized");
            previous = fraction;
        }
    }

    private static Cell advance(Cell following, Decision decision, int matches, double error) {
        return following == null ? null : new Cell(following.matches() + matches, following.error() + error, decision);
    }

    private static Cell choose(Cell first, Cell second) {
        if (second == null) return first;
        return first == null || second.matches() > first.matches()
                || second.matches() == first.matches() && second.error() < first.error() - 1e-12
                || second.matches() == first.matches() && Math.abs(second.error() - first.error()) <= 1e-12
                        && second.decision().ordinal() < first.decision().ordinal() ? second : first;
    }

    private record Cell(int matches, double error, Decision decision) { }
    private enum Decision { MATCH, SKIP_SLOT, SKIP_SOURCE, DONE }
}
