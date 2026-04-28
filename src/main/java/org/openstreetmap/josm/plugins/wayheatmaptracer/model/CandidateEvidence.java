package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.util.List;

public record CandidateEvidence(
    String detectorMode,
    int totalProfiles,
    int supportedProfiles,
    int emptyProfiles,
    int maxConsecutiveEmptyProfiles,
    double totalIntensity,
    double meanIntensity,
    double signalToNoise,
    double ambiguity,
    List<String> consensusModes
) {
    public static CandidateEvidence empty() {
        return new CandidateEvidence("", 0, 0, 0, 0, 0.0, 0.0, 0.0, 0.0, List.of());
    }

    public boolean hasSignal() {
        return supportedProfiles > 0 && totalIntensity > 0.0 && signalToNoise > 0.0;
    }

    public double supportRatio() {
        return totalProfiles <= 0 ? 0.0 : (double) supportedProfiles / totalProfiles;
    }

    public CandidateEvidence withDetectorMode(String mode) {
        return new CandidateEvidence(
            mode == null ? "" : mode,
            totalProfiles,
            supportedProfiles,
            emptyProfiles,
            maxConsecutiveEmptyProfiles,
            totalIntensity,
            meanIntensity,
            signalToNoise,
            ambiguity,
            consensusModes
        );
    }

    public CandidateEvidence withConsensusModes(List<String> modes) {
        return new CandidateEvidence(
            detectorMode,
            totalProfiles,
            supportedProfiles,
            emptyProfiles,
            maxConsecutiveEmptyProfiles,
            totalIntensity,
            meanIntensity,
            signalToNoise,
            ambiguity,
            modes == null ? List.of() : List.copyOf(modes)
        );
    }

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
            + "\"signalToNoise\":" + signalToNoise + ','
            + "\"ambiguity\":" + ambiguity + ','
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
