package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.List;
import java.util.OptionalDouble;

/** Detached, nonprobabilistic observations for the approved identification experiment. */
final class BIdentificationFacts {
    static final String VERSION = "b-identification-facts-3";
    enum Unit { SCALAR, METRE, DIMENSIONLESS }
    enum Availability { MEASURED, DERIVED, UNAVAILABLE }
    enum UnavailableReason { NOT_EXPOSED, NO_UNIQUE_RAW_RECEIPT, NO_NATIVE_METADATA, NO_SOURCE_PRECISION }
    enum Feature {
        PEAK, BACKGROUND, PROMINENCE, NOISE_DESCRIPTOR, QUANTIZATION,
        SHOULDER_THRESHOLD, RMS_DESCRIPTOR, FILTER_DESCRIPTOR,
        RAW_CENTER, B3_CENTER, B5_CENTER, CENTER_DISAGREEMENT,
        CORE_MINIMUM, CORE_MAXIMUM, SHOULDER_MINIMUM, SHOULDER_MAXIMUM,
        NATIVE_PITCH, INDEPENDENT_SAMPLE_COUNT, LEFT_DIRECT_SPAN, RIGHT_DIRECT_SPAN
    }
    record ScalarFact(Feature feature, Unit unit, Availability availability, OptionalDouble value) {
        ScalarFact {
            if (feature == null || unit != expectedUnit(feature) || availability == null || value == null
                    || (availability == Availability.UNAVAILABLE) == value.isPresent()
                    || value.isPresent() && !Double.isFinite(value.getAsDouble())) {
                throw new IllegalArgumentException("Inconsistent fact availability");
            }
        }
        UnavailableReason unavailableReason() {
            if (availability != Availability.UNAVAILABLE) return null;
            return switch(feature) {
                case QUANTIZATION -> UnavailableReason.NO_SOURCE_PRECISION;
                case NATIVE_PITCH,INDEPENDENT_SAMPLE_COUNT -> UnavailableReason.NO_NATIVE_METADATA;
                case PEAK,SHOULDER_MINIMUM,SHOULDER_MAXIMUM -> UnavailableReason.NO_UNIQUE_RAW_RECEIPT;
                default -> UnavailableReason.NOT_EXPOSED;
            };
        }
    }
    sealed interface Receipt permits RawReceipt, FlankReceipt, CorrelationReceipt, StageReceipt { }
    record RawReceipt(int firstPeakIndex, int lastPeakIndex, int winnerIndex) implements Receipt { }
    record FlankReceipt(int leftIndex, int rightIndex, boolean complete) implements Receipt { }
    /** Exact original correlated filter-stage measurement, not an independent observation. */
    record StageReceipt(
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.LocalScalarProfileExtractor.NestedCenterReceipt original,
            double firstOffsetMeters, double lastOffsetMeters) implements Receipt {
        StageReceipt {
            if (original == null || original.intervalMeasured() && (!Double.isFinite(firstOffsetMeters)
                    || !Double.isFinite(lastOffsetMeters) || firstOffsetMeters > lastOffsetMeters)
                    || !original.intervalMeasured() && (!Double.isNaN(firstOffsetMeters)||!Double.isNaN(lastOffsetMeters))
                    || original.available() && original.centerOffsetMeters()!=.5*(firstOffsetMeters+lastOffsetMeters))
                throw new IllegalArgumentException("Stage coordinate receipt mismatch");
        }
    }
    /** The same raw/B3/B5 samples share one physical information group. */
    record CorrelationReceipt(String fieldLineage, String transformId,
            boolean nativePitchKnown) implements Receipt { }
    record ModeFacts(String lineage, List<ScalarFact> values, List<Receipt> provenance) {
        ModeFacts {
            if (lineage == null || lineage.isBlank()) throw new IllegalArgumentException("Missing lineage");
            values = List.copyOf(values);
            provenance = List.copyOf(provenance);
            if (values.stream().map(ScalarFact::feature).distinct().count() != values.size()) {
                throw new IllegalArgumentException("Duplicate feature");
            }
        }
        ScalarFact fact(Feature feature) {
            for (var value : values) if (value.feature() == feature) return value;
            throw new IllegalArgumentException("Missing feature " + feature);
        }
    }
    record ProfileFacts(ProbabilisticProfile baseline, List<ModeFacts> modes, String featureVersion) {
        ProfileFacts {
            if(baseline==null||!VERSION.equals(featureVersion))throw new IllegalArgumentException("Unknown facts version");
            modes = List.copyOf(modes);
            for(var mode:modes) for(var receipt:mode.provenance()) if(receipt instanceof StageReceipt stage) {
                var original=stage.original();
                if(original.intervalMeasured()) {
                    if(original.firstCoreSampleIndex()<0||original.lastCoreSampleIndex()>=baseline.samples().size()
                            || baseline.samples().get(original.firstCoreSampleIndex()).offsetMeters()!=stage.firstOffsetMeters()
                            || baseline.samples().get(original.lastCoreSampleIndex()).offsetMeters()!=stage.lastOffsetMeters())
                        throw new IllegalArgumentException("Stage endpoints do not belong to profile samples");
                }
                Feature feature=switch(original.stage()){case RAW->Feature.RAW_CENTER;case B3->Feature.B3_CENTER;case B5->Feature.B5_CENTER;};
                var fact=mode.fact(feature);
                if(original.available() ? fact.value().isEmpty()||fact.value().getAsDouble()!=original.centerOffsetMeters()
                        :fact.value().isPresent())throw new IllegalArgumentException("Stage center does not match original receipt");
            }
        }
    }
    static Unit expectedUnit(Feature feature) {
        return switch(feature) {
            case PEAK,BACKGROUND,PROMINENCE,NOISE_DESCRIPTOR,QUANTIZATION,
                    SHOULDER_THRESHOLD,RMS_DESCRIPTOR,FILTER_DESCRIPTOR -> Unit.SCALAR;
            case INDEPENDENT_SAMPLE_COUNT -> Unit.DIMENSIONLESS;
            default -> Unit.METRE;
        };
    }
    private BIdentificationFacts() { }
}
