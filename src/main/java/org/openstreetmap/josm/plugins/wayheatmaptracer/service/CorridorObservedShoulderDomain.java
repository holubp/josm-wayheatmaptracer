package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.*;
import java.util.function.Consumer;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** R5 restriction of existing states to directly measured shoulders. No new movement authority. */
public final class CorridorObservedShoulderDomain {
    private static final long CACHE_LIMIT = 128L * 1024 * 1024;
    private CorridorObservedShoulderDomain() { }

    public enum Disposition {
        RESTRICTED, ALREADY_IN_DOMAIN, REFUSED_EMPTY_DOMAIN, INELIGIBLE_BOUNDARY,
        INELIGIBLE_UNOBSERVED, INELIGIBLE_PROTECTED, INELIGIBLE_MOTION,
        INELIGIBLE_CONFLICT, INELIGIBLE_PROVENANCE, INELIGIBLE_NONFINITE,
        INELIGIBLE_TUBE_INSIDE, SKIPPED_NO_BOUND_SOURCE_AUTHORITY,
        SKIPPED_OWNED_CORRESPONDENCE_PRIORITY, PREPARED, CANCELLED, RESOURCE_LIMIT
    }
    /** Relative coordinates only; original observation evidence is never modified. */
    public record Row(int originalRow, int oldCount, int retainedCount, double shoulderMinPx,
            double shoulderMaxPx, List<Double> removedOffsets, String evidenceKind, Disposition disposition) {
        public Row { removedOffsets = List.copyOf(removedOffsets); }
    }
    /** Rows examined includes a partially checked current row; rows contains completed row decisions only.
     * Retained bytes is the scoped conservative plan/domain reservation, not a measured heap peak. */
    public record Work(long rowsExamined, long statesExamined, long restrictedRows, long removedStates,
            long retainedBytes, Disposition outcome, List<Row> rows) {
        public Work { rows = List.copyOf(rows); }
        public long admissionUnits() { return statesExamined; }
        public static Work skipped(Disposition disposition) { return new Work(0,0,0,0,0,disposition,List.of()); }
    }
    /** Immutable restriction certificate bound to the original invocation and observed-row states. */
    public static final class Plan {
        private final Object token;
        private final CorridorWorkingSchedule.BoundContext context;
        private final List<List<Double>> states;
        private final boolean[] observed;
        private final Work work;
        private Plan(CorridorCenterlineOptimizer.OriginalDomain d, List<List<Double>> states, Work work) {
            token=d.token(); context=d.context(); this.work=work;
            if(work.outcome()==Disposition.SKIPPED_NO_BOUND_SOURCE_AUTHORITY) {
                this.states=d.states(); observed=null; return;
            }
            this.states=List.copyOf(states); observed=new boolean[states.size()];
            for(int row=0;row<observed.length;row++) observed[row]=d.track().points().containsKey(row);
        }
        public List<List<Double>> states() { return states; }
        public Work work() { return work; }
        public void requireBinding(CorridorCenterlineOptimizer.OriginalDomain d) {
            if(d.token()!=token || d.context()!=context || d.states().size()!=states.size())
                throw new IllegalArgumentException("Observed shoulder original invocation differs");
            if(observed==null) {
                if(d.states()!=states)throw new IllegalArgumentException("Skipped observed domain states differ");
                return;
            }
            for(int row=0;row<states.size();row++) {
                // Only an independently sealed gap plan may change other, unobserved rows.
                if((observed[row] || d.gapPlan().isEmpty()) && !sameBits(states.get(row),d.states().get(row)))
                    throw new IllegalArgumentException("Observed shoulder ordered states differ");
            }
        }
        private static boolean sameBits(List<Double> a,List<Double> b) {
            if(a.size()!=b.size()) return false;
            for(int i=0;i<a.size();i++) if(Double.doubleToLongBits(a.get(i))!=Double.doubleToLongBits(b.get(i))) return false;
            return true;
        }
    }

