package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.awt.geom.Point2D;
import java.util.List;
import java.util.Map;

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
 * @param proposedNodePositions candidate-time existing-node targets keyed by stable OSM node id
 * @param junctionSafetyFindings structured final-preview connected-way findings
 * @param junctionSafetyToleranceMeters slide-time tolerance used to evaluate connected-way junction crossings
 * @param evidence aggregate heatmap and longitudinal evidence for the candidate
 * @param cleanupEvidence candidate-specific evidence for optional geometry cleanup
 * @param geometryCleanup compact cleanup attempt and raw-parent metadata
 * @param safetyWarnings structural warnings that should prevent unsafe apply operations
 */
public record CenterlineCandidate(
    String id,
    double score,
    List<Point2D.Double> screenPoints,
    List<Double> offsetsPx,
    List<EastNorth> eastNorthPoints,
    List<EastNorth> finalPreviewPoints,
    Map<Long, EastNorth> proposedNodePositions,
    List<JunctionSafetyFinding> junctionSafetyFindings,
    double junctionSafetyToleranceMeters,
    CandidateEvidence evidence,
    CandidateCleanupEvidence cleanupEvidence,
    CandidateGeometryCleanup geometryCleanup,
    List<String> safetyWarnings
) {
    /** Normalizes optional collections and evidence to immutable non-null values. */
    public CenterlineCandidate {
        screenPoints = screenPoints == null ? List.of() : List.copyOf(screenPoints);
        offsetsPx = offsetsPx == null ? List.of() : List.copyOf(offsetsPx);
        eastNorthPoints = eastNorthPoints == null ? List.of() : List.copyOf(eastNorthPoints);
        finalPreviewPoints = finalPreviewPoints == null ? List.of() : List.copyOf(finalPreviewPoints);
        proposedNodePositions = proposedNodePositions == null ? Map.of() : Map.copyOf(proposedNodePositions);
        for (Map.Entry<Long, EastNorth> entry : proposedNodePositions.entrySet()) {
            EastNorth point = entry.getValue();
            if (point == null || !Double.isFinite(point.east()) || !Double.isFinite(point.north())) {
                throw new IllegalArgumentException(
                    "Proposed node positions must contain finite projected coordinates");
            }
        }
        junctionSafetyFindings = junctionSafetyFindings == null ? List.of() : List.copyOf(junctionSafetyFindings);
        evidence = evidence == null ? CandidateEvidence.empty() : evidence;
        cleanupEvidence = cleanupEvidence == null ? CandidateCleanupEvidence.empty() : cleanupEvidence;
        geometryCleanup = geometryCleanup == null ? CandidateGeometryCleanup.notRequested() : geometryCleanup;
        safetyWarnings = safetyWarnings == null ? List.of() : List.copyOf(safetyWarnings);
    }

    /**
     * Creates a candidate using the pre-geometry-cleanup-report canonical contract.
     *
     * @param id stable candidate identifier
     * @param score detector ranking score
     * @param screenPoints slide-time raster geometry
     * @param offsetsPx lateral sampled-raster offsets
     * @param eastNorthPoints projected raw candidate geometry
     * @param finalPreviewPoints reconstructed final preview geometry
     * @param proposedNodePositions candidate-owned existing-node targets
     * @param junctionSafetyFindings connected-way safety findings
     * @param junctionSafetyToleranceMeters physical crossing tolerance in ground metres
     * @param evidence aggregate detector evidence
     * @param cleanupEvidence candidate-specific retained cleanup evidence
     * @param safetyWarnings blocking structural warnings
     */
    public CenterlineCandidate(
        String id,
        double score,
        List<Point2D.Double> screenPoints,
        List<Double> offsetsPx,
        List<EastNorth> eastNorthPoints,
        List<EastNorth> finalPreviewPoints,
        Map<Long, EastNorth> proposedNodePositions,
        List<JunctionSafetyFinding> junctionSafetyFindings,
        double junctionSafetyToleranceMeters,
        CandidateEvidence evidence,
        CandidateCleanupEvidence cleanupEvidence,
        List<String> safetyWarnings
    ) {
        this(id, score, screenPoints, offsetsPx, eastNorthPoints, finalPreviewPoints,
            proposedNodePositions, junctionSafetyFindings, junctionSafetyToleranceMeters,
            evidence, cleanupEvidence, CandidateGeometryCleanup.notRequested(), safetyWarnings);
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
        this(id, score, screenPoints, offsetsPx, List.of(), List.of(), Map.of(), List.of(), Double.NaN,
            CandidateEvidence.empty(), CandidateCleanupEvidence.empty(), List.of());
    }

    /**
     * Creates a candidate using the pre-proposed-node canonical contract.
     *
     * @param id candidate identifier
     * @param score ranking score
     * @param screenPoints sampled-raster geometry
     * @param offsetsPx lateral profile offsets
     * @param eastNorthPoints projected raw geometry
     * @param finalPreviewPoints reconstructed preview geometry
     * @param junctionSafetyFindings connected-way findings
     * @param junctionSafetyToleranceMeters physical crossing tolerance
     * @param evidence candidate evidence
     * @param safetyWarnings blocking warnings
     */
    public CenterlineCandidate(
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
        this(id, score, screenPoints, offsetsPx, eastNorthPoints, finalPreviewPoints, Map.of(),
            junctionSafetyFindings, junctionSafetyToleranceMeters, evidence, CandidateCleanupEvidence.empty(),
            safetyWarnings);
    }

    /**
     * Creates a candidate using the pre-cleanup-evidence canonical contract.
     *
     * @param id candidate identifier
     * @param score ranking score
     * @param screenPoints sampled-raster geometry
     * @param offsetsPx lateral profile offsets
     * @param eastNorthPoints projected raw geometry
     * @param finalPreviewPoints reconstructed preview geometry
     * @param proposedNodePositions candidate-owned existing-node targets
     * @param junctionSafetyFindings connected-way findings
     * @param junctionSafetyToleranceMeters physical crossing tolerance
     * @param evidence aggregate candidate evidence
     * @param safetyWarnings blocking warnings
     */
    public CenterlineCandidate(
        String id,
        double score,
        List<Point2D.Double> screenPoints,
        List<Double> offsetsPx,
        List<EastNorth> eastNorthPoints,
        List<EastNorth> finalPreviewPoints,
        Map<Long, EastNorth> proposedNodePositions,
        List<JunctionSafetyFinding> junctionSafetyFindings,
        double junctionSafetyToleranceMeters,
        CandidateEvidence evidence,
        List<String> safetyWarnings
    ) {
        this(id, score, screenPoints, offsetsPx, eastNorthPoints, finalPreviewPoints, proposedNodePositions,
            junctionSafetyFindings, junctionSafetyToleranceMeters, evidence, CandidateCleanupEvidence.empty(),
            safetyWarnings);
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
        this(id, score, screenPoints, offsetsPx, eastNorthPoints, List.of(), Map.of(), List.of(), Double.NaN,
            evidence, CandidateCleanupEvidence.empty(), safetyWarnings);
    }

    /**
     * Returns a copy with a different identifier.
     *
     * @param newId replacement candidate identifier
     * @return candidate copy using {@code newId}
     */
    public CenterlineCandidate withId(String newId) {
        return new CenterlineCandidate(newId, score, screenPoints, offsetsPx, eastNorthPoints,
            finalPreviewPoints, proposedNodePositions, junctionSafetyFindings, junctionSafetyToleranceMeters,
            evidence, cleanupEvidence, geometryCleanup, safetyWarnings);
    }

    /**
     * Returns a copy with a different score.
     *
     * @param newScore replacement ranking score
     * @return candidate copy using {@code newScore}
     */
    public CenterlineCandidate withScore(double newScore) {
        return new CenterlineCandidate(id, newScore, screenPoints, offsetsPx, eastNorthPoints,
            finalPreviewPoints, proposedNodePositions, junctionSafetyFindings, junctionSafetyToleranceMeters,
            evidence, cleanupEvidence, geometryCleanup, safetyWarnings);
    }

    /**
     * Returns a copy with projected slide-time candidate points.
     *
     * @param points candidate geometry in JOSM projected coordinates
     * @return candidate copy using {@code points}
     */
    public CenterlineCandidate withEastNorthPoints(List<EastNorth> points) {
        return new CenterlineCandidate(id, score, screenPoints, offsetsPx, points,
            finalPreviewPoints, proposedNodePositions, junctionSafetyFindings, junctionSafetyToleranceMeters,
            evidence, cleanupEvidence, geometryCleanup, safetyWarnings);
    }

    /**
     * Returns a copy with projected geometry and its recomputed lateral offsets.
     *
     * @param points projected candidate geometry
     * @param offsets lateral offsets aligned one-to-one with {@code points}
     * @return candidate copy using the supplied geometry and offsets
     * @throws IllegalArgumentException when geometry and offsets have different sizes
     */
    public CenterlineCandidate withProjectedGeometryAndOffsets(
        List<EastNorth> points,
        List<Double> offsets
    ) {
        if (points == null || offsets == null || points.size() != offsets.size()) {
            throw new IllegalArgumentException("Projected candidate geometry and offsets must align");
        }
        return new CenterlineCandidate(id, score, List.of(), offsets, points,
            finalPreviewPoints, proposedNodePositions, junctionSafetyFindings, junctionSafetyToleranceMeters,
            evidence, cleanupEvidence, geometryCleanup, safetyWarnings);
    }

    /**
     * Returns a copy with candidate-specific final preview geometry.
     *
     * @param points geometry after mode reconstruction and fixed-anchor restoration
     * @return candidate copy using {@code points} for preview and application
     */
    public CenterlineCandidate withFinalPreviewPoints(List<EastNorth> points) {
        return new CenterlineCandidate(id, score, screenPoints, offsetsPx, eastNorthPoints,
            points, proposedNodePositions, junctionSafetyFindings, junctionSafetyToleranceMeters,
            evidence, cleanupEvidence, geometryCleanup, safetyWarnings);
    }

    /**
     * Returns a copy with final preview geometry and its existing-node targets.
     *
     * @param points geometry after mode reconstruction
     * @param nodePositions existing-node targets keyed by stable OSM node id
     * @return candidate copy using one consistent proposed topology state
     */
    public CenterlineCandidate withFinalPreviewGeometry(
        List<EastNorth> points,
        Map<Long, EastNorth> nodePositions
    ) {
        return new CenterlineCandidate(id, score, screenPoints, offsetsPx, eastNorthPoints,
            points, nodePositions, junctionSafetyFindings, junctionSafetyToleranceMeters, evidence,
            cleanupEvidence, geometryCleanup, safetyWarnings);
    }

    /**
     * Returns a copy with structured final-preview junction findings.
     *
     * @param findings connected-way findings for the final preview geometry
     * @return candidate copy using {@code findings}
     */
    public CenterlineCandidate withJunctionSafetyFindings(List<JunctionSafetyFinding> findings) {
        return new CenterlineCandidate(id, score, screenPoints, offsetsPx, eastNorthPoints,
            finalPreviewPoints, proposedNodePositions, findings, junctionSafetyToleranceMeters,
            evidence, cleanupEvidence, geometryCleanup, safetyWarnings);
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
            finalPreviewPoints, proposedNodePositions, findings, toleranceMeters, evidence, cleanupEvidence,
            geometryCleanup, safetyWarnings);
    }

    /**
     * Returns a copy with updated detector evidence.
     *
     * @param newEvidence aggregate heatmap evidence to attach
     * @return candidate copy using {@code newEvidence}
     */
    public CenterlineCandidate withEvidence(CandidateEvidence newEvidence) {
        return new CenterlineCandidate(id, score, screenPoints, offsetsPx, eastNorthPoints,
            finalPreviewPoints, proposedNodePositions, junctionSafetyFindings, junctionSafetyToleranceMeters,
            newEvidence, cleanupEvidence, geometryCleanup, safetyWarnings);
    }

    /**
     * Returns a copy with candidate-specific geometry-cleanup evidence.
     *
     * @param newCleanupEvidence immutable cleanup evidence to attach
     * @return candidate copy sharing the supplied detector sampling frame
     */
    public CenterlineCandidate withCleanupEvidence(CandidateCleanupEvidence newCleanupEvidence) {
        return new CenterlineCandidate(id, score, screenPoints, offsetsPx, eastNorthPoints,
            finalPreviewPoints, proposedNodePositions, junctionSafetyFindings, junctionSafetyToleranceMeters,
            evidence, newCleanupEvidence, geometryCleanup, safetyWarnings);
    }

    /**
     * Returns a copy with compact cleanup attempt and raw-parent metadata.
     *
     * @param report immutable cleanup report
     * @return candidate copy using {@code report}
     */
    public CenterlineCandidate withGeometryCleanup(CandidateGeometryCleanup report) {
        return new CenterlineCandidate(id, score, screenPoints, offsetsPx, eastNorthPoints,
            finalPreviewPoints, proposedNodePositions, junctionSafetyFindings, junctionSafetyToleranceMeters,
            evidence, cleanupEvidence, report, safetyWarnings);
    }

    /**
     * Returns a copy with updated structural warnings.
     *
     * @param warnings warnings such as self-intersection or abrupt lateral jumps
     * @return candidate copy using {@code warnings}
     */
    public CenterlineCandidate withSafetyWarnings(List<String> warnings) {
        return new CenterlineCandidate(id, score, screenPoints, offsetsPx, eastNorthPoints,
            finalPreviewPoints, proposedNodePositions, junctionSafetyFindings, junctionSafetyToleranceMeters,
            evidence, cleanupEvidence, geometryCleanup, warnings);
    }

    /**
     * Builds the user-facing candidate label shown in the preview selector.
     *
     * @return readable detector name, confidence label, and safety warning summary
     */
    public String displayName() {
        String normalized = id.replace("#cleaned", "")
            .replace("-mapped-parallel", " mapped parallel");
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
            label.append(readableCandidatePart(parts[index]));
            index++;
        } else if (index < parts.length) {
            label.append(capitalize(parts[index])).append(" detector");
            index++;
        }
        if (index < parts.length) {
            if (!label.isEmpty() && !label.toString().endsWith(" - ")) {
                label.append(" - ");
            }
            label.append(readableCandidatePart(parts[index]));
            index++;
        }
        while (index < parts.length) {
            label.append(" - ").append(readableCandidatePart(parts[index]));
            index++;
        }
        label.append(" - ").append(confidenceLabel());
        if (geometryCleanup.cleanedCandidate()) {
            label.append(" - cleaned (").append(geometryCleanup.beforePointCount()).append(" -> ")
                .append(geometryCleanup.afterPointCount()).append(" points)");
        }
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

    private String readableCandidatePart(String part) {
        return part.startsWith("bundle-") ? "sparse corridor " + part.substring("bundle-".length())
            : part.replace('-', ' ');
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
