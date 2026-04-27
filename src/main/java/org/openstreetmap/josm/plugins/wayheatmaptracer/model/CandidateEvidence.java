package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.util.List;

public record CandidateEvidence(
    String detectorMode,
    int supportedProfiles,
    int emptyProfiles,
    double totalIntensity,
    double meanIntensity,
    double signalToNoise,
    double ambiguity,
    List<String> consensusModes
) {
    public static CandidateEvidence empty() {
        return new CandidateEvidence("", 0, 0, 0.0, 0.0, 0.0, 0.0, List.of());
    }

    public boolean hasSignal() {
        return supportedProfiles > 0 && totalIntensity > 0.0 && signalToNoise > 0.0;
    }

    public CandidateEvidence withDetectorMode(String mode) {
        return new CandidateEvidence(
            mode == null ? "" : mode,
            supportedProfiles,
            emptyProfiles,
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
            supportedProfiles,
            emptyProfiles,
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
            + "\"supportedProfiles\":" + supportedProfiles + ','
            + "\"emptyProfiles\":" + emptyProfiles + ','
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
