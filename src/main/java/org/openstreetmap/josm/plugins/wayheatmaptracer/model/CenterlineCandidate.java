package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.awt.geom.Point2D;
import java.util.List;

import org.openstreetmap.josm.data.coor.EastNorth;

/**
 * One heatmap ridge candidate that can be previewed, rated, and applied.
 *
 * @param id stable detector/candidate identifier used in debug exports and preview labels
 * @param score calibrated candidate ranking score
 * @param screenPoints candidate points in the slide-time raster/screen coordinate space
 * @param offsetsPx lateral offsets from each sampled source profile in raster pixels
 * @param eastNorthPoints slide-time projected candidate geometry, preferred for modeless preview selection
 * @param finalPreviewPoints candidate-specific geometry after mode reconstruction and fixed-anchor restoration
 * @param junctionSafetyFindings structured final-preview connected-way findings
 * @param junctionSafetyToleranceMeters slide-time tolerance used to evaluate connected-way junction crossings
 * @param evidence aggregate heatmap and longitudinal evidence for the candidate
 * @param safetyWarnings structural warnings that should prevent unsafe apply operations
 */
public record CenterlineCandidate(
    String id,
    double score,
    List<Point2D.Double> screenPoints,
    List<Double> offsetsPx,
    List<EastNorth> eastNorthPoints,
    List<EastNorth> finalPreviewPoints,
    List<JunctionSafetyFinding> junctionSafetyFindings,
    double junctionSafetyToleranceMeters,
    CandidateEvidence evidence,
    List<String> safetyWarnings
) {
    /** Normalizes optional collections and evidence to immutable non-null values. */
    public CenterlineCandidate {
        screenPoints = screenPoints == null ? List.of() : List.copyOf(screenPoints);
        offsetsPx = offsetsPx == null ? List.of() : List.copyOf(offsetsPx);
        eastNorthPoints = eastNorthPoints == null ? List.of() : List.copyOf(eastNorthPoints);
        finalPreviewPoints = finalPreviewPoints == null ? List.of() : List.copyOf(finalPreviewPoints);
        junctionSafetyFindings = junctionSafetyFindings == null ? List.of() : List.copyOf(junctionSafetyFindings);
        evidence = evidence == null ? CandidateEvidence.empty() : evidence;
        safetyWarnings = safetyWarnings == null ? List.of() : List.copyOf(safetyWarnings);
    }

    /**
     * Creates a legacy visible-raster candidate without projected geometry or explicit evidence.
     *
     * @param id detector/candidate identifier
     * @param score candidate ranking score
     * @param screenPoints candidate points in raster/screen coordinates
     * @param offsetsPx lateral offsets from sampled profiles in raster pixels
     */
    public CenterlineCandidate(String id, double score, List<Point2D.Double> screenPoints, List<Double> offsetsPx) {
        this(id, score, screenPoints, offsetsPx, List.of(), List.of(), List.of(), Double.NaN,
            CandidateEvidence.empty(), List.of());
    }

    /**
     * Creates a candidate using the pre-format-5 projected-geometry contract.
     *
     * @param id detector/candidate identifier
     * @param score candidate ranking score
     * @param screenPoints candidate points in raster/screen coordinates
     * @param offsetsPx lateral offsets from sampled profiles in raster pixels
     * @param eastNorthPoints candidate geometry in JOSM projected coordinates
     * @param evidence aggregate heatmap and longitudinal evidence
     * @param safetyWarnings structural warnings that prevent unsafe application
     */
    public CenterlineCandidate(
        String id,
        double score,
        List<Point2D.Double> screenPoints,
        List<Double> offsetsPx,
        List<EastNorth> eastNorthPoints,
        CandidateEvidence evidence,
        List<String> safetyWarnings
    ) {
        this(id, score, screenPoints, offsetsPx, eastNorthPoints, List.of(), List.of(), Double.NaN,
            evidence, safetyWarnings);
    }

    /**
     * Returns a copy with a different identifier.
     *
     * @param newId replacement candidate identifier
     * @return candidate copy using {@code newId}
     */
    public CenterlineCandidate withId(String newId) {
        return new CenterlineCandidate(newId, score, screenPoints, offsetsPx, eastNorthPoints,
            finalPreviewPoints, junctionSafetyFindings, junctionSafetyToleranceMeters, evidence, safetyWarnings);
    }

    /**
     * Returns a copy with a different score.
     *
     * @param newScore replacement ranking score
     * @return candidate copy using {@code newScore}
     */
    public CenterlineCandidate withScore(double newScore) {
        return new CenterlineCandidate(id, newScore, screenPoints, offsetsPx, eastNorthPoints,
            finalPreviewPoints, junctionSafetyFindings, junctionSafetyToleranceMeters, evidence, safetyWarnings);
    }

    /**
     * Returns a copy with projected slide-time candidate points.
     *
     * @param points candidate geometry in JOSM projected coordinates
     * @return candidate copy using {@code points}
     */
    public CenterlineCandidate withEastNorthPoints(List<EastNorth> points) {
        return new CenterlineCandidate(id, score, screenPoints, offsetsPx, points,
            finalPreviewPoints, junctionSafetyFindings, junctionSafetyToleranceMeters, evidence, safetyWarnings);
    }

    /**
     * Returns a copy with candidate-specific final preview geometry.
     *
     * @param points geometry after mode reconstruction and fixed-anchor restoration
     * @return candidate copy using {@code points} for preview and application
     */
    public CenterlineCandidate withFinalPreviewPoints(List<EastNorth> points) {
        return new CenterlineCandidate(id, score, screenPoints, offsetsPx, eastNorthPoints,
            points, junctionSafetyFindings, junctionSafetyToleranceMeters, evidence, safetyWarnings);
    }

    /**
     * Returns a copy with structured final-preview junction findings.
     *
     * @param findings connected-way findings for the final preview geometry
     * @return candidate copy using {@code findings}
     */
    public CenterlineCandidate withJunctionSafetyFindings(List<JunctionSafetyFinding> findings) {
        return new CenterlineCandidate(id, score, screenPoints, offsetsPx, eastNorthPoints,
            finalPreviewPoints, findings, junctionSafetyToleranceMeters, evidence, safetyWarnings);
    }

    /**
     * Returns a copy with structured findings and the tolerance used for their evaluation.
     *
     * @param findings connected-way findings for the final preview geometry
     * @param toleranceMeters slide-time crossing tolerance in metres
     * @return candidate copy using the supplied safety evaluation
     */
    public CenterlineCandidate withJunctionSafetyEvaluation(
        List<JunctionSafetyFinding> findings,
        double toleranceMeters
    ) {
        return new CenterlineCandidate(id, score, screenPoints, offsetsPx, eastNorthPoints,
            finalPreviewPoints, findings, toleranceMeters, evidence, safetyWarnings);
    }

    /**
     * Returns a copy with updated detector evidence.
     *
     * @param newEvidence aggregate heatmap evidence to attach
     * @return candidate copy using {@code newEvidence}
     */
    public CenterlineCandidate withEvidence(CandidateEvidence newEvidence) {
        return new CenterlineCandidate(id, score, screenPoints, offsetsPx, eastNorthPoints,
            finalPreviewPoints, junctionSafetyFindings, junctionSafetyToleranceMeters, newEvidence, safetyWarnings);
    }

    /**
     * Returns a copy with updated structural warnings.
     *
     * @param warnings warnings such as self-intersection or abrupt lateral jumps
     * @return candidate copy using {@code warnings}
     */
    public CenterlineCandidate withSafetyWarnings(List<String> warnings) {
        return new CenterlineCandidate(id, score, screenPoints, offsetsPx, eastNorthPoints,
            finalPreviewPoints, junctionSafetyFindings, junctionSafetyToleranceMeters, evidence, warnings);
    }

    /**
     * Builds the user-facing candidate label shown in the preview selector.
     *
     * @return readable detector name, confidence label, and safety warning summary
     */
    public String displayName() {
        String normalized = id.replace("-mapped-parallel", " mapped parallel");
        String[] parts = normalized.split("/");
        StringBuilder label = new StringBuilder();
        int index = 0;
        while (index < parts.length && parts[index].startsWith("refined-")) {
            index++;
        }
        while (index < parts.length && parts[index].startsWith("consensus-")) {
            String modes = consensusModesLabel();
            if (modes.isBlank()) {
                String count = parts[index].substring("consensus-".length());
                label.append("Consensus ").append(count).append(" detectors");
            } else {
                label.append("Consensus: ").append(modes);
            }
            label.append(" - ");
            index++;
        }
        if (index < parts.length && "consensus".equals(parts[index])) {
            index++;
        }
        if (index < parts.length && parts[index].startsWith("ridge-") && label.toString().startsWith("Consensus")) {
            label.append(parts[index].replace('-', ' '));
            index++;
        } else if (index < parts.length) {
            label.append(capitalize(parts[index])).append(" detector");
            index++;
        }
        if (index < parts.length) {
            if (!label.isEmpty() && !label.toString().endsWith(" - ")) {
                label.append(" - ");
            }
            label.append(parts[index].replace('-', ' '));
            index++;
        }
        while (index < parts.length) {
            label.append(" - ").append(parts[index].replace('-', ' '));
            index++;
        }
        label.append(" - ").append(confidenceLabel());
        if (!safetyWarnings.isEmpty()) {
            label.append(" - ").append(String.join(", ", safetyWarnings));
        }
        return label.toString();
    }

    @Override
    public String toString() {
        return displayName();
    }

    private String confidenceLabel() {
        if (!evidence.hasSignal()) {
            return "no signal";
        }
        double support = evidence.supportRatio();
        double quality =
            0.34 * clamp01(support)
            + 0.28 * clamp01(evidence.signalToNoise() / 0.24)
            + 0.22 * clamp01(evidence.meanIntensity() / 0.55)
            + 0.10 * clamp01(evidence.meanGradientStrength() / 0.25)
            + 0.06 * clamp01(evidence.longitudinalStability())
            + 0.10 * (1.0 - clamp01(evidence.ambiguity() / 1.60));
        quality -= 0.16 * clamp01(evidence.maxConsecutiveEmptyProfiles() / 20.0);
        if (quality >= 0.62 && support >= 0.45 && evidence.maxConsecutiveEmptyProfiles() <= 14) {
            return "strong";
        }
        if (quality >= 0.42 && support >= 0.28 && evidence.maxConsecutiveEmptyProfiles() <= 24) {
            return "usable";
        }
        if (quality >= 0.22 || support >= 0.12 || evidence.signalToNoise() >= 0.03) {
            return "weak";
        }
        return "very weak";
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static String capitalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + value.substring(1);
    }

    private String consensusModesLabel() {
        if (evidence.consensusModes().isEmpty()) {
            return "";
        }
        return String.join(" + ", evidence.consensusModes());
    }
}
