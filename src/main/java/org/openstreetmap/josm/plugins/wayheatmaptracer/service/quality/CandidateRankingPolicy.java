package org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;

/** Deterministic physical cross-engine ranking that never compares engine-owned objectives. */
public final class CandidateRankingPolicy {
    /** Common final-preview quality values with explicit physical support. */
    public record PhysicalQuality(boolean completeRoute, boolean hardBlocked,
            int severeLocalDefects, int branchConflicts, int anchorConflicts,
            double directlySupportedLengthMeters, double totalLengthMeters,
            double worstUnsupportedSpanMeters, double imageCenterFit,
            double orientationFit, double bendPreservingRoughness,
            int mappingPreference, double guideReliability) {
        /** Validates finite nonnegative metrics and bounded counts. */
        public PhysicalQuality {
            if (severeLocalDefects < 0 || branchConflicts < 0 || anchorConflicts < 0
                    || !nonnegative(directlySupportedLengthMeters) || !nonnegative(totalLengthMeters)
                    || !nonnegative(worstUnsupportedSpanMeters) || !nonnegative(imageCenterFit)
                    || !nonnegative(orientationFit) || !nonnegative(bendPreservingRoughness)
                    || totalLengthMeters <= 0.0
                    || directlySupportedLengthMeters > totalLengthMeters + 1.0e-9
                    || worstUnsupportedSpanMeters > totalLengthMeters + 1.0e-9
                    || mappingPreference < 0 || !boundedUnit(guideReliability)) {
                throw new IllegalArgumentException("Candidate physical quality must be finite and nonnegative");
            }
        }

        private static boolean nonnegative(double value) {
            return Double.isFinite(value) && value >= 0.0;
        }

        private static boolean boundedUnit(double value) {
            return nonnegative(value) && value <= 1.0;
        }
    }

    /** Candidate metadata; engine objective and point count remain diagnostic only. */
    public record Candidate(String id, TrackerMode engine, String sourceTier,
            PhysicalQuality quality, double engineOwnedObjective, int pointCount,
            boolean cleanedSibling, String topologyFamily, boolean cleanupImprovement) {
        /** Validates stable identity while retaining opaque engine score. */
        public Candidate {
            if (id == null || id.isBlank() || engine == null || sourceTier == null
                    || sourceTier.isBlank() || quality == null || !Double.isFinite(engineOwnedObjective)
                    || pointCount < 2 || topologyFamily == null || topologyFamily.isBlank()) {
                throw new IllegalArgumentException("Ranked candidate metadata is incomplete");
            }
        }
    }

    /** Returns a new deterministically ranked list without mutating caller order. */
    public List<Candidate> rank(List<Candidate> candidates) {
        Objects.requireNonNull(candidates, "candidates");
        List<Candidate> ranked = new ArrayList<>(candidates);
        ranked.sort(comparator());
        return List.copyOf(ranked);
    }

    private static Comparator<Candidate> comparator() {
        return (first, second) -> {
            int comparison = Boolean.compare(first.quality().hardBlocked(), second.quality().hardBlocked());
            if (comparison != 0) {
                return comparison;
            }
            comparison = Boolean.compare(!first.quality().completeRoute(), !second.quality().completeRoute());
            if (comparison != 0) {
                return comparison;
            }
            comparison = Integer.compare(defectCount(first.quality()), defectCount(second.quality()));
            if (comparison != 0) {
                return comparison;
            }
            comparison = Double.compare(second.quality().directlySupportedLengthMeters(),
                    first.quality().directlySupportedLengthMeters());
            if (comparison != 0) {
                return comparison;
            }
            comparison = Double.compare(first.quality().worstUnsupportedSpanMeters(),
                    second.quality().worstUnsupportedSpanMeters());
            if (comparison != 0) {
                return comparison;
            }
            comparison = Double.compare(first.quality().imageCenterFit(), second.quality().imageCenterFit());
            if (comparison != 0) {
                return comparison;
            }
            comparison = Double.compare(first.quality().orientationFit(), second.quality().orientationFit());
            if (comparison != 0) {
                return comparison;
            }
            comparison = Double.compare(first.quality().bendPreservingRoughness(),
                    second.quality().bendPreservingRoughness());
            if (comparison != 0) {
                return comparison;
            }
            comparison = Integer.compare(sourceTier(first.sourceTier()), sourceTier(second.sourceTier()));
            if (comparison != 0) {
                return comparison;
            }
            comparison = Integer.compare(first.quality().mappingPreference(), second.quality().mappingPreference());
            if (comparison != 0) {
                return comparison;
            }
            comparison = Double.compare(second.quality().guideReliability(), first.quality().guideReliability());
            if (comparison != 0) {
                return comparison;
            }
            comparison = first.topologyFamily().compareTo(second.topologyFamily());
            if (comparison != 0) {
                return comparison;
            }
            comparison = Boolean.compare(!first.cleanupImprovement(), !second.cleanupImprovement());
            if (comparison != 0) {
                return comparison;
            }
            return first.id().compareTo(second.id());
        };
    }

    private static int defectCount(PhysicalQuality quality) {
        return quality.severeLocalDefects() + quality.branchConflicts() + quality.anchorConflicts();
    }

    private static int sourceTier(String sourceTier) {
        return switch (sourceTier.toLowerCase(java.util.Locale.ROOT)) {
            case "native" -> 0;
            case "aggregate" -> 1;
            case "cross-mapping" -> 2;
            default -> 3;
        };
    }
}
