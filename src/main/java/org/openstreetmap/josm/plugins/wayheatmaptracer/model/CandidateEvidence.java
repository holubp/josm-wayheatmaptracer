package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.util.List;

/**
 * Aggregate heatmap evidence collected for one centerline candidate.
 *
 * @param detectorMode detector or palette mapping that produced the candidate
 * @param totalProfiles number of sampled cross-sections
 * @param supportedProfiles profiles with usable heatmap signal
 * @param emptyProfiles profiles without usable heatmap signal
 * @param maxConsecutiveEmptyProfiles longest unsupported run along the way
 * @param totalIntensity sum of selected peak intensities
 * @param meanIntensity mean selected peak intensity
 * @param meanGradientStrength mean cross-section gradient evidence at selected peaks
 * @param longitudinalStability smoothness/stability score along the way
 * @param signalToNoise estimated signal-to-noise ratio
 * @param ambiguity estimated multimodal ambiguity
 * @param signalExistenceConfidence confidence that a persistent heat corridor exists
 * @param localizationConfidence confidence that the selected corridor center is localized
 * @param optimizerCost normalized corridor-aware objective cost
 * @param inCorridorFraction fraction of optimized points inside selected corridor shoulders
 * @param scalePersistence mean compatible L0/L1/L2 corridor persistence
 * @param scaleConflictFraction fraction of supported profiles with incompatible coarse evidence
 * @param consensusModes detector modes fused before candidate extraction
 */