    /** Examines bound direct observations once and retains existing double values in their original order. */
    public static Plan restrict(CorridorCenterlineOptimizer.OriginalDomain d, Map<String,CorridorTrack> tracksById,
            Optional<SparseCorridorBundle> bundle, CancellationProbe cancellation, Consumer<Work> spent) {
        Objects.requireNonNull(d); Objects.requireNonNull(tracksById); Objects.requireNonNull(bundle);
        Objects.requireNonNull(cancellation); Objects.requireNonNull(spent);
        if(d.gapPlan().isPresent()) throw new IllegalArgumentException("Observed restriction must precede gap preparation");
        if(d.context()==null) {
            var work=Work.skipped(Disposition.SKIPPED_NO_BOUND_SOURCE_AUTHORITY); spent.accept(work);
            return new Plan(d,d.states(),work);
        }
        long rows=0,checks=0,restricted=0,removed=0,bytes=0;
        var diagnostics=new ArrayList<Row>();
        try {
            long points=0,pairs=0;
            for(int row=0;row<d.states().size();row++) {
                cancellation.checkpoint();
                points=Math.addExact(points,d.states().get(row).size());
                if(row>0) pairs=Math.addExact(pairs,Math.multiplyExact((long)d.states().get(row-1).size(),d.states().get(row).size()));
            }
            // Retained original + filtered lists, row receipts/removals and the native metric tables share one ceiling.
            bytes=Math.addExact(Math.multiplyExact(points,96L),Math.multiplyExact(d.states().size(),320L));
            if(Math.addExact(bytes,CorridorGapDomain.estimateMetricBytes(d.states().size(),points,pairs))>CACHE_LIMIT)
                throw limit("Combined observed-domain and metric storage cap");
            var states=new ArrayList<List<Double>>(d.states().size());
            var profiles=d.frozenProfiles();
            for(int row=0;row<profiles.size();row++) {
                cancellation.checkpoint();
                d.context().requireProfileFrame(profiles.get(row).source().samplingAnchor());
                var old=d.states().get(row); var point=d.track().points().get(row);
                var band=point==null?null:point.band();
                Disposition status=eligibility(d,tracksById,bundle,row);
                rows++;
                var lost=new ArrayList<Double>(); List<Double> retained=old;
                if(status==null) {
                    var keep=new ArrayList<Double>();
                    for(double value:old) {
                        cancellation.checkpoint();
                        if(checks>=d.budgets().maximumPairVisits()) throw limit("Observed membership work cap");
                        checks++;
                        if(value>=band.shoulderMinPx() && value<=band.shoulderMaxPx()) keep.add(value); else lost.add(value);
                    }
                    if(keep.isEmpty()) { status=Disposition.REFUSED_EMPTY_DOMAIN; lost.clear(); }
                    else if(lost.isEmpty()) status=Disposition.ALREADY_IN_DOMAIN;
                    else { retained=List.copyOf(keep); restricted++; removed+=lost.size(); status=Disposition.RESTRICTED; }
                }
                states.add(retained);
                diagnostics.add(new Row(row,old.size(),retained.size(),band==null?Double.NaN:band.shoulderMinPx(),
                    band==null?Double.NaN:band.shoulderMaxPx(),lost,d.track().parent()?"DIRECT_CHILD_UNION":"ELEMENTARY",status));
            }
            var work=new Work(rows,checks,restricted,removed,bytes,Disposition.PREPARED,diagnostics); spent.accept(work);
            return new Plan(d,states,work);
        } catch(java.util.concurrent.CancellationException cancelled) {
            spent.accept(new Work(rows,checks,restricted,removed,bytes,Disposition.CANCELLED,diagnostics)); throw cancelled;
        } catch(CorridorResourceLimitException limited) {
            spent.accept(new Work(rows,checks,restricted,removed,bytes,Disposition.RESOURCE_LIMIT,diagnostics)); throw limited;
        } catch(ArithmeticException overflow) {
            spent.accept(new Work(rows,checks,restricted,removed,bytes,Disposition.RESOURCE_LIMIT,diagnostics)); throw limit("Observed inventory overflow");
        }
    }

