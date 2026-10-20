package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.nio.file.*;
import java.util.regex.Pattern;

/** Metadata tests use the separately supplied workflow literal, never a self-derived authority. */
final class ProspectiveTestInputs {
    static String expectedPins() throws Exception {
        String workflow=Files.readString(Path.of(".github/workflows/observation-prospective-fit.yml"));
        var matcher=Pattern.compile("PROSPECTIVE_PINS_SHA256: ([0-9a-f]{64})").matcher(workflow);
        if(!matcher.find())throw new IllegalStateException("Fixed candidate workflow digest absent");
        return matcher.group(1);
    }
    static BObservationIdentificationTool.RunReceipt historicalCatalogMetadata(Path output) throws Exception {
        HistoricalObservationMetadataFixture.run("catalog",output);
        return new BObservationIdentificationTool.RunReceipt("CATALOG_REGISTERED",output.resolve("summary.json"));
    }
    private ProspectiveTestInputs() { }
}
