package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.util.List;

/** Immutable whole-network validation disposition and typed finding codes attached to an edit plan. */
public record ValidationReport(Disposition disposition, List<String> findingCodes) {
    /** Mutually exclusive final validation states. */
    public enum Disposition { APPLICABLE, REVIEW_REQUIRED, HARD_BLOCKED }

    /** Copies finding codes and enforces truthful result-state combinations. */
    public ValidationReport {
        if (disposition == null || findingCodes == null
            || findingCodes.stream().anyMatch(code -> code == null || code.isBlank())
            || disposition == Disposition.HARD_BLOCKED && findingCodes.isEmpty()) {
            throw new IllegalArgumentException("Validation report disposition and findings are inconsistent");
        }
        findingCodes = List.copyOf(findingCodes);
    }

    /** Compatibility constructor that rejects the impossible not-applicable/review-required state. */
    public ValidationReport(boolean applicable, boolean reviewRequired, List<String> findingCodes) {
        this(fromBooleans(applicable, reviewRequired), findingCodes);
    }

    /** Returns whether the result may be applied directly or after explicit review. */
    public boolean applicable() {
        return disposition != Disposition.HARD_BLOCKED;
    }

    /** Returns whether exact-plan review confirmation is required before Apply. */
    public boolean reviewRequired() {
        return disposition == Disposition.REVIEW_REQUIRED;
    }

    private static Disposition fromBooleans(boolean applicable, boolean reviewRequired) {
        if (!applicable && reviewRequired) {
            throw new IllegalArgumentException("A blocked validation result cannot require apply review");
        }
        return applicable ? reviewRequired ? Disposition.REVIEW_REQUIRED : Disposition.APPLICABLE
            : Disposition.HARD_BLOCKED;
    }
}
