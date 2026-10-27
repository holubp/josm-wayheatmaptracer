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
    CapturedFacts extractCapturedPosition(BExperimentalScalarCapture capture,EvidenceReadView evidence,
            String fieldKey,BExperimentalScalarCapture.CapturedProfiles profiles,
            CancellationProbe cancellation,AttemptMemoryLedger.Owner owner,BSharedWork work) {
        Objects.requireNonNull(work);work.authoritySlots(5);work.requireOwner(owner);work.requireProbe(cancellation);
        Objects.requireNonNull(profiles).validate(capture,evidence,fieldKey,owner,work,cancellation);
        work.claimSourceExtraction();
        AttemptMemoryLedger.Owner issued=null;
        try {
            work.poll();profiles.validate(capture,evidence,fieldKey,owner,work,cancellation);
            var original=profiles.profiles();int count=original.size();
            work.authoritySlots(6+fieldKey.length());
            if(count<1||count>81)throw new IllegalArgumentException("Captured facts profile inventory");
            //Facts capsule6refs+boolean=O49=72, checked callback2refs=O16=32.
            //The exact immutable factory list is borrowed; no copied/replayed profiles.
            work.metadata(72+32);
            issued=owner.child("captured-original-facts");
            for(int index=0;index<count;index++) {
                work.poll();profiles.validate(capture,evidence,fieldKey,owner,work,cancellation);
                work.authoritySlots(1);issued.retainIfAbsent(original.get(index));
            }
            CancellationProbe checked=new CapturedProbe(work,profiles);
            List<ProfileFacts> facts;
            work.sourceExtractionStarting();
            try {
                facts=extractReadView(evidence,original,checked,issued);
            } catch(RuntimeException|Error primary) {
                work.sourceExtractionFinished(false,primary instanceof java.util.concurrent.CancellationException);
                throw primary;
            }
            work.sourceExtractionFinished(true,false);
            for(int index=0;index<facts.size();index++) {
                work.poll();profiles.validate(capture,evidence,fieldKey,owner,work,cancellation);
                work.authoritySlots(1);
                if(facts.get(index).baseline()!=original.get(index))
                    throw new IllegalStateException("Extractor changed captured profile identity");
            }
            var result=new CapturedFacts(profiles,fieldKey,evidence.fields().get(fieldKey),facts,issued,work);
            work.poll();profiles.validate(capture,evidence,fieldKey,owner,work,cancellation);
            if(issued.isClosed())throw new IllegalStateException("Captured facts owner closed before publication");
            return result;
        } catch(RuntimeException|Error primary) {
            if(primary instanceof java.util.concurrent.CancellationException)
                try {work.sourceIssuerCancelled();}catch(RuntimeException|Error secondary){ /* preserve primary */ }
            if(issued!=null)try {issued.close();}catch(RuntimeException|Error secondary){ /* preserve primary */ }
            throw primary;
        }
    }
    private static final class CapturedProbe implements CancellationProbe {
        private final BSharedWork work;
        private final BExperimentalScalarCapture.CapturedProfiles profiles;
        private CapturedProbe(BSharedWork work,BExperimentalScalarCapture.CapturedProfiles profiles){this.work=work;this.profiles=profiles;}
        public boolean cancelled(){work.authoritySlots(2);work.poll();profiles.ensureOpen();return false;}
    }
    static final class CapturedFacts implements BSharedPositionEvidence.SourceAuthority {
        private final BExperimentalScalarCapture.CapturedProfiles source;
        private final String fieldKey;
        private final ScalarEvidenceField field;
        private final List<ProfileFacts> facts;
        private final AttemptMemoryLedger.Owner issued;
        private final BSharedWork work;
        private boolean closed;
        private CapturedFacts(BExperimentalScalarCapture.CapturedProfiles source,String key,
                ScalarEvidenceField field,List<ProfileFacts> facts,AttemptMemoryLedger.Owner issued,BSharedWork work) {
            this.source=source;this.fieldKey=key;this.field=field;this.facts=facts;this.issued=issued;this.work=work;
        }
        public void ensureOpen(){work.authoritySlots(3);source.ensureOpen();
            if(closed||issued.isClosed())throw new IllegalStateException("Captured facts closed");}
        List<ProfileFacts> facts(){ensureOpen();return facts;}
        void validate(BExperimentalScalarCapture capture,EvidenceReadView view,String key,
                BExperimentalScalarCapture.CapturedProfiles profiles,AttemptMemoryLedger.Owner owner,
                BSharedWork actualWork,CancellationProbe probe) {
            ensureOpen();actualWork.authoritySlots(7);source.validate(capture,view,key,owner,actualWork,probe);
            if(source!=profiles||work!=actualWork||!fieldKey.equals(key)||view.fields().get(key)!=field)
                throw new IllegalArgumentException("Foreign captured facts capability");
        }
        @Override public void close(){closed=true;issued.close();}
    }
    /** Issues only actual extractor output; source objects are immutable borrows, not co-owned pixels. */
    SharedPositionFacts extractSharedPosition(EvidenceSnapshot evidence,String fieldKey,
            List<ProbabilisticProfile> profiles,CancellationProbe cancellation,
            AttemptMemoryLedger.Owner owner,BSharedWork work) {
        Objects.requireNonNull(work);
        // Work/owner/probe references are checked before any acquisition or child owner.
        work.authoritySlots(3);work.requireOwner(owner);work.requireProbe(cancellation);
        work.claimSourceExtraction();
        AttemptMemoryLedger.Owner issued=null;
        try {
            work.poll();
            // Six header slots: snapshot, key, sequence, selected field, source and transform.
            work.authoritySlots(6);
            Objects.requireNonNull(evidence);Objects.requireNonNull(profiles);
            if(fieldKey==null||fieldKey.length()>8192)throw new IllegalArgumentException("Missing or unbounded selected source field");
            work.authoritySlots(fieldKey.length());
            ScalarEvidenceField field=evidence.fields().get(fieldKey);
            if(field==null||profiles.isEmpty()||profiles.size()>81)
                throw new IllegalArgumentException("Shared-position issuer inventory");
            int count=profiles.size();
            // Capability O80, bounded builder, up to3 profile-copy arrays (including
            // List.copyOf scratch), and exact returned-facts list. All share the1MiB holder.
            work.metadata(AttemptMemoryLedger.objectBytes(80)
                    +AttemptMemoryLedger.objectBytes(32)+2*AttemptMemoryLedger.objectBytes(16)
                    +4*AttemptMemoryLedger.arrayBytes(count,8));
            issued=owner.child("shared-position-original-facts");
            var copiedProfiles=new ArrayList<ProbabilisticProfile>(count);
            for(int index=0;index<count;index++) {
                work.poll();work.authoritySlots(1);
                var profile=Objects.requireNonNull(profiles.get(index));
                issued.retainIfAbsent(profile);copiedProfiles.add(profile);
            }
            if(profiles.size()!=count)throw new IllegalArgumentException("Original profile sequence changed");
            var originalProfiles=List.copyOf(copiedProfiles);
            List<ProfileFacts> facts;
            work.sourceExtractionStarting();
            try {
                facts=extract(evidence,originalProfiles,cancellation,issued);
            } catch(RuntimeException|Error failure) {
                work.sourceExtractionFinished(false,failure instanceof java.util.concurrent.CancellationException);
                throw failure;
            }
            work.sourceExtractionFinished(true,false);
            for(int index=0;index<facts.size();index++) {
                work.poll();work.authoritySlots(1);
                if(facts.get(index).baseline()!=originalProfiles.get(index))
                    throw new IllegalStateException("Extractor changed original profile identity");
            }
            var result=new SharedPositionFacts(evidence,fieldKey,field,originalProfiles,facts,owner,issued,cancellation,work);
            work.poll();return result;
        } catch(RuntimeException|Error failure) {
            if(failure instanceof java.util.concurrent.CancellationException)work.sourceIssuerCancelled();
            if(issued!=null)issued.close();throw failure;
        }
    }

    static final class SharedPositionFacts implements BSharedPositionEvidence.SourceAuthority {
        private final EvidenceSnapshot evidence;
        private final String fieldKey;
        private final ScalarEvidenceField field;
        private final List<ProbabilisticProfile> profiles;
        private final List<ProfileFacts> facts;
        private final AttemptMemoryLedger.Owner originalOwner,issued;
        private final CancellationProbe cancellation;
        private final BSharedWork work;
        private boolean closed;
        private SharedPositionFacts(EvidenceSnapshot evidence,String fieldKey,ScalarEvidenceField field,
                List<ProbabilisticProfile> profiles,List<ProfileFacts> facts,AttemptMemoryLedger.Owner originalOwner,
                AttemptMemoryLedger.Owner issued,CancellationProbe cancellation,BSharedWork work) {
            this.evidence=evidence;this.fieldKey=fieldKey;this.field=field;this.profiles=profiles;this.facts=facts;
            this.originalOwner=originalOwner;this.issued=issued;this.cancellation=cancellation;this.work=work;
        }
        public void ensureOpen() {
            work.ensureOpen();
            if(closed||originalOwner.isClosed()||issued.isClosed())
                throw new IllegalStateException("Shared-position facts capability closed");
        }
        List<ProfileFacts> facts(){ensureOpen();return facts;}
        void validate(EvidenceSnapshot snapshot,String key,List<ProfileFacts> supplied,BSharedWork actualWork) {
            Objects.requireNonNull(actualWork);actualWork.authoritySlots(7);ensureOpen();
            if(actualWork!=work||snapshot!=evidence||key==null||key.length()>8192||supplied!=facts)
                throw new IllegalArgumentException("Foreign shared-position facts authority");
            actualWork.authoritySlots(key.length());
            if(!fieldKey.equals(key)||snapshot.fields().get(key)!=field)
                throw new IllegalArgumentException("Foreign selected source field");
            actualWork.requireOwner(originalOwner);actualWork.requireProbe(cancellation);
            for(int index=0;index<facts.size();index++) {
                actualWork.poll();actualWork.authoritySlots(1);
                if(facts.get(index).baseline()!=profiles.get(index))
                    throw new IllegalArgumentException("Foreign original source profile");
            }
        }
        @Override public void close(){closed=true;issued.close();}
    }

    List<BIdentificationFacts.ProfileFacts> extract(EvidenceSnapshot evidence,
            List<ProbabilisticProfile> profiles, CancellationProbe cancellation,
            AttemptMemoryLedger.Owner owner) {
        return extractReadView(evidence,profiles,cancellation,owner);
    }

    /** Same raw extractor body; private so a read-view never becomes arbitrary issuer authority. */
    private List<BIdentificationFacts.ProfileFacts> extractReadView(EvidenceReadView evidence,
            List<ProbabilisticProfile> profiles,CancellationProbe cancellation,
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
