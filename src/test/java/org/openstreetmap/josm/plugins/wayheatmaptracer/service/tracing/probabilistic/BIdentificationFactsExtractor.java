package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.List;
import java.util.ArrayList;
import java.util.OptionalDouble;
import java.util.Objects;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.LocalScalarProfileExtractor;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.BIdentificationFacts.*;

/** Identification-only adapter; never changes extraction or live evidence. */
final class BIdentificationFactsExtractor {
    List<BIdentificationFacts.ProfileFacts> extract(EvidenceSnapshot evidence,
            List<ProbabilisticProfile> profiles, CancellationProbe cancellation,
            AttemptMemoryLedger.Owner owner) {
        Objects.requireNonNull(evidence); Objects.requireNonNull(profiles);
        Objects.requireNonNull(cancellation).checkpoint();
        if (profiles.size() > 4096) throw new IllegalArgumentException("Identification profile limit");
        var transaction = owner == null ? null : owner.child("identification-facts");
        long[] work = {0};
        java.util.function.LongConsumer charge = count -> {
            cancellation.checkpoint();
            work[0] = Math.addExact(work[0],count);
            if (work[0] > 8_000_000) throw new IllegalArgumentException("Identification work limit");
        };
        try {
            var result = new ArrayList<ProfileFacts>();
            for (var profile : profiles) {
                charge.accept(profile.samples().size());
                long bytes = Math.addExact(1024L + 96L * profile.samples().size(),
                        8192L * profile.modes().size());
                for (var mode : profile.modes()) bytes = Math.addExact(bytes,
                        4L * (mode.id().length() + mode.evidenceLineage().length()));
                // Reserve before samples, receipts or immutable copies are allocated.
                var reservation = transaction == null ? null : transaction.reserve(bytes);
                var scratch = transaction == null ? null : transaction.child("profile-receipts");
                try {
                    var samples = profile.samples().stream().map(sample -> new LocalScalarProfileExtractor.Sample(
                            sample.offsetMeters(),sample.intensity(),sample.valid())).toList();
                    var detailed = new LocalScalarProfileExtractor().extractDetailed(samples,
                            profile.sourcePitchMeters(),EvidenceModelParameters.defaults().localization(),
                            scratch,cancellation,charge);
                    var modes = new ArrayList<ModeFacts>();
                    for (var mode : profile.modes()) {
                        charge.accept(1);
                        var values = new ArrayList<ScalarFact>();
                        var receipts = new ArrayList<Receipt>();
                        for (var feature : Feature.values()) values.add(new ScalarFact(feature,unit(feature),
                                Availability.UNAVAILABLE,OptionalDouble.empty()));
                        set(values,Feature.NOISE_DESCRIPTOR,profile.noiseFloor(),Availability.DERIVED);
                        set(values,Feature.CORE_MINIMUM,mode.coreMinimumMeters(),Availability.DERIVED);
                        set(values,Feature.CORE_MAXIMUM,mode.coreMaximumMeters(),Availability.DERIVED);
                        set(values,Feature.LEFT_DIRECT_SPAN,mode.leftDirectSpanMeters(),Availability.DERIVED);
                        set(values,Feature.RIGHT_DIRECT_SPAN,mode.rightDirectSpanMeters(),Availability.DERIVED);
                        if (profile.nativePitchKnown()) set(values,Feature.NATIVE_PITCH,
                                profile.sourcePitchMeters(),Availability.MEASURED);
                        // Match exact elementary geometry and peak provenance, never nearest neighbor.
                        var matching = detailed.modeObservationReceipts().stream().map(
                                LocalScalarProfileExtractor.ModeObservationReceipt::mode).filter(raw -> !mode.groupedParent()
                                && raw.coreMinimumMeters() == mode.coreMinimumMeters()
                                && raw.coreMaximumMeters() == mode.coreMaximumMeters()
                                && raw.localizationSigmaMeters() == mode.localizationSigmaMeters()
                                && raw.existenceConfidence() == mode.existenceConfidence()
                                && raw.localizationConfidence() == mode.localizationConfidence()
                                && raw.scalarAmplitudeReliability() == mode.scalarAmplitudeReliability()
                                && raw.nestedCenterOffsetsMeters().equals(mode.nestedCenterOffsetsMeters())
                                && raw.peakOffsetsMeters().equals(mode.peakOffsetsMeters())).toList();
                        if (matching.size() == 1) {
                            var raw = matching.get(0);
                            for (var observation : detailed.modeObservationReceipts()) if (observation.mode() == raw) {
                                set(values,Feature.BACKGROUND,observation.localBackground(),Availability.MEASURED);
                                set(values,Feature.PROMINENCE,observation.prominence(),Availability.MEASURED);
                                set(values,Feature.SHOULDER_THRESHOLD,observation.shoulderThreshold(),Availability.MEASURED);
                                set(values,Feature.RMS_DESCRIPTOR,observation.rmsUncertainty(),Availability.MEASURED);
                                set(values,Feature.FILTER_DESCRIPTOR,observation.filterUncertainty(),Availability.MEASURED);
                                double minimum = Double.POSITIVE_INFINITY;
                                double maximum = Double.NEGATIVE_INFINITY;
                                for (var stage : observation.nestedCenters()) {
                                    receipts.add(new StageReceipt(stage,
                                            stage.firstCoreSampleIndex() < 0 ? Double.NaN
                                                : samples.get(stage.firstCoreSampleIndex()).offsetMeters(),
                                            stage.lastCoreSampleIndex() < 0 ? Double.NaN
                                                : samples.get(stage.lastCoreSampleIndex()).offsetMeters()));
                                    if (!stage.available()) continue;
                                    Feature feature = switch(stage.stage()) {
                                        case RAW -> Feature.RAW_CENTER;
                                        case B3 -> Feature.B3_CENTER;
                                        case B5 -> Feature.B5_CENTER;
                                    };
                                    set(values,feature,stage.centerOffsetMeters(),Availability.MEASURED);
                                    minimum = Math.min(minimum,stage.centerOffsetMeters());
                                    maximum = Math.max(maximum,stage.centerOffsetMeters());
                                }
                                if (Double.isFinite(minimum) && Double.isFinite(maximum))
                                    set(values,Feature.CENTER_DISAGREEMENT,maximum-minimum,Availability.DERIVED);
                            }
                            for (var receipt : detailed.rawCoreReceipts()) if (receipt.mode() == raw) {
                                receipts.add(new RawReceipt(receipt.rawPeak().firstSampleIndex(),
                                        receipt.rawPeak().lastSampleIndex(),receipt.rawStageWinnerSampleIndex()));
                                set(values,Feature.PEAK,receipt.rawPeak().value(),Availability.MEASURED);
                                // Keep winning raw sample identity separate from tagged raw center above.
                            }
                            for (var flank : detailed.flankReceipts()) if (flank.mode() == raw) {
                                receipts.add(new FlankReceipt(flank.leftBracketSampleIndex(),
                                        flank.rightBracketSampleIndex(),flank.hasTwoSidedValidOutwardContinuation()));
                                if (flank.leftBracketSampleIndex() >= 0) set(values,Feature.SHOULDER_MINIMUM,
                                        samples.get(flank.leftBracketSampleIndex()).offsetMeters(),Availability.MEASURED);
                                if (flank.rightBracketSampleIndex() >= 0) set(values,Feature.SHOULDER_MAXIMUM,
                                        samples.get(flank.rightBracketSampleIndex()).offsetMeters(),Availability.MEASURED);
                            }
                        }
                        receipts.add(new CorrelationReceipt(evidence.sourceIdentity(),
                                evidence.transform().transformId(),profile.nativePitchKnown()));
                        modes.add(new ModeFacts(mode.evidenceLineage(),values,receipts));
                    }
                    var facts = new ProfileFacts(profile,modes,VERSION);
                    if (reservation != null) reservation.adopt(facts);
                    result.add(facts);
                } finally {
                    if (scratch != null) scratch.close();
                    if (reservation != null) reservation.close();
                }
            }
            cancellation.checkpoint();
            var frozen = List.copyOf(result);
            if (transaction != null) transaction.transferTo(owner);
            return frozen;
        } finally { if (transaction != null) transaction.close(); }
    }