    private static Disposition eligibility(CorridorCenterlineOptimizer.OriginalDomain d,Map<String,CorridorTrack> tracks,
            Optional<SparseCorridorBundle> bundle,int row) {
        if(row==0 || row==d.states().size()-1) return Disposition.INELIGIBLE_BOUNDARY;
        var p=d.track().points().get(row); var profile=d.frozenProfiles().get(row); var tube=d.tube().at(row);
        if(p==null || p.support()!=CorridorPointSupport.DIRECT_UNION || !profile.supported() || !tube.observed())
            return Disposition.INELIGIBLE_UNOBSERVED;
        if(p.profileIndex()!=row || profile.index()!=row) return Disposition.INELIGIBLE_PROVENANCE;
        double chainage=profile.source().cumulativeGroundDistanceMeters(); var protection=d.context().sourceProtection();
        if(!Double.isFinite(chainage) || chainage<protection.firstChainage() || chainage>protection.lastChainage())
            return Disposition.INELIGIBLE_PROTECTED;
        if(d.junctions().at(row)!=null || !d.approaches().targetsAt(row).isEmpty() || protection.protectedChainages().contains(chainage))
            return Disposition.INELIGIBLE_PROTECTED;
        var band=p.band();
        if(!finite(band) || !Double.isFinite(tube.centerOffsetPx()) || !Double.isFinite(tube.motionSupport()))
            return Disposition.INELIGIBLE_NONFINITE;
        if(tube.motionSupport()!=0.0) return Disposition.INELIGIBLE_MOTION;
        var scale=d.scales().get(CorridorCenterlineOptimizer.scaleEvidenceKey(row,band.id()));
        if(tube.scaleConflict() || scale!=null && scale.scaleConflict()) return Disposition.INELIGIBLE_CONFLICT;
        if(band.boundaryCompleteness()!=CorridorBand.BoundaryCompleteness.COMPLETE || !provenance(d,tracks,bundle,row))
            return Disposition.INELIGIBLE_PROVENANCE;
        // Coarse persistence has the reviewed elementary fine-child witness only, never a synthetic union witness.
        if((tube.parentMerge() || scale!=null && scale.parentMerge()) && !(!d.track().parent() && !band.parentHypothesis()
                && scale!=null && scale.parentMerge()
                && !scale.hasCoarseCenterPrior() && tube.parentMerge() && sameIntervals(band,tube))) return Disposition.INELIGIBLE_PROVENANCE;
        if(tube.centerOffsetPx()>=band.shoulderMinPx() && tube.centerOffsetPx()<=band.shoulderMaxPx()) return Disposition.INELIGIBLE_TUBE_INSIDE;
        return null;
    }
    private static boolean provenance(CorridorCenterlineOptimizer.OriginalDomain d,Map<String,CorridorTrack> tracks,
            Optional<SparseCorridorBundle> optional,int row) {
        var band=d.track().points().get(row).band(); var profile=d.frozenProfiles().get(row);
        if(!d.track().parent()) return !band.parentHypothesis() && band.childIds().isEmpty() && profile.bands().contains(band);
        var bundle=optional.orElse(null);
        if(bundle==null || !band.parentHypothesis() || !bundle.id().equals(d.track().id())
                || !bundle.childTrackIds().equals(d.track().childTrackIds())) return false;
        var point=bundle.points().get(row);
        if(point==null || point.profileIndex()!=row || point.support()!=CorridorPointSupport.DIRECT_UNION
                || point.directContributorTrackIds().isEmpty() || !point.predictedContributorTrackIds().isEmpty()
                || new HashSet<>(point.directContributorTrackIds()).size()!=point.directContributorTrackIds().size()) return false;
        double lo=Double.POSITIVE_INFINITY,hi=Double.NEGATIVE_INFINITY,coreLo=Double.POSITIVE_INFINITY,coreHi=Double.NEGATIVE_INFINITY;
        var childBandIds=new HashSet<String>();
        for(String id:point.directContributorTrackIds()) {
            var child=tracks.get(id); var cp=child==null?null:child.points().get(row);
            if(child==null || !child.id().equals(id) || child.parent() || !child.childTrackIds().isEmpty() || cp==null
                    || cp.profileIndex()!=row || cp.support()!=CorridorPointSupport.DIRECT_UNION) return false;
            var b=cp.band(); var scale=d.scales().get(CorridorCenterlineOptimizer.scaleEvidenceKey(row,b.id()));
            if(b.parentHypothesis() || !b.childIds().isEmpty() || !finite(b) || !profile.bands().contains(b)
                    || b.boundaryCompleteness()!=CorridorBand.BoundaryCompleteness.COMPLETE
                    || scale!=null && scale.scaleConflict()) return false;
            lo=Math.min(lo,b.shoulderMinPx()); hi=Math.max(hi,b.shoulderMaxPx());
            coreLo=Math.min(coreLo,b.coreMinPx()); coreHi=Math.max(coreHi,b.coreMaxPx());
            if(!childBandIds.add(b.id()))return false;
        }
        return Double.compare(lo,band.shoulderMinPx())==0 && Double.compare(hi,band.shoulderMaxPx())==0
            && Double.compare(lo,point.shoulderMinPx())==0 && Double.compare(hi,point.shoulderMaxPx())==0
            && Double.compare(coreLo,band.coreMinPx())==0 && Double.compare(coreHi,band.coreMaxPx())==0
            && Double.compare(coreLo,point.coreMinPx())==0 && Double.compare(coreHi,point.coreMaxPx())==0
            && childBandIds.size()==band.childIds().size() && childBandIds.equals(new HashSet<>(band.childIds()));
    }
    private static boolean finite(CorridorBand b) {
        if(!(Double.isFinite(b.shoulderMinPx()) && Double.isFinite(b.shoulderMaxPx()) && Double.isFinite(b.coreMinPx())
            && Double.isFinite(b.coreMaxPx()) && Double.isFinite(b.centerOffsetPx()) && Double.isFinite(b.peakIntensity())
            && Double.isFinite(b.noiseFloor()) && Double.isFinite(b.valleyRatio()) && Double.isFinite(b.gradientStrength())
            && Double.isFinite(b.gradientBalance()) && Double.isFinite(b.scaleAgreement()) && Double.isFinite(b.signalExistenceConfidence())
            && Double.isFinite(b.localizationConfidence()) && Double.isFinite(b.uncertaintyPx())))return false;
        for(double center:b.nestedCentersPx())if(!Double.isFinite(center))return false;
        return true;
    }
    private static boolean sameIntervals(CorridorBand b,CorridorTubeSlice s) {
        return Double.compare(b.coreMinPx(),s.coreMinPx())==0 && Double.compare(b.coreMaxPx(),s.coreMaxPx())==0
            && Double.compare(b.shoulderMinPx(),s.shoulderMinPx())==0 && Double.compare(b.shoulderMaxPx(),s.shoulderMaxPx())==0;
    }
    private static CorridorResourceLimitException limit(String message) {
        return new CorridorResourceLimitException(message,CorridorResourceLimitException.Stage.ORIGINAL_ADMISSION,
            CorridorResourceLimitException.Reason.TABLE_OR_WORK_CAP,Map.of());
    }
}