public record CandidateEvidence(
    String detectorMode,
    int totalProfiles,
    int supportedProfiles,
    int emptyProfiles,
    int maxConsecutiveEmptyProfiles,
    double totalIntensity,
    double meanIntensity,
    double meanGradientStrength,
    double longitudinalStability,
    double signalToNoise,
    double ambiguity,
    double signalExistenceConfidence,
    double localizationConfidence,
    double optimizerCost,
    double inCorridorFraction,
    double scalePersistence,
    double scaleConflictFraction,
    List<String> consensusModes
) {
    /**
     * Creates pre-scale-space corridor evidence.
     *
     * @param detectorMode detector mapping name
     * @param totalProfiles sampled profile count
     * @param supportedProfiles supported profile count
     * @param emptyProfiles unsupported profile count
     * @param maxConsecutiveEmptyProfiles longest unsupported run
     * @param totalIntensity selected intensity sum
     * @param meanIntensity selected mean intensity
     * @param meanGradientStrength mean boundary-gradient strength
     * @param longitudinalStability longitudinal stability score
     * @param signalToNoise signal-to-noise estimate
     * @param ambiguity multimodal ambiguity estimate
     * @param signalExistenceConfidence corridor-existence confidence
     * @param localizationConfidence center-localization confidence
     * @param optimizerCost normalized optimizer cost
     * @param inCorridorFraction fraction inside corridor shoulders
     * @param consensusModes pre-extraction fused mappings
     */
    public CandidateEvidence(
        String detectorMode,
        int totalProfiles,
        int supportedProfiles,
        int emptyProfiles,
        int maxConsecutiveEmptyProfiles,
        double totalIntensity,
        double meanIntensity,
        double meanGradientStrength,
        double longitudinalStability,
        double signalToNoise,
        double ambiguity,
        double signalExistenceConfidence,
        double localizationConfidence,
        double optimizerCost,
        double inCorridorFraction,
        List<String> consensusModes
    ) {
        this(detectorMode, totalProfiles, supportedProfiles, emptyProfiles, maxConsecutiveEmptyProfiles,
            totalIntensity, meanIntensity, meanGradientStrength, longitudinalStability, signalToNoise,
            ambiguity, signalExistenceConfidence, localizationConfidence, optimizerCost, inCorridorFraction,
            0.0, 0.0, consensusModes);
    }

    /**
     * Creates legacy evidence without corridor-aware confidence fields.
     *
     * @param detectorMode detector or palette mapping
     * @param totalProfiles total sampled profiles
     * @param supportedProfiles supported profiles
     * @param emptyProfiles empty profiles
     * @param maxConsecutiveEmptyProfiles longest empty run
     * @param totalIntensity total selected intensity
     * @param meanIntensity mean selected intensity
     * @param meanGradientStrength mean gradient strength
     * @param longitudinalStability longitudinal stability
     * @param signalToNoise signal-to-noise estimate
     * @param ambiguity multimodal ambiguity
     * @param consensusModes fused detector modes
     */
    public CandidateEvidence(
        String detectorMode,
        int totalProfiles,
        int supportedProfiles,
        int emptyProfiles,
        int maxConsecutiveEmptyProfiles,
        double totalIntensity,
        double meanIntensity,
        double meanGradientStrength,
        double longitudinalStability,
        double signalToNoise,
        double ambiguity,
        List<String> consensusModes
    ) {
        this(detectorMode, totalProfiles, supportedProfiles, emptyProfiles, maxConsecutiveEmptyProfiles,
            totalIntensity, meanIntensity, meanGradientStrength, longitudinalStability, signalToNoise,
            ambiguity, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, consensusModes);
    }

    /**
     * Creates an evidence object representing a candidate with no signal.
     *
     * @return empty candidate evidence
     */
    public static CandidateEvidence empty() {
        return new CandidateEvidence("", 0, 0, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
            0.0, 0.0, 0.0, 0.0, 0.0, 0.0, List.of());
    }

    /**
     * Checks whether this evidence contains enough heatmap signal to describe a real candidate.
     *
     * @return {@code true} when supported profiles and signal-to-noise are positive
     */
    public boolean hasSignal() {
        return supportedProfiles > 0 && totalIntensity > 0.0 && signalToNoise > 0.0;
    }

    /**
     * Computes the fraction of profiles backed by usable signal.
     *
     * @return supported profile ratio in the {@code [0,1]} range
     */
    public double supportRatio() {
        return totalProfiles <= 0 ? 0.0 : (double) supportedProfiles / totalProfiles;
    }

    /**
     * Returns a copy with a new detector mode.
     *
     * @param mode detector identifier to store
     * @return copied evidence with the detector mode changed
     */
    public CandidateEvidence withDetectorMode(String mode) {
        return new CandidateEvidence(
            mode == null ? "" : mode,
            totalProfiles,
            supportedProfiles,
            emptyProfiles,
            maxConsecutiveEmptyProfiles,
            totalIntensity,
            meanIntensity,
            meanGradientStrength,
            longitudinalStability,
            signalToNoise,
            ambiguity,
            signalExistenceConfidence,
            localizationConfidence,
            optimizerCost,
            inCorridorFraction,
            scalePersistence,
            scaleConflictFraction,
            consensusModes
        );
    }

    /**
     * Returns a copy with the list of fused detector modes.
     *
     * @param modes detector mappings fused before ridge extraction
     * @return copied evidence with consensus mode metadata
     */
    public CandidateEvidence withConsensusModes(List<String> modes) {
        return new CandidateEvidence(
            detectorMode,
            totalProfiles,
            supportedProfiles,
            emptyProfiles,
            maxConsecutiveEmptyProfiles,
            totalIntensity,
            meanIntensity,
            meanGradientStrength,
            longitudinalStability,
            signalToNoise,
            ambiguity,
            signalExistenceConfidence,
            localizationConfidence,
            optimizerCost,
            inCorridorFraction,
            scalePersistence,
            scaleConflictFraction,
            modes == null ? List.of() : List.copyOf(modes)
        );
    }

    /**
     * Serializes this evidence into a compact JSON object for debug bundles.
     *
     * @return JSON object string without secrets
     */
    public String toJson() {
        return "{"
            + "\"detectorMode\":\"" + escape(detectorMode) + "\","
            + "\"totalProfiles\":" + totalProfiles + ','
            + "\"supportedProfiles\":" + supportedProfiles + ','
            + "\"emptyProfiles\":" + emptyProfiles + ','
            + "\"maxConsecutiveEmptyProfiles\":" + maxConsecutiveEmptyProfiles + ','
            + "\"supportRatio\":" + supportRatio() + ','
            + "\"totalIntensity\":" + totalIntensity + ','
            + "\"meanIntensity\":" + meanIntensity + ','
            + "\"meanGradientStrength\":" + meanGradientStrength + ','
            + "\"longitudinalStability\":" + longitudinalStability + ','
            + "\"signalToNoise\":" + signalToNoise + ','
            + "\"ambiguity\":" + ambiguity + ','
            + "\"signalExistenceConfidence\":" + signalExistenceConfidence + ','
            + "\"localizationConfidence\":" + localizationConfidence + ','
            + "\"optimizerCost\":" + optimizerCost + ','
            + "\"inCorridorFraction\":" + inCorridorFraction + ','
            + "\"scalePersistence\":" + scalePersistence + ','
            + "\"scaleConflictFraction\":" + scaleConflictFraction + ','
            + "\"consensusModes\":" + stringArray(consensusModes)
            + "}";
    }

    private static String stringArray(List<String> values) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append('"').append(escape(values.get(i))).append('"');
        }
        return builder.append(']').toString();
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