    private static Unit unit(Feature feature) {
        return BIdentificationFacts.expectedUnit(feature);
    }

    private static void set(List<ScalarFact> values,Feature feature,double value,Availability availability) {
        values.set(feature.ordinal(),new ScalarFact(feature,unit(feature),availability,OptionalDouble.of(value)));
    }

    /** No positive certificate is exposed without interval bounds and original own-threshold provenance. */
    Availability connectionCertificateAvailability() { return Availability.UNAVAILABLE; }

    /** Numerical diagnostic only: NEVER usable as continuous connection or position authority. */
    boolean diagnosticChord(ScalarEvidenceField field, RasterMetricTransform transform,
            MetricRegion region, MetricPoint start, MetricPoint end, double startThreshold,
            double endThreshold, CancellationProbe cancellation) {
        cancellation.checkpoint();
        if (!Double.isFinite(startThreshold) || !Double.isFinite(endThreshold)
                || startThreshold < 0 || endThreshold < 0 || startThreshold > 1 || endThreshold > 1
                || !region.containsSegment(start,end,cancellation,ignored -> { },null)) return false;
        var a = transform.metricToPixelCenter(start);
        var b = transform.metricToPixelCenter(end);
        double dx=b.x()-a.x(),dy=b.y()-a.y();
        if (dx == 0 && dy == 0) return false;
        double t=0;
        long limit=(long)field.width()+field.height()+2;
        if (limit > 1_000_000) return false;
        for (long visited=0; t < 1 && visited < limit; visited++) {
            cancellation.checkpoint();
            double x=a.x()+t*dx,y=a.y()+t*dy;
            int cx=(int)Math.floor(x),cy=(int)Math.floor(y);
            if (dx < 0 && x == cx) cx--;
            if (dy < 0 && y == cy) cy--;
            if (!field.supportsInterpolationCell(cx,cy)) return false;
            double tx=dx==0 ? Double.POSITIVE_INFINITY : ((dx>0 ? cx+1 : cx)-a.x())/dx;
            double ty=dy==0 ? Double.POSITIVE_INFINITY : ((dy>0 ? cy+1 : cy)-a.y())/dy;
            double next=Math.min(1,Math.min(tx,ty));
            if (!(next > t)) return false; // uncertain boundary traversal cannot certify support
            double f00=field.sample(cx,cy).orElseThrow(),f10=field.sample(cx+1,cy).orElseThrow();
            double f01=field.sample(cx,cy+1).orElseThrow(),f11=field.sample(cx+1,cy+1).orElseThrow();
            double g0=response(a.x()+t*dx-cx,a.y()+t*dy-cy,f00,f10,f01,f11)
                    -(startThreshold+t*(endThreshold-startThreshold));
            double mid=(t+next)*.5;
            double gm=response(a.x()+mid*dx-cx,a.y()+mid*dy-cy,f00,f10,f01,f11)
                    -(startThreshold+mid*(endThreshold-startThreshold));
            double g1=response(a.x()+next*dx-cx,a.y()+next*dy-cy,f00,f10,f01,f11)
                    -(startThreshold+next*(endThreshold-startThreshold));
            double qa=2*(g0+g1-2*gm),qb=g1-g0-qa;
            double minimum=Math.min(g0,g1);
            if (qa>0) {
                double stationary=-qb/(2*qa);
                if (stationary>0 && stationary<1) minimum=Math.min(minimum,
                        (qa*stationary+qb)*stationary+g0);
            }
            // A nonpositive/rounding-scale margin is unavailable, never positive proof.
            if (!(minimum>128*Math.ulp(1.0))) return false;
            t=next;
        }
        return t == 1;
    }

    private static double response(double x,double y,double a,double b,double c,double d) {
        return (1-y)*((1-x)*a+x*b)+y*((1-x)*c+x*d);
    }
}
